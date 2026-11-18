package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TVHAY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
private const val ENTRY_SEP = "|||"
private const val PAIR_SEP = ":::"

private object TvHayCrypto {
    private val ORIGIN_PATTERN = Pattern.compile("""^(https?://[^/]+)""", Pattern.CASE_INSENSITIVE)
    private val HASH_PATTERN = Pattern.compile("""[?&]hash=([0-9a-fA-F]+)""")
    private val IV_PATTERN = Pattern.compile("""iv=([0-9a-fA-F]+)""")

    fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    fun gcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun looksLikePlaylist(t: String?): Boolean {
        if (t == null) return false
        val clean = t.trimStart('\uFEFF').trimStart()
        return clean.startsWith("#EXTM3U") &&
            clean.lines().any { it.isNotBlank() && !it.trimStart().startsWith("#") }
    }

    fun decryptPlaylist(text: String, videoHash: String): String? {
        val clean = text.trimStart('\uFEFF')
        if (!clean.contains("#ENC-AESGCM") && !clean.contains("#EXT-X-B65")) return clean
        val m = IV_PATTERN.matcher(clean)
        val iv = if (m.find()) m.group(1) else null
        if (iv.isNullOrBlank()) return null
        val line = clean.lines().map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("stream-derive-v1".toByteArray(), "HmacSHA256"))
        val key = mac.doFinal(videoHash.toByteArray())
        val plain = gcmDecrypt(key, hexToBytes(iv), Base64.decode(line, Base64.DEFAULT), null)
        return String(plain, Charsets.UTF_8)
    }

    fun originOf(url: String): String {
        val m = ORIGIN_PATTERN.matcher(url)
        return if (m.find()) m.group(1).orEmpty() else ""
    }

    fun resolveUrl(base: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val origin = originOf(base)
        if (trimmed.startsWith("//")) {
            val scheme = if (base.startsWith("http://", ignoreCase = true)) "http:" else "https:"
            return scheme + trimmed
        }
        if (trimmed.startsWith("/") && origin.isNotEmpty()) {
            return origin + trimmed
        }
        val dir = base.substringBefore('#').substringBefore('?').substringBeforeLast('/') + "/"
        return dir + trimmed
    }

    fun hashOf(embed: String): String {
        val m = HASH_PATTERN.matcher(embed)
        return if (m.find()) m.group(1).orEmpty() else ""
    }
}

/**
 * Proxy HTTP cục bộ trên 127.0.0.1 dùng OkHttp HTTP/2:
 *  - Giữ Referer/Origin và giao thức HTTP/2 để vượt 403 trên StreamC & StreamVSMov.
 *  - Giải mã playlist #ENC-AESGCM / #EXT-X-B65 (StreamC).
 *  - Gỡ bỏ header PNG giả + padding 0xFF trước gói tin MPEG-TS (StreamVSMov .png & StreamC .png)
 *    và trả về Content-Type: video/mp2t cho ExoPlayer / Media3 (CloudStream 4.5.2+).
 */
private object TvHayLocalProxy {
    private sealed class ProxyEntry {
        class InlinePlaylist(val body: ByteArray) : ProxyEntry()
        class RemotePlaylist(
            val url: String,
            val embed: String,
            val hashes: List<String>,
            val stripPng: Boolean
        ) : ProxyEntry()

        class RemoteSegment(
            val url: String,
            val embed: String,
            val forceMime: String,
            val stripPng: Boolean
        ) : ProxyEntry()
    }

    private val seq = AtomicLong(0)
    private val pool = Executors.newCachedThreadPool()
    private val entries = Collections.synchronizedMap(
        object : LinkedHashMap<String, ProxyEntry>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProxyEntry>?): Boolean =
                size > 60_000
        }
    )

    private var server: ServerSocket? = null
    private var listenPort: Int = -1

    @Volatile
    private var cachedClient: OkHttpClient? = null

    private val URI_ATTR_PATTERN = Pattern.compile("""URI="([^"]+)"""")

    fun client(): OkHttpClient {
        cachedClient?.let { return it }
        return synchronized(this) {
            cachedClient ?: app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
                .also { cachedClient = it }
        }
    }

    @Synchronized
    private fun ensureStarted(): Int {
        val current = server
        if (current != null && !current.isClosed && listenPort > 0) return listenPort
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        listenPort = ss.localPort
        Thread {
            while (!ss.isClosed) {
                val clientSock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { handle(clientSock) }
            }
        }.apply { isDaemon = true }.start()
        return listenPort
    }

    fun registerPlaylist(
        rawPlaylist: String,
        baseUrl: String,
        embed: String,
        hashes: List<String>,
        stripPng: Boolean = true
    ): String {
        val port = ensureStarted()
        val rewritten = rewritePlaylist(rawPlaylist, baseUrl, embed, hashes, stripPng, port)
        val id = "pl_${seq.incrementAndGet()}.m3u8"
        entries[id] = ProxyEntry.InlinePlaylist(rewritten.toByteArray(Charsets.UTF_8))
        return "http://127.0.0.1:$port/$id"
    }

    private fun registerRemotePlaylist(
        url: String,
        embed: String,
        hashes: List<String>,
        stripPng: Boolean,
        port: Int
    ): String {
        val id = "pl_${seq.incrementAndGet()}.m3u8"
        entries[id] = ProxyEntry.RemotePlaylist(url, embed, hashes, stripPng)
        return "http://127.0.0.1:$port/$id"
    }

    private fun registerSegment(
        url: String,
        embed: String,
        forceMime: String,
        ext: String,
        stripPng: Boolean,
        port: Int
    ): String {
        val id = "seg_${seq.incrementAndGet()}.$ext"
        entries[id] = ProxyEntry.RemoteSegment(url, embed, forceMime, stripPng)
        return "http://127.0.0.1:$port/$id"
    }

    private fun detectSegMimeAndExt(url: String): Pair<String, String> {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m4s") || clean.endsWith(".mp4") -> "video/mp4" to "m4s"
            clean.endsWith(".aac") -> "audio/aac" to "aac"
            clean.endsWith(".vtt") -> "text/vtt; charset=utf-8" to "vtt"
            clean.endsWith(".key") -> "application/octet-stream" to "key"
            else -> "video/mp2t" to "ts"
        }
    }

    private fun replaceUriAttr(
        line: String,
        transform: (String) -> String
    ): String {
        val m = URI_ATTR_PATTERN.matcher(line)
        val sb = StringBuffer()
        while (m.find()) {
            val rawUri = m.group(1) ?: ""
            val replacement = """URI="${transform(rawUri)}""""
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    private fun rewritePlaylist(
        text: String,
        baseUrl: String,
        embed: String,
        hashes: List<String>,
        stripPng: Boolean,
        port: Int
    ): String {
        val lines = text.trimStart('\uFEFF').lines()
        val out = StringBuilder(text.length + 512)
        var nextIsVariant = false

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> out.append('\n')

                trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true) -> {
                    if (trimmed.contains("URI=\"")) {
                        val replaced = replaceUriAttr(trimmed) { raw ->
                            val abs = TvHayCrypto.resolveUrl(baseUrl, raw)
                            registerRemotePlaylist(abs, embed, hashes, stripPng, port)
                        }
                        out.append(replaced).append('\n')
                    } else {
                        nextIsVariant = true
                        out.append(trimmed).append('\n')
                    }
                }

                trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val replaced = replaceUriAttr(trimmed) { raw ->
                        val abs = TvHayCrypto.resolveUrl(baseUrl, raw)
                        when {
                            trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) ->
                                registerRemotePlaylist(abs, embed, hashes, stripPng, port)
                            trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) ->
                                registerSegment(abs, embed, "video/mp4", "mp4", stripPng, port)
                            else ->
                                registerSegment(abs, embed, "application/octet-stream", "key", false, port)
                        }
                    }
                    out.append(replaced).append('\n')
                }

                trimmed.startsWith("#") -> out.append(trimmed).append('\n')

                else -> {
                    val abs = TvHayCrypto.resolveUrl(baseUrl, trimmed)
                    val proxied = if (nextIsVariant) {
                        nextIsVariant = false
                        registerRemotePlaylist(abs, embed, hashes, stripPng, port)
                    } else {
                        val (mime, ext) = detectSegMimeAndExt(abs)
                        val isImageMask = abs.substringBefore('?').lowercase().endsWith(".png") ||
                            abs.substringBefore('?').lowercase().endsWith(".jpg") ||
                            abs.substringBefore('?').lowercase().endsWith(".webp")
                        registerSegment(abs, embed, mime, ext, stripPng || isImageMask, port)
                    }
                    out.append(proxied).append('\n')
                }
            }
        }
        return out.toString()
    }

    fun fetchTextWithHttp2(
        url: String,
        embed: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String>? {
        val origin = TvHayCrypto.originOf(embed)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", TVHAY_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
        if (embed.isNotBlank()) reqBuilder.header("Referer", embed)
        if (origin.isNotBlank()) reqBuilder.header("Origin", origin)
        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        return runCatching {
            client().newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        }.getOrNull()
    }

    fun postJsonWithHttp2(
        url: String,
        jsonBody: String,
        embed: String
    ): Pair<Int, String>? {
        val origin = TvHayCrypto.originOf(embed)
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", TVHAY_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply {
                if (origin.isNotBlank()) header("Origin", origin)
                if (embed.isNotBlank()) header("Referer", embed)
            }
            .build()

        return runCatching {
            client().newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        }.getOrNull()
    }

    fun probeFirstSegment(playlistText: String, baseUrl: String, embed: String): Boolean {
        val firstSeg = playlistText.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false

        if (playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return true

        val abs = TvHayCrypto.resolveUrl(baseUrl, firstSeg)
        val origin = TvHayCrypto.originOf(embed)
        val referers = listOf(embed, if (origin.isNotBlank()) "$origin/" else "").filter { it.isNotBlank() }.distinct()

        for (ref in referers) {
            val req = Request.Builder()
                .url(abs)
                .header("User-Agent", TVHAY_UA)
                .header("Accept", "*/*")
                .header("Referer", ref)
                .apply { if (origin.isNotBlank()) header("Origin", origin) }
                .header("Range", "bytes=0-63")
                .build()

            val ok = runCatching {
                client().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    val buf = ByteArray(64)
                    val n = body.byteStream().read(buf)
                    if (n <= 0) return@use false
                    val head = String(buf, 0, n, Charsets.UTF_8).trimStart()
                    !head.startsWith("<!doctype", ignoreCase = true) &&
                        !head.startsWith("<html", ignoreCase = true)
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /**
     * Tìm vị trí bắt đầu thực sự của dữ liệu MPEG-TS (0x47 với chu kỳ 188 byte)
     * hoặc bỏ qua header PNG + phần đệm 0xFF (IEND + 4 byte CRC + 0xFF*).
     */
    private fun findTsStripOffset(buf: ByteArray, len: Int): Int {
        if (len < 16) return 0
        val isPng = buf[0] == 0x89.toByte() &&
            buf[1] == 0x50.toByte() &&
            buf[2] == 0x4E.toByte() &&
            buf[3] == 0x47.toByte()
        if (!isPng) return 0

        if (len > 377) {
            val limit = len - 377
            for (i in 8..limit) {
                if (buf[i] == 0x47.toByte() &&
                    buf[i + 188] == 0x47.toByte() &&
                    buf[i + 376] == 0x47.toByte()
                ) {
                    return i
                }
            }
        } else if (len > 189) {
            val limit = len - 189
            for (i in 8..limit) {
                if (buf[i] == 0x47.toByte() && buf[i + 188] == 0x47.toByte()) {
                    return i
                }
            }
        }

        val iendLimit = len - 8
        for (i in 8..iendLimit) {
            if (buf[i] == 0x49.toByte() &&
                buf[i + 1] == 0x45.toByte() &&
                buf[i + 2] == 0x4E.toByte() &&
                buf[i + 3] == 0x44.toByte()
            ) {
                var pos = i + 8
                while (pos < len && buf[pos] == 0xFF.toByte()) {
                    pos++
                }
                if (pos < len) return pos
            }
        }
        return 0
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon > 0) {
                    val key = h.substring(0, colon).trim()
                    if (key.equals("Range", ignoreCase = true)) {
                        rangeHeader = h.substring(colon + 1).trim()
                    }
                }
            }

            val reqParts = requestLine.split(" ")
            val method = reqParts.getOrNull(0)?.uppercase() ?: "GET"
            val id = reqParts.getOrNull(1)?.trimStart('/')?.substringBefore('?')
            val entry = id?.let { entries[it] }
            val out = socket.getOutputStream()

            if (entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            when (entry) {
                is ProxyEntry.InlinePlaylist -> {
                    val hdr = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Content-Length: ${entry.body.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") out.write(entry.body)
                    out.flush()
                }

                is ProxyEntry.RemotePlaylist -> {
                    val fetched = fetchTextWithHttp2(entry.url, entry.embed)
                    if (fetched == null || fetched.first !in 200..299) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    var body = fetched.second
                    if (body.contains("#ENC-AESGCM") || body.contains("#EXT-X-B65")) {
                        var decoded: String? = null
                        for (h in entry.hashes) {
                            val candidate = runCatching { TvHayCrypto.decryptPlaylist(body, h) }.getOrNull()
                            if (TvHayCrypto.looksLikePlaylist(candidate)) {
                                decoded = candidate
                                break
                            }
                        }
                        if (decoded == null) {
                            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            return
                        }
                        body = decoded
                    }
                    if (!TvHayCrypto.looksLikePlaylist(body)) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    val rewrittenBytes = rewritePlaylist(
                        body,
                        entry.url,
                        entry.embed,
                        entry.hashes,
                        entry.stripPng,
                        ensureStarted()
                    ).toByteArray(Charsets.UTF_8)
                    val hdr = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Content-Length: ${rewrittenBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") out.write(rewrittenBytes)
                    out.flush()
                }

                is ProxyEntry.RemoteSegment -> {
                    relaySegment(entry, method, rangeHeader, out)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun relaySegment(
        entry: ProxyEntry.RemoteSegment,
        method: String,
        rangeHeader: String?,
        out: java.io.OutputStream
    ) {
        val origin = TvHayCrypto.originOf(entry.embed)
        val referers = listOf(entry.embed, if (origin.isNotBlank()) "$origin/" else "")
            .filter { it.isNotBlank() }
            .distinct()

        for (i in referers.indices) {
            val req = Request.Builder()
                .url(entry.url)
                .apply {
                    if (method == "HEAD") head() else get()
                    header("User-Agent", TVHAY_UA)
                    header("Accept", "*/*")
                    header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                    header("Referer", referers[i])
                    if (origin.isNotBlank()) header("Origin", origin)
                    if (!rangeHeader.isNullOrBlank() && !entry.stripPng) header("Range", rangeHeader)
                }
                .build()

            val resp = try {
                client().newCall(req).execute()
            } catch (_: Exception) {
                null
            }

            if (resp == null) continue

            resp.use { r ->
                if (r.code == 403 && i < referers.lastIndex) {
                    return@use
                }
                if (r.code !in 200..299) {
                    out.write("HTTP/1.1 ${r.code} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val body = r.body
                val upstreamLen = body?.contentLength() ?: -1L

                if (method == "HEAD" || body == null) {
                    val hdr = StringBuilder()
                        .append("HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: ").append(entry.forceMime).append("\r\n")
                        .append("Access-Control-Allow-Origin: *\r\n")
                    if (upstreamLen >= 0 && !entry.stripPng) {
                        hdr.append("Content-Length: ").append(upstreamLen).append("\r\n")
                    }
                    hdr.append("Connection: close\r\n\r\n")
                    out.write(hdr.toString().toByteArray(Charsets.US_ASCII))
                    out.flush()
                    return
                }

                body.byteStream().use { input ->
                    if (entry.stripPng && rangeHeader.isNullOrBlank()) {
                        val headBuf = ByteArray(4096)
                        var headRead = 0
                        while (headRead < headBuf.size) {
                            val n = input.read(headBuf, headRead, headBuf.size - headRead)
                            if (n <= 0) break
                            headRead += n
                        }
                        val stripOff = findTsStripOffset(headBuf, headRead)
                        val adjustedLen = if (upstreamLen >= 0L) (upstreamLen - stripOff).coerceAtLeast(0L) else -1L
                        val hdr = StringBuilder()
                            .append("HTTP/1.1 200 OK\r\n")
                            .append("Content-Type: ").append(entry.forceMime).append("\r\n")
                            .append("Access-Control-Allow-Origin: *\r\n")
                        if (adjustedLen >= 0L) {
                            hdr.append("Content-Length: ").append(adjustedLen).append("\r\n")
                        }
                        hdr.append("Connection: close\r\n\r\n")
                        out.write(hdr.toString().toByteArray(Charsets.US_ASCII))
                        if (headRead > stripOff) {
                            out.write(headBuf, stripOff, headRead - stripOff)
                        }
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        out.flush()
                        return
                    } else {
                        val statusText = if (r.code == 206) "Partial Content" else "OK"
                        val hdr = StringBuilder()
                            .append("HTTP/1.1 ").append(r.code).append(' ').append(statusText).append("\r\n")
                            .append("Content-Type: ").append(entry.forceMime).append("\r\n")
                            .append("Access-Control-Allow-Origin: *\r\n")
                        r.header("Content-Range")?.let { hdr.append("Content-Range: ").append(it).append("\r\n") }
                        r.header("Accept-Ranges")?.let { hdr.append("Accept-Ranges: ").append(it).append("\r\n") }
                        if (upstreamLen >= 0) hdr.append("Content-Length: ").append(upstreamLen).append("\r\n")
                        hdr.append("Connection: close\r\n\r\n")
                        out.write(hdr.toString().toByteArray(Charsets.US_ASCII))
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        out.flush()
                        return
                    }
                }
            }
        }

        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }
}

class TvHayProvider : MainAPI() {
    override var mainUrl = "https://tvhay.buzz"
    override var name = "TvHay"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    companion object {
        @Volatile
        var appContext: Context? = null

        private val YEAR_PATTERN = Pattern.compile("""(19\d{2}|20\d{2})""")
        private val NUMBER_PATTERN = Pattern.compile("""(\d+)""")
        private val LINK_HPRO_PATTERN = Pattern.compile("""var\s+linkHPro\s*=\s*["']([^"']+)["']""")
        private val LINK_SPRO_PATTERN = Pattern.compile("""var\s+linkSPro\s*=\s*["']([^"']+)["']""")
        private val STREAM_BOOTSTRAP_PATTERN = Pattern.compile(
            """<script\s+id="stream-bootstrap"[^>]*>(.*?)</script>""",
            Pattern.DOTALL
        )
        private val DATA_OBF_PATTERN = Pattern.compile("""data-obf="([^"]+)"""")
        private val M3U8_SCAN_PATTERN = Pattern.compile("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
        private val MP4_SCAN_PATTERN = Pattern.compile("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
        private val IFRAME_SRC_PATTERN = Pattern.compile("""<iframe[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE)
        private val VSM_SIGNED_PATTERN = Pattern.compile("""signedMasterUrl:\s*["']([^"']*)["']""")
        private val VSM_BASE_PATTERN = Pattern.compile("""const\s+baseUrl\s*=\s*["']([^"']+)["']""")
        private val VSM_HASH_PATTERN = Pattern.compile("""const\s+videoHash\s*=\s*["']([^"']+)["']""")
        private val PRELOAD_M3U8_PATTERN = Pattern.compile("""<link[^>]+href=["']([^"']+\.m3u8[^"']*)["']""", Pattern.CASE_INSENSITIVE)
    }

    override val mainPage = mainPageOf(
        "/phim-moi" to "Phim Mới Cập Nhật",
        "/phim-bo" to "Phim Bộ",
        "/phim-le" to "Phim Lẻ",
        "/the-loai/hanh-dong" to "Hành Động",
        "/the-loai/tinh-cam" to "Tình Cảm",
        "/the-loai/co-trang" to "Cổ Trang",
        "/the-loai/kinh-di" to "Kinh Dị",
        "/the-loai/hai-huoc" to "Hài Hước",
        "/the-loai/phieu-luu" to "Phiêu Lưu",
        "/quoc-gia/han-quoc" to "Phim Hàn Quốc",
        "/quoc-gia/trung-quoc" to "Phim Trung Quốc",
        "/quoc-gia/au-my" to "Phim Âu Mỹ"
    )

    private fun browserHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to TVHAY_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private fun absUrl(href: String): String {
        val h = href.trim()
        if (h.isEmpty()) return ""
        if (h.startsWith("http://", true) || h.startsWith("https://", true)) return h
        if (h.startsWith("//")) return "https:$h"
        val path = if (h.startsWith("/")) h else "/$h"
        return "$mainUrl$path"
    }

    private fun firstGroup(pattern: Pattern, text: String): String? {
        val m = pattern.matcher(text)
        return if (m.find()) m.group(1) else null
    }

    private fun parseYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return firstGroup(YEAR_PATTERN, text)?.toIntOrNull()
    }

    private fun parseMovieCard(item: Element): SearchResponse? {
        val a = item.selectFirst("a[href*=/phim/]") ?: item.selectFirst("a[href]") ?: return null
        val url = absUrl(a.attr("href"))
        if (!url.contains("/phim/")) return null

        val title = item.selectFirst(".movie-title")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("title").trim().ifBlank { null }
            ?: return null

        val imgEl = item.selectFirst(".movie-thumbnail img, img")
        val poster = imgEl?.attr("src")?.trim()?.takeIf {
            it.startsWith("http", true) && !it.contains("placehold.co")
        }

        val epStatus = item.selectFirst(".movie-ep-status")?.text()?.trim().orEmpty()
        val yearText = item.selectFirst(".movie-meta-short span")?.text()?.trim()
        val year = parseYear(yearText)

        val isSeries = epStatus.contains("Tập", ignoreCase = true) ||
            (epStatus.contains("/") && !epStatus.equals("Full", ignoreCase = true))
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, url, tvType) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun parseCards(container: Element?): List<SearchResponse> {
        if (container == null) return emptyList()
        val list = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (card in container.select(".movie-item")) {
            val sr = parseMovieCard(card) ?: continue
            if (seen.add(sr.url)) {
                list.add(sr)
            }
        }
        return list
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val basePath = request.data.trim()
        val sep = if (basePath.contains("?")) "&" else "?"
        val url = if (page <= 1) "$mainUrl$basePath" else "$mainUrl$basePath${sep}page=$page"

        return try {
            val doc = app.get(url, headers = browserHeaders(), timeout = 20L).document
            val list = parseCards(doc)
            val pag = doc.selectFirst(".pagination")
            val hasNext = pag?.selectFirst("a[href*=page=${page + 1}]") != null ||
                pag?.selectFirst("a:contains(»)") != null
            ClipboardLogger.logAndCopy("getMainPage", "${request.name} (p=$page) -> ${list.size} phim, hasNext=$hasNext")
            newHomePageResponse(request.name, list, hasNext = hasNext)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("getMainPage Error", "${request.name} p=$page: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        try {
            val doc = app.get(
                "$mainUrl/?view=tim-kiem&keyword=$encoded",
                headers = browserHeaders(),
                timeout = 20L
            ).document
            for (sr in parseCards(doc)) {
                if (seen.add(sr.url)) out.add(sr)
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Search HTML Error", "$q: ${e.message}")
        }

        // Bổ sung / dự phòng từ endpoint AJAX JSON nếu cần
        try {
            val ajaxText = app.get(
                "$mainUrl/index.php?view=ajax-search&keyword=$encoded",
                headers = browserHeaders("$mainUrl/") + mapOf("Accept" to "application/json"),
                timeout = 15L
            ).text
            if (ajaxText.trimStart().startsWith("[")) {
                val arr = JSONArray(ajaxText)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val slug = obj.optString("slug").trim()
                    val name = obj.optString("name").trim()
                    if (slug.isEmpty() || name.isEmpty()) continue
                    val filmUrl = "$mainUrl/phim/$slug"
                    if (!seen.add(filmUrl)) continue
                    val thumb = obj.optString("thumb").trim().takeIf { it.startsWith("http", true) }
                    val year = parseYear(obj.optString("year"))
                    out.add(
                        newMovieSearchResponse(name, filmUrl, TvType.Movie) {
                            this.posterUrl = thumb
                            this.year = year
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Search AJAX Error", "$q: ${e.message}")
        }

        ClipboardLogger.logAndCopy("Search", "'$q' -> ${out.size} kết quả")
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val filmUrl = absUrl(url)
        return try {
            val doc = app.get(filmUrl, headers = browserHeaders(), timeout = 20L).document
            val title = doc.selectFirst("h1.movie-name-vi")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null

            val poster = doc.selectFirst(".poster-img-box img")?.attr("src")?.trim()?.takeIf {
                it.startsWith("http", true) && !it.contains("placehold.co")
            } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()

            // .movie-description thường chứa HTML bị escape (&lt;p&gt;...&lt;/p&gt;)
            val descHtml = doc.selectFirst(".movie-description")?.html().orEmpty()
            val descStage1 = Jsoup.parse(descHtml).text()
            val plot = Jsoup.parse(descStage1).text().trim().ifBlank {
                doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            }

            var statusText = ""
            var year: Int? = null
            var durationMins: Int? = null
            val tags = mutableListOf<String>()

            for (li in doc.select(".meta-list li")) {
                val label = li.text().lowercase()
                val valText = li.selectFirst(".meta-value")?.text()?.trim().orEmpty()
                when {
                    label.contains("tình trạng") -> statusText = valText
                    label.contains("năm sản xuất") -> year = parseYear(valText)
                    label.contains("thời lượng") -> durationMins = firstGroup(NUMBER_PATTERN, valText)?.toIntOrNull()
                    label.contains("thể loại") -> {
                        valText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.add(it) }
                    }
                }
            }
            if (year == null) {
                year = parseYear(doc.selectFirst("p.movie-name-en")?.text())
            }

            val recommendations = parseCards(doc.selectFirst(".related-block"))

            val firstWatchUrl = doc.selectFirst("a.btn-watch")?.attr("href")?.let { absUrl(it) }
                ?: doc.selectFirst(".latest-eps-container a")?.attr("href")?.let { absUrl(it) }
                ?: doc.selectFirst("a[href*=/xem/]")?.attr("href")?.let { absUrl(it) }

            // Lấy danh sách đầy đủ các server & tất cả các tập từ trang xem phim
            val groupedEpisodes = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            val epLabels = HashMap<String, String>()
            val epNumbers = HashMap<String, Int?>()

            if (!firstWatchUrl.isNullOrBlank()) {
                try {
                    val watchDoc = app.get(firstWatchUrl, headers = browserHeaders(filmUrl), timeout = 20L).document
                    val containers = watchDoc.select(".episode-block .source-container")
                    for ((svIdx, container) in containers.withIndex()) {
                        val svTitle = container.selectFirst(".source-title")?.text()?.trim()
                            ?.ifBlank { "Server ${svIdx + 1}" } ?: "Server ${svIdx + 1}"
                        for (epEl in container.select(".ep-grid a.ep-btn")) {
                            val epHref = absUrl(epEl.attr("href"))
                            if (epHref.isEmpty()) continue
                            val rawLabel = epEl.text().trim().ifBlank { "Full" }
                            val epNum = firstGroup(NUMBER_PATTERN, rawLabel)?.toIntOrNull()
                                ?: firstGroup(Pattern.compile("""tap-(\d+)""", Pattern.CASE_INSENSITIVE), epHref)?.toIntOrNull()

                            val key = if (epNum != null) "ep_$epNum" else "raw_${rawLabel.lowercase()}"
                            var list = groupedEpisodes[key]
                            if (list == null) {
                                list = mutableListOf()
                                groupedEpisodes[key] = list
                            }
                            val entry = svTitle to epHref
                            if (!list.contains(entry)) {
                                list.add(entry)
                            }
                            if (!epLabels.containsKey(key)) {
                                epLabels[key] = rawLabel
                                epNumbers[key] = epNum
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("Watch Page Parse Error", "$firstWatchUrl -> ${e.message}")
                }
            }

            if (groupedEpisodes.isEmpty() && !firstWatchUrl.isNullOrBlank()) {
                val key = "ep_fallback"
                groupedEpisodes[key] = mutableListOf("Server 1" to firstWatchUrl)
                epLabels[key] = if (statusText.contains("full", true)) "Full" else "Tập 1"
                epNumbers[key] = 1
            }

            val isSingleMovie = groupedEpisodes.size <= 1 && (
                statusText.contains("full", ignoreCase = true) ||
                    (firstWatchUrl?.contains("/full", ignoreCase = true) == true) ||
                    (firstWatchUrl?.contains("/tap-full", ignoreCase = true) == true)
                )

            if (isSingleMovie) {
                val firstPairs = groupedEpisodes.values.firstOrNull().orEmpty()
                val movieData = firstPairs.joinToString(ENTRY_SEP) { "${it.first}$PAIR_SEP${it.second}" }
                ClipboardLogger.logAndCopy("load", "Movie: $title ($year) | servers=${firstPairs.size}")
                newMovieLoadResponse(title, filmUrl, TvType.Movie, movieData) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.duration = durationMins
                    if (tags.isNotEmpty()) this.tags = tags
                    if (recommendations.isNotEmpty()) this.recommendations = recommendations
                }
            } else {
                val episodes = mutableListOf<Episode>()
                var seqNum = 1
                for ((key, serverPairs) in groupedEpisodes) {
                    val epNum = epNumbers[key] ?: seqNum
                    val rawLabel = epLabels[key] ?: "Tập $epNum"
                    val displayName = if (rawLabel.matches(Regex("""^\d+$"""))) {
                        "Tập $rawLabel"
                    } else {
                        rawLabel
                    }
                    val epData = serverPairs.joinToString(ENTRY_SEP) { "${it.first}$PAIR_SEP${it.second}" }
                    episodes.add(
                        newEpisode(epData) {
                            this.name = displayName
                            this.episode = epNum
                            this.season = 1
                        }
                    )
                    seqNum++
                }
                ClipboardLogger.logAndCopy("load", "Series: $title ($year) | episodes=${episodes.size}")
                newTvSeriesLoadResponse(title, filmUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.duration = durationMins
                    if (tags.isNotEmpty()) this.tags = tags
                    if (recommendations.isNotEmpty()) this.recommendations = recommendations
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("load Error", "$url -> ${e.message}")
            null
        }
    }

    // =========================================================================
    // TRÍCH XUẤT LUỒNG PHÁT (PhimAPI / StreamVSMov / StreamC / Generic)
    // =========================================================================

    private class StreamBundle(
        val playlistText: String,
        val baseUrl: String,
        val hashes: List<String>,
        val via: String,
        val stripPng: Boolean
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks Start", "Data: $data")

        val serverWatchList = data.split(ENTRY_SEP)
            .mapNotNull { token ->
                val t = token.trim()
                if (t.isEmpty()) return@mapNotNull null
                val idx = t.indexOf(PAIR_SEP)
                if (idx > 0) {
                    t.substring(0, idx).trim() to absUrl(t.substring(idx + PAIR_SEP.length))
                } else {
                    "Server" to absUrl(t)
                }
            }
            .distinctBy { it.second }

        if (serverWatchList.isEmpty()) {
            ClipboardLogger.logAndCopy("LoadLinks Error", "Không có URL trang xem phim trong data")
            return false
        }

        val emittedUrls = Collections.synchronizedSet(HashSet<String>())
        val foundAny = AtomicBoolean(false)

        serverWatchList.amap { (serverLabel, watchUrl) ->
            try {
                ClipboardLogger.logAndCopy("Watch Fetch", "[$serverLabel] $watchUrl")
                val watchHtml = app.get(watchUrl, headers = browserHeaders(mainUrl), timeout = 20L).text
                val doc = Jsoup.parse(watchHtml, watchUrl)

                val candidatePlayers = mutableListOf<String>()
                firstGroup(LINK_HPRO_PATTERN, watchHtml)?.let { candidatePlayers.add(absUrl(it)) }
                firstGroup(LINK_SPRO_PATTERN, watchHtml)?.let { candidatePlayers.add(absUrl(it)) }
                doc.selectFirst("iframe#movie-iframe")?.attr("src")?.takeIf { it.isNotBlank() }?.let {
                    candidatePlayers.add(absUrl(it))
                }

                // Khử trùng lặp nếu linkHPro & linkSPro trỏ về cùng 1 URL gốc hoặc cùng ?url=...m3u8
                val uniqueTargets = linkedMapOf<String, String>()
                for (playerUrl in candidatePlayers) {
                    val directM3u8 = extractQueryUrlParam(playerUrl)
                    val dedupKey = directM3u8 ?: playerUrl
                    if (!uniqueTargets.containsKey(dedupKey)) {
                        uniqueTargets[dedupKey] = playerUrl
                    }
                }

                if (uniqueTargets.isEmpty()) {
                    ClipboardLogger.logAndCopy("Watch Empty", "[$serverLabel] Không tìm thấy link player")
                    return@amap
                }

                for ((dedupKey, playerUrl) in uniqueTargets) {
                    val ok = resolvePlayerTarget(
                        serverLabel = serverLabel,
                        watchUrl = watchUrl,
                        playerUrl = playerUrl,
                        directUrlParam = if (dedupKey != playerUrl) dedupKey else null,
                        emittedUrls = emittedUrls,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                    if (ok) foundAny.set(true)
                }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Watch Error", "[$serverLabel] ${e.message}")
            }
        }

        ClipboardLogger.logAndCopy("LoadLinks Done", "Success=${foundAny.get()}, streams=${emittedUrls.size}")
        return foundAny.get()
    }

    private fun extractQueryUrlParam(playerUrl: String): String? {
        if (!playerUrl.contains("url=", ignoreCase = true)) return null
        val qPart = playerUrl.substringAfter('?', "")
        for (pair in qPart.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq)
            if (k.equals("url", ignoreCase = true)) {
                val decoded = runCatching {
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                }.getOrDefault(pair.substring(eq + 1)).trim()
                if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                    return decoded
                }
            }
        }
        return null
    }

    private suspend fun resolvePlayerTarget(
        serverLabel: String,
        watchUrl: String,
        playerUrl: String,
        directUrlParam: String?,
        emittedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. PhimAPI / /player.php?url=https://.../index.m3u8
        if (!directUrlParam.isNullOrBlank()) {
            val isM3u8 = !directUrlParam.contains(".mp4", ignoreCase = true) ||
                directUrlParam.contains(".m3u8", ignoreCase = true)
            if (emittedUrls.add(directUrlParam)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - $serverLabel",
                        url = directUrlParam,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to TVHAY_UA, "Referer" to "$mainUrl/")
                    }
                )
                ClipboardLogger.logAndCopy("Stream Added", "$serverLabel (PhimAPI) -> $directUrlParam")
                return true
            }
            return false
        }

        // 2. StreamVSMov (-vsm): v*.streamvsmov.com/video/<uuid>
        if (playerUrl.contains("streamvsmov", ignoreCase = true)) {
            ClipboardLogger.logAndCopy("StreamVSMov", "[$serverLabel] Fetching $playerUrl")
            val vsmBundle = extractStreamVsmov(playerUrl)
            if (vsmBundle != null) {
                val proxyUrl = TvHayLocalProxy.registerPlaylist(
                    vsmBundle.playlistText,
                    vsmBundle.baseUrl,
                    playerUrl,
                    vsmBundle.hashes,
                    stripPng = vsmBundle.stripPng
                )
                if (emittedUrls.add(proxyUrl)) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - $serverLabel (VSM)",
                            url = proxyUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    ClipboardLogger.logAndCopy("Stream Added", "$serverLabel (StreamVSMov/${vsmBundle.via}) -> ${vsmBundle.baseUrl}")
                    return true
                }
            }
            ClipboardLogger.logAndCopy("StreamVSMov Fail", "[$serverLabel] Không lấy được master.m3u8 từ $playerUrl")
            return false
        }

        // 3. StreamC / NguonC (-nc): embed*.streamc.xyz/embed.php?hash=...
        if (playerUrl.contains("streamc", ignoreCase = true) || playerUrl.contains("embed.php?hash=", ignoreCase = true)) {
            ClipboardLogger.logAndCopy("StreamC", "[$serverLabel] Extracting $playerUrl")
            for (tier in listOf("bootstrap", "data-obf", "scan", "webview")) {
                val st = runCatching {
                    when (tier) {
                        "bootstrap" -> viaStreamCBootstrap(playerUrl)
                        "data-obf" -> viaStreamCDataObf(playerUrl)
                        "scan" -> viaScan(playerUrl)
                        else -> viaWebView(playerUrl)
                    }
                }.onFailure {
                    if (it is java.util.concurrent.CancellationException) throw it
                    ClipboardLogger.logAndCopy("StreamC $tier Err", "${it.javaClass.simpleName}: ${it.message?.take(120)}")
                }.getOrNull()

                if (st != null) {
                    val proxyUrl = TvHayLocalProxy.registerPlaylist(
                        st.playlistText,
                        st.baseUrl,
                        playerUrl,
                        st.hashes,
                        stripPng = st.stripPng
                    )
                    if (emittedUrls.add(proxyUrl)) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - $serverLabel (NC)",
                                url = proxyUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                        ClipboardLogger.logAndCopy("Stream Added", "$serverLabel (StreamC/${st.via})")
                        return true
                    }
                }
            }
            ClipboardLogger.logAndCopy("StreamC Fail", "[$serverLabel] Tất cả các tầng đều thất bại")
            return false
        }

        // 4. Generic direct stream / iframe scan / CloudStream loadExtractor fallback
        if (playerUrl.contains(".m3u8", ignoreCase = true) || playerUrl.contains(".mp4", ignoreCase = true)) {
            val isHls = playerUrl.contains(".m3u8", ignoreCase = true)
            if (emittedUrls.add(playerUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - $serverLabel",
                        url = playerUrl,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                ClipboardLogger.logAndCopy("Stream Added", "$serverLabel (Direct) -> $playerUrl")
                return true
            }
            return false
        }

        val scanned = viaScan(playerUrl)
        if (scanned != null) {
            val proxyUrl = TvHayLocalProxy.registerPlaylist(
                scanned.playlistText,
                scanned.baseUrl,
                playerUrl,
                scanned.hashes,
                stripPng = scanned.stripPng
            )
            if (emittedUrls.add(proxyUrl)) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - $serverLabel",
                        url = proxyUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playerUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                ClipboardLogger.logAndCopy("Stream Added", "$serverLabel (Scan)")
                return true
            }
        }

        return runCatching {
            loadExtractor(playerUrl, watchUrl, subtitleCallback, callback)
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // StreamVSMov (-vsm) Extractor
    // -------------------------------------------------------------------------
    private fun extractStreamVsmov(embedUrl: String): StreamBundle? {
        val (code, html) = TvHayLocalProxy.fetchTextWithHttp2(embedUrl, "$mainUrl/") ?: return null
        if (code !in 200..299 || html.isBlank()) return null

        val signed = firstGroup(VSM_SIGNED_PATTERN, html)?.trim()?.takeIf { it.startsWith("http", true) }
        val baseUrl = firstGroup(VSM_BASE_PATTERN, html)?.trim()?.trimEnd('/')
            ?: TvHayCrypto.originOf(embedUrl)
        val videoHash = firstGroup(VSM_HASH_PATTERN, html)?.trim()
            ?: embedUrl.substringBefore('?').substringBefore('#').substringAfterLast('/').trim()
        val preloaded = firstGroup(PRELOAD_M3U8_PATTERN, html)?.trim()

        val candidates = listOfNotNull(
            signed,
            if (baseUrl.isNotBlank() && videoHash.isNotBlank()) "$baseUrl/stream/$videoHash/master.m3u8" else null,
            preloaded?.let { TvHayCrypto.resolveUrl(embedUrl, it) }
        ).distinct()

        for (m3u8Url in candidates) {
            val (mCode, mBody) = TvHayLocalProxy.fetchTextWithHttp2(m3u8Url, embedUrl) ?: continue
            if (mCode in 200..299 && TvHayCrypto.looksLikePlaylist(mBody)) {
                val hasPngSeg = mBody.contains(".png", ignoreCase = true)
                return StreamBundle(
                    playlistText = mBody,
                    baseUrl = m3u8Url,
                    hashes = emptyList(),
                    via = if (hasPngSeg) "vsm-png" else "vsm-hls",
                    stripPng = hasPngSeg
                )
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // StreamC (-nc) Multi-Tier Extractor (bootstrap -> data-obf -> scan -> webview)
    // -------------------------------------------------------------------------
    private fun directPlaylist(
        url: String,
        embed: String,
        via: String,
        hashes: List<String> = emptyList()
    ): StreamBundle? {
        val (code, body) = TvHayLocalProxy.fetchTextWithHttp2(url, embed) ?: return null
        if (code !in 200..299 || body.isBlank()) return null

        val tryHashes = (hashes + TvHayCrypto.hashOf(embed)).filter { it.isNotBlank() }.distinct()
        val resolvedText = if (body.contains("#ENC-AESGCM") || body.contains("#EXT-X-B65")) {
            var decoded: String? = null
            for (h in tryHashes) {
                val plain = runCatching { TvHayCrypto.decryptPlaylist(body, h) }.getOrNull()
                if (TvHayCrypto.looksLikePlaylist(plain)) {
                    decoded = plain
                    break
                }
            }
            decoded ?: return null
        } else {
            if (!TvHayCrypto.looksLikePlaylist(body)) return null
            body
        }

        if (!TvHayLocalProxy.probeFirstSegment(resolvedText, url, embed)) {
            ClipboardLogger.logAndCopy("StreamC Probe Fail", "$via: segment đầu tiên không hợp lệ")
            return null
        }
        return StreamBundle(resolvedText, url, tryHashes, via, stripPng = true)
    }

    private fun viaStreamCBootstrap(embed: String): StreamBundle? {
        val origin = TvHayCrypto.originOf(embed).takeIf { it.isNotBlank() } ?: return null
        val (shellCode, shellHtml) = TvHayLocalProxy.fetchTextWithHttp2(embed, "$mainUrl/") ?: return null
        if (shellCode !in 200..299) return null

        val cfg = firstGroup(STREAM_BOOTSTRAP_PATTERN, shellHtml) ?: return null
        val api = JSONObject(cfg).optString("api").takeIf { it.isNotBlank() } ?: return null

        for (pFmt in listOf("hls", "aesgcm-v2")) {
            val body = JSONObject().apply {
                put("action", "bootstrap")
                put("referrer", "$mainUrl/")
                put("frame_origins", JSONArray().put(mainUrl))
                put("request_grant", true)
                put("playlist_format", pFmt)
                put("pretty_url", true)
                put("path_chunks", true)
                put("bootstrap_format", "aesgcm-v1")
            }.toString()

            val (postCode, postText) = TvHayLocalProxy.postJsonWithHttp2(api, body, embed) ?: continue
            if (postCode !in 200..299 || postText.isBlank()) continue

            var j = runCatching { JSONObject(postText) }.getOrNull() ?: continue
            if (j.optString("format") == "aesgcm-v1") {
                val aad = "stream-bootstrap-v1\n$api".toByteArray()
                val plain = TvHayCrypto.gcmDecrypt(
                    TvHayCrypto.sha256(aad),
                    TvHayCrypto.hexToBytes(j.getString("iv")),
                    Base64.decode(j.getString("data"), Base64.DEFAULT),
                    aad
                )
                j = JSONObject(String(plain, Charsets.UTF_8))
            }

            val video = j.optString("video").ifBlank { TvHayCrypto.hashOf(embed) }
            var playlist = j.optJSONObject("preissued")?.optString("playlist")?.takeIf { it.isNotBlank() }

            if (playlist == null && !j.optBoolean("turnstileEnabled", false)) {
                val bootToken = j.optString("bootstrap").takeIf { it.isNotBlank() }
                if (bootToken != null) {
                    val issueBody = JSONObject().apply {
                        put("action", "issue")
                        put("bootstrap", bootToken)
                        put("turnstile_response", "")
                        put("frame_origins", JSONArray().put(mainUrl))
                        put("playlist_format", pFmt)
                        put("pretty_url", true)
                        put("path_chunks", true)
                    }.toString()
                    val (iCode, iText) = TvHayLocalProxy.postJsonWithHttp2(api, issueBody, embed) ?: (0 to "")
                    if (iCode in 200..299 && iText.isNotBlank()) {
                        playlist = runCatching { JSONObject(iText).optString("playlist") }
                            .getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
            }

            if (playlist != null) {
                directPlaylist(playlist, embed, "bootstrap-$pFmt", listOf(video))?.let { return it }
            }

            if (j.optBoolean("turnstileEnabled", false)) {
                ClipboardLogger.logAndCopy("StreamC Turnstile", "$origin bật Turnstile -> chuyển WebView")
                return null
            }
        }
        return null
    }

    private fun viaStreamCDataObf(embed: String): StreamBundle? {
        val origin = TvHayCrypto.originOf(embed).takeIf { it.isNotBlank() } ?: return null
        val (code, html) = TvHayLocalProxy.fetchTextWithHttp2(embed, "$mainUrl/") ?: return null
        if (code !in 200..299) return null
        val obf = firstGroup(DATA_OBF_PATTERN, html) ?: return null
        val j = JSONObject(String(Base64.decode(obf, Base64.DEFAULT)))
        val sub = j.optString("sUb").takeIf { it.isNotBlank() } ?: return null
        return directPlaylist("$origin/$sub.m3u8", embed, "data-obf", listOf(j.optString("hD")))
    }

    private fun viaScan(embed: String): StreamBundle? {
        val seen = HashSet<String>()
        fun scan(url: String, depth: Int): StreamBundle? {
            if (!seen.add(url) || depth > 1) return null
            val (_, htmlRaw) = TvHayLocalProxy.fetchTextWithHttp2(url, "$mainUrl/") ?: return null
            val html = htmlRaw.replace("\\/", "/")

            val m3u8Matcher = M3U8_SCAN_PATTERN.matcher(html)
            while (m3u8Matcher.find()) {
                val m3u8 = m3u8Matcher.group(0) ?: continue
                directPlaylist(m3u8, embed, "scan", listOf(TvHayCrypto.hashOf(embed)))?.let { return it }
            }

            val iframeMatcher = IFRAME_SRC_PATTERN.matcher(html)
            while (iframeMatcher.find()) {
                val rawSrc = iframeMatcher.group(1) ?: continue
                val src = TvHayCrypto.resolveUrl(url, rawSrc)
                if (src.startsWith("http")) scan(src, depth + 1)?.let { return it }
            }
            return null
        }
        return scan(embed, 0)
    }

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        CommonActivity.activity?.let { return it.applicationContext ?: it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
    }

    private val adUrlPatterns = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "google-analytics.com", "popads.net",
        "propellerads.com", "popcash.net", "adsterra.com", "adcash.com",
        "/ads.js", "vast.js"
    )

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun viaWebView(embed: String): StreamBundle? {
        val signedPlaylistRegex = Pattern.compile(
            """\.m3u8(\?|$|#)|/conf\.php\?t=|^https?://[^/]*streamc[^/]*/(?:[A-Za-z0-9_-]{180}/){0,30}[A-Za-z0-9_-]{80,}(?:\?d)?$""",
            Pattern.CASE_INSENSITIVE
        )
        val assetSkipRegex = Pattern.compile(
            """\.(js|css|woff2?|ttf|svg|ico|png|jpe?g|webp|gif)(\?|$)|/assets/""",
            Pattern.CASE_INSENSITIVE
        )

        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val act = CommonActivity.activity
                val ctx = act ?: resolveContext()
                if (ctx == null) {
                    cont.resume(null)
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    val finished = AtomicBoolean(false)
                    val probedUrls = Collections.synchronizedSet(HashSet<String>())
                    val jsVideoHash = AtomicReference(TvHayCrypto.hashOf(embed))

                    fun finish(res: StreamBundle?) {
                        if (!finished.compareAndSet(false, true)) return
                        handler.removeCallbacksAndMessages(null)
                        runCatching {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        }
                        cont.resume(res)
                    }

                    val hookJs = """
                        (function(){
                            if (window.__tvhayHooked) return;
                            window.__tvhayHooked = true;
                            try { window.open = function(){ return null; }; } catch(e){}
                            try {
                                var origDecode = TextDecoder.prototype.decode;
                                TextDecoder.prototype.decode = function(input, opts){
                                    var out = origDecode.apply(this, arguments);
                                    try {
                                        if (typeof out === 'string' && out.indexOf('#EXTM3U') === 0 && out.length > 30) {
                                            if (!window.__tvhaySent && window.TvHayBridge) {
                                                window.__tvhaySent = true;
                                                window.TvHayBridge.onPlaylistText(out, String(window.videoHash || ''), String(location.href));
                                            }
                                        }
                                    } catch(ex){}
                                    return out;
                                };
                            } catch(e){}
                            try {
                                var origText = Response.prototype.text;
                                Response.prototype.text = function(){
                                    var respUrl = this.url || '';
                                    return origText.apply(this, arguments).then(function(txt){
                                        try {
                                            if (typeof txt === 'string' && txt.indexOf('#EXTM3U') === 0 &&
                                                txt.indexOf('#ENC-AESGCM') === -1 && txt.indexOf('#EXT-X-B65') === -1 &&
                                                !window.__tvhaySent && window.TvHayBridge) {
                                                window.__tvhaySent = true;
                                                window.TvHayBridge.onPlaylistText(txt, String(window.videoHash || ''), String(respUrl || location.href));
                                            }
                                        } catch(ex){}
                                        return txt;
                                    });
                                };
                            } catch(e){}
                        })();
                    """.trimIndent()

                    val autoPlayJs = """
                        (function(){
                            try {
                                if (window.TvHayBridge && window.videoHash) {
                                    window.TvHayBridge.onVideoHash(String(window.videoHash));
                                }
                                var btn = document.querySelector('.jw-display-icon-container, .jw-icon-playback, button[aria-label*="Phát"], button[aria-label*="Play"]');
                                if (btn) btn.click();
                                var v = document.querySelector('video');
                                if (v && v.paused) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                }
                            } catch(e){}
                        })();
                    """.trimIndent()

                    val bridge = object {
                        @JavascriptInterface
                        fun onVideoHash(vh: String?) {
                            if (!vh.isNullOrBlank()) jsVideoHash.set(vh.trim())
                        }

                        @JavascriptInterface
                        fun onPlaylistText(text: String?, vh: String?, baseUrl: String?) {
                            if (finished.get() || text.isNullOrBlank()) return
                            if (!vh.isNullOrBlank()) jsVideoHash.set(vh.trim())
                            if (!TvHayCrypto.looksLikePlaylist(text)) return
                            if (text.contains("#ENC-AESGCM") || text.contains("#EXT-X-B65")) return
                            val resolvedBase = baseUrl?.takeIf { it.startsWith("http") } ?: embed
                            Thread {
                                if (finished.get()) return@Thread
                                if (TvHayLocalProxy.probeFirstSegment(text, resolvedBase, embed)) {
                                    val st = StreamBundle(
                                        playlistText = text,
                                        baseUrl = resolvedBase,
                                        hashes = listOf(jsVideoHash.get(), TvHayCrypto.hashOf(embed))
                                            .filter { it.isNotBlank() }.distinct(),
                                        via = "webview-js",
                                        stripPng = true
                                    )
                                    handler.post { finish(st) }
                                }
                            }.apply { isDaemon = true }.start()
                        }
                    }

                    wv.addJavascriptInterface(bridge, "TvHayBridge")

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = TVHAY_UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false
                    }

                    var taps = 0
                    fun tapLoop() {
                        if (finished.get()) return
                        taps++
                        runCatching {
                            wv.evaluateJavascript(hookJs, null)
                            wv.evaluateJavascript(autoPlayJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        }
                        if (taps < 12) handler.postDelayed({ tapLoop() }, 1600)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            runCatching { wv.evaluateJavascript(hookJs, null) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            runCatching {
                                wv.evaluateJavascript(hookJs, null)
                                wv.evaluateJavascript(autoPlayJs, null)
                            }
                            handler.postDelayed({ tapLoop() }, 1500)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (finished.get()) return null
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http", ignoreCase = true)) return null

                            if (adUrlPatterns.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!assetSkipRegex.matcher(u).find() &&
                                signedPlaylistRegex.matcher(u).find() &&
                                probedUrls.add(u)
                            ) {
                                Thread {
                                    if (finished.get()) return@Thread
                                    val hashes = listOf(jsVideoHash.get(), TvHayCrypto.hashOf(embed))
                                        .filter { it.isNotBlank() }.distinct()
                                    val st = directPlaylist(u, embed, "webview-net", hashes)
                                    if (st != null && !finished.get()) {
                                        handler.post { finish(st) }
                                    }
                                }.apply { isDaemon = true }.start()
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (adUrlPatterns.any { u.contains(it, ignoreCase = true) }) return true
                            if (request.isForMainFrame) {
                                val allow = u.contains("streamc", ignoreCase = true) ||
                                    u.contains("challenges.cloudflare.com", ignoreCase = true) ||
                                    u.contains("nguonc", ignoreCase = true)
                                return !allow
                            }
                            return false
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    runCatching {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    }

                    handler.postDelayed({ finish(null) }, 24_000L)
                    wv.loadUrl(embed, mapOf("Referer" to "$mainUrl/"))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("WebView Error", e.message ?: "Unknown")
                    cont.resume(null)
                }
            }
        }
    }
}

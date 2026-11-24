package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val VIDROCK_BASE_URL = "https://vidrock.net"
private const val VIDROCK_SUB_URL = "https://sub.vdrk.site"
private const val VIDROCK_AES_KEY_HEX =
    "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

private data class VnMediaRef(
    val type: String,
    val id: Int,
    val season: Int? = null,
    val episode: Int? = null
)

private data class VnDecryptedSource(
    val serverName: String,
    val url: String,
    val language: String,
    val flag: String,
    val type: String
)

private val VN_REF_REGEX =
    Regex("""^vidnet:(movie|tv):(\d+)(?::(\d+):(\d+))?$""")

private val VN_PATH_REGEX =
    Regex("""^/(?:embed/)?(movie|tv)/(\d+)(?:/(\d+)/(\d+))?/?$""")

private val VN_LEGACY_PATH_REGEX =
    Regex("""^/vidnet:(movie|tv):(\d+)(?::(\d+):(\d+))?/?$""")

private fun rethrowIfCancellation(t: Throwable) {
    var cur: Throwable? = t
    while (cur != null) {
        if (cur.javaClass.name.endsWith("CancellationException")) {
            throw t
        }
        cur = cur.cause
    }
}

private fun parseVnRef(value: String): VnMediaRef? {
    val input = value.trim()
    val path = try {
        URI(input).path ?: input
    } catch (_: Exception) {
        input
    }

    val match = VN_REF_REGEX.matchEntire(input)
        ?: VN_PATH_REGEX.matchEntire(path)
        ?: VN_LEGACY_PATH_REGEX.matchEntire(path)
        ?: return null

    return VnMediaRef(
        type = match.groupValues[1],
        id = match.groupValues[2].toIntOrNull() ?: return null,
        season = match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull(),
        episode = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull()
    )
}

private fun vnDetailUrl(type: String, id: Int): String {
    return if (type == "movie") {
        "$VIDROCK_BASE_URL/movie/$id"
    } else {
        "$VIDROCK_BASE_URL/tv/$id/1/1"
    }
}

private fun resolveHttpUrl(baseUrl: String, raw: String?): String? {
    val value = raw
        ?.trim()
        ?.replace("&amp;", "&")
        ?.replace("\\u0026", "&")
        ?.replace("\\/", "/")
        ?.replace(" ", "%20")
        ?.takeIf { it.isNotBlank() }
        ?: return null

    if (value.startsWith("javascript:", ignoreCase = true) ||
        value.startsWith("data:", ignoreCase = true) ||
        value.startsWith("blob:", ignoreCase = true)
    ) return null

    return try {
        val resolved = URI(baseUrl).resolve(value).toString()
        resolved.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    } catch (_: Exception) {
        null
    }
}

private fun hexToBytes(hex: String): ByteArray {
    val len = hex.length / 2
    val out = ByteArray(len)
    for (i in 0 until len) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private object VidrockCrypto {
    private val secretKey by lazy {
        SecretKeySpec(hexToBytes(VIDROCK_AES_KEY_HEX), "AES")
    }

    fun decryptUrl(cipherB64Url: String): String? {
        val cleaned = cipherB64Url.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) return cleaned

        return runCatching {
            val raw = Base64.decode(
                cleaned,
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            if (raw.size < 28) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, raw, 0, 12)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plain = cipher.doFinal(raw, 12, raw.size - 12)
            String(plain, Charsets.UTF_8).trim()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }
}

/**
 * Local loopback HLS + PNG-unwrapping proxy for Vidrock nodes:
 * - Preserves required `Referer: https://vidrock.net/` and `Origin: https://vidrock.net` on every
 *   child playlist & segment request.
 * - Unwraps fake PNG-disguised MPEG-TS segments (e.g. `vercetti.org` `tsDa` PNG chunk / 64-byte PNG
 *   prefix + `IEND` trailer) into clean `video/mp2t` MPEG-TS streams so Android ExoPlayer plays
 *   every segment without `UnrecognizedInputFormatException`.
 */
private object VidnetHlsProxy {
    data class RouteEntry(
        val targetUrl: String,
        val isPlaylist: Boolean,
        val unwrapPngTs: Boolean,
        val referer: String,
        val origin: String,
        val userAgent: String
    )

    private var serverSocket: ServerSocket? = null
    private var port: Int = -1
    private val seq = AtomicInteger(0)
    private val entries = ConcurrentHashMap<String, RouteEntry>()
    private val keyToId = ConcurrentHashMap<String, String>()

    @Synchronized
    fun ensureStarted(): Int {
        if (port > 0 && serverSocket?.isClosed == false) return port
        return runCatching {
            val ss = ServerSocket(0, 48, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            port = ss.localPort
            Thread {
                while (!ss.isClosed) {
                    try {
                        val client = ss.accept()
                        Thread { handleClient(client) }.apply { isDaemon = true }.start()
                    } catch (_: Exception) {
                        if (ss.isClosed) break
                    }
                }
            }.apply { isDaemon = true }.start()
            port
        }.getOrDefault(-1)
    }

    fun registerPlaylist(
        targetUrl: String,
        unwrapPngTs: Boolean,
        referer: String,
        origin: String,
        userAgent: String
    ): String? {
        val p = ensureStarted()
        if (p <= 0) return null
        val key = "pl|$unwrapPngTs|$targetUrl"
        keyToId[key]?.let { existingId ->
            if (entries.containsKey(existingId)) {
                return "http://127.0.0.1:$p/vn_pl/$existingId.m3u8"
            }
        }
        if (entries.size > 12_000) {
            entries.clear()
            keyToId.clear()
        }
        val id = seq.incrementAndGet().toString()
        entries[id] = RouteEntry(
            targetUrl = targetUrl,
            isPlaylist = true,
            unwrapPngTs = unwrapPngTs,
            referer = referer,
            origin = origin,
            userAgent = userAgent
        )
        keyToId[key] = id
        return "http://127.0.0.1:$p/vn_pl/$id.m3u8"
    }

    fun registerSegment(
        targetUrl: String,
        unwrapPngTs: Boolean,
        referer: String,
        origin: String,
        userAgent: String
    ): String? {
        val p = ensureStarted()
        if (p <= 0) return null
        val key = "seg|$unwrapPngTs|$targetUrl"
        keyToId[key]?.let { existingId ->
            if (entries.containsKey(existingId)) {
                return "http://127.0.0.1:$p/vn_seg/$existingId.ts"
            }
        }
        if (entries.size > 12_000) {
            entries.clear()
            keyToId.clear()
        }
        val id = seq.incrementAndGet().toString()
        entries[id] = RouteEntry(
            targetUrl = targetUrl,
            isPlaylist = false,
            unwrapPngTs = unwrapPngTs,
            referer = referer,
            origin = origin,
            userAgent = userAgent
        )
        keyToId[key] = id
        return "http://127.0.0.1:$p/vn_seg/$id.ts"
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val reqLine = reader.readLine() ?: return
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = reqLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1) ?: ""
            val plId = Regex("""/vn_pl/(\d+)\.m3u8""").find(path)?.groupValues?.getOrNull(1)
            val segId = Regex("""/vn_seg/(\d+)\.ts""").find(path)?.groupValues?.getOrNull(1)
            val id = plId ?: segId
            val entry = id?.let { entries[it] }

            val out = socket.getOutputStream()
            if (entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            if (entry.isPlaylist) {
                val fetched = fetchTextBlocking(entry)
                if (fetched == null) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val (effectiveUrl, rawBody) = fetched
                val rewritten = rewriteM3u8(rawBody, effectiveUrl, entry)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(bytes)
                out.flush()
            } else {
                val fetched = fetchBytesBlocking(entry)
                if (fetched == null) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val (rawBytes, contentType) = fetched
                val slice = if (entry.unwrapPngTs) unwrapPngWrappedTs(rawBytes) else (0 to rawBytes.size)
                val offset = slice.first
                val length = slice.second
                val outMime = if (entry.unwrapPngTs || contentType.contains("image/png", ignoreCase = true)) {
                    "video/mp2t"
                } else {
                    contentType.ifBlank { "video/mp2t" }
                }

                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $outMime\r\n" +
                    "Content-Length: $length\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD" && length > 0) {
                    out.write(rawBytes, offset, length)
                }
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun openUpstream(url: String, entry: RouteEntry): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", entry.userAgent)
            setRequestProperty("Accept", "*/*")
            if (entry.referer.isNotBlank()) setRequestProperty("Referer", entry.referer)
            if (entry.origin.isNotBlank()) setRequestProperty("Origin", entry.origin)
        }
    }

    private fun fetchTextBlocking(entry: RouteEntry): Pair<String, String>? {
        var current = entry.targetUrl
        for (hop in 0..3) {
            var conn: HttpURLConnection? = null
            try {
                conn = openUpstream(current, entry)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")?.trim()
                    if (!loc.isNullOrBlank()) {
                        current = resolveHttpUrl(current, loc) ?: loc
                        continue
                    }
                }
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    return current to body
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun fetchBytesBlocking(entry: RouteEntry): Pair<ByteArray, String>? {
        var current = entry.targetUrl
        for (hop in 0..3) {
            var conn: HttpURLConnection? = null
            try {
                conn = openUpstream(current, entry)
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")?.trim()
                    if (!loc.isNullOrBlank()) {
                        current = resolveHttpUrl(current, loc) ?: loc
                        continue
                    }
                }
                if (code in 200..299) {
                    val ct = conn.contentType ?: ""
                    val baos = ByteArrayOutputStream(maxOf(8192, conn.contentLength))
                    conn.inputStream.use { inp ->
                        val buf = ByteArray(16_384)
                        while (true) {
                            val r = inp.read(buf)
                            if (r <= 0) break
                            baos.write(buf, 0, r)
                        }
                    }
                    return baos.toByteArray() to ct
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun isPngHeader(buf: ByteArray, size: Int = buf.size): Boolean {
        return size >= 8 &&
            (buf[0].toInt() and 0xFF) == 0x89 &&
            (buf[1].toInt() and 0xFF) == 0x50 &&
            (buf[2].toInt() and 0xFF) == 0x4E &&
            (buf[3].toInt() and 0xFF) == 0x47 &&
            (buf[4].toInt() and 0xFF) == 0x0D &&
            (buf[5].toInt() and 0xFF) == 0x0A &&
            (buf[6].toInt() and 0xFF) == 0x1A &&
            (buf[7].toInt() and 0xFF) == 0x0A
    }

    fun findTsSyncOffset(buf: ByteArray, size: Int = buf.size): Int {
        val limit = minOf(4096, size - 565)
        if (limit < 0) return -1
        for (i in 0..limit) {
            if ((buf[i].toInt() and 0xFF) == 0x47 &&
                (buf[i + 188].toInt() and 0xFF) == 0x47 &&
                (buf[i + 376].toInt() and 0xFF) == 0x47 &&
                (buf[i + 564].toInt() and 0xFF) == 0x47
            ) {
                return i
            }
        }
        return -1
    }

    private fun unwrapPngWrappedTs(raw: ByteArray): Pair<Int, Int> {
        if (!isPngHeader(raw)) return 0 to raw.size

        // 1. Parse PNG chunks cleanly to locate custom TS chunk (e.g. `tsDa` on vercetti.org)
        var pos = 8
        while (pos + 8 <= raw.size) {
            val chunkLen = ((raw[pos].toInt() and 0xFF) shl 24) or
                ((raw[pos + 1].toInt() and 0xFF) shl 16) or
                ((raw[pos + 2].toInt() and 0xFF) shl 8) or
                (raw[pos + 3].toInt() and 0xFF)
            val chunkType = String(raw, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (chunkLen < 0 || dataStart.toLong() + chunkLen > raw.size) break

            if (chunkLen >= 188 && (
                    chunkType == "tsDa" ||
                        ((raw[dataStart].toInt() and 0xFF) == 0x47 &&
                            dataStart + 188 < raw.size &&
                            (raw[dataStart + 188].toInt() and 0xFF) == 0x47)
                    )
            ) {
                return dataStart to chunkLen
            }

            pos = dataStart + chunkLen + 4
        }

        // 2. Fallback: find 188-byte MPEG-TS sync header and trim trailing PNG IEND + CRC
        val syncOff = findTsSyncOffset(raw)
        if (syncOff >= 0) {
            var end = raw.size
            // Strip trailing 12-byte IEND + 4-byte chunk CRC if present
            if (end - syncOff >= 16 &&
                (raw[end - 8].toInt() and 0xFF) == 0x49 &&
                (raw[end - 7].toInt() and 0xFF) == 0x45 &&
                (raw[end - 6].toInt() and 0xFF) == 0x4E &&
                (raw[end - 5].toInt() and 0xFF) == 0x44
            ) {
                end -= 12
                if ((end - syncOff) % 188 == 4) {
                    end -= 4
                }
            }
            val aligned = ((end - syncOff) / 188) * 188
            if (aligned >= 188) return syncOff to aligned
        }

        return 0 to raw.size
    }

    private fun rewriteM3u8(body: String, baseUrl: String, entry: RouteEntry): String {
        val out = StringBuilder(body.length + 1024)
        val uriAttrRegex = Regex("URI=\"([^\"]+)\"")
        var nextIsVariant = false
        val isMasterPlaylist = body.contains("#EXT-X-STREAM-INF", ignoreCase = true)

        val lines = body.replace("\r\n", "\n").split('\n')
        for ((idx, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (idx < lines.lastIndex) out.append('\n')
                continue
            }

            if (line.startsWith("#")) {
                val rewrittenTag = if (line.contains("URI=\"")) {
                    val isPlaylistTag = line.startsWith("#EXT-X-MEDIA", ignoreCase = true) ||
                        line.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true)
                    uriAttrRegex.replace(line) { m ->
                        val resolved = resolveHttpUrl(baseUrl, m.groupValues[1])
                        if (resolved != null) {
                            val proxied = if (isPlaylistTag) {
                                registerPlaylist(resolved, entry.unwrapPngTs, entry.referer, entry.origin, entry.userAgent)
                            } else {
                                registerSegment(resolved, false, entry.referer, entry.origin, entry.userAgent)
                            }
                            "URI=\"${proxied ?: resolved}\""
                        } else {
                            m.value
                        }
                    }
                } else {
                    line
                }
                out.append(rewrittenTag)
                nextIsVariant = line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
            } else {
                val resolved = resolveHttpUrl(baseUrl, line)
                if (resolved != null) {
                    val isSubPlaylist = nextIsVariant ||
                        (isMasterPlaylist && !resolved.contains(".ts", ignoreCase = true)) ||
                        resolved.contains(".m3u8", ignoreCase = true)
                    val proxied = if (isSubPlaylist) {
                        registerPlaylist(resolved, entry.unwrapPngTs, entry.referer, entry.origin, entry.userAgent)
                    } else {
                        registerSegment(resolved, entry.unwrapPngTs, entry.referer, entry.origin, entry.userAgent)
                    }
                    out.append(proxied ?: resolved)
                } else {
                    out.append(line)
                }
                nextIsVariant = false
            }

            if (idx < lines.lastIndex) out.append('\n')
        }
        return out.toString()
    }
}

class VidnetProvider : MainAPI() {
    override var mainUrl = VIDROCK_BASE_URL
    override var name = "Vidnet"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Today",
        "/movie/popular" to "Popular Movies",
        "/tv/popular" to "Popular TV Shows"
    )

    private val tmdbHosts = listOf(
        TMDB_BASE_URL,
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val imdbCache = ConcurrentHashMap<Int, String>()

    private val blockedWebViewDomains = listOf(
        "acscdn.com",
        "aclib",
        "rapidadexchange.com",
        "youradexchange.com",
        "adexchangerapid.com",
        "usrpubtrck.com",
        "histats.com",
        "stats.vidrock.to",
        "sbx-2dl.pages.dev",
        "disable-devtool",
        "googletagmanager.com",
        "google-analytics.com",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "popads.net",
        "onclickads.net",
        "propellerads.com",
        "popcash.net",
        "adsterra.com",
        "adcash.com",
        "rtmark.net"
    )

    private fun imageUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return "https://wsrv.nl/?url=image.tmdb.org/t/p/$size$path"
    }

    private suspend fun tmdbGet(path: String): JSONObject {
        var lastError: Exception? = null
        val separator = if (path.contains("?")) "&" else "?"

        for (host in tmdbHosts) {
            try {
                val response = app.get(
                    "$host$path${separator}api_key=$TMDB_API_KEY",
                    headers = mapOf("User-Agent" to userAgent),
                    timeout = 10L
                )

                if (response.code == 200) {
                    val body = response.text
                    if (body.trimStart().startsWith("{")) return JSONObject(body)
                }

                lastError = IllegalStateException("TMDB HTTP ${response.code}: $host")
            } catch (e: Exception) {
                rethrowIfCancellation(e)
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("TMDB request failed")
    }

    private fun searchResponse(
        item: JSONObject,
        forcedType: String? = null
    ): SearchResponse? {
        val type = forcedType ?: item.optString("media_type")
        if (type != "movie" && type != "tv") return null

        val id = item.optInt("id", 0)
        if (id <= 0) return null

        val title = if (type == "movie") {
            item.optString("title", item.optString("original_title"))
        } else {
            item.optString("name", item.optString("original_name"))
        }.trim()

        if (title.isBlank()) return null

        return newMovieSearchResponse(
            title,
            vnDetailUrl(type, id),
            if (type == "tv") TvType.TvSeries else TvType.Movie
        ) {
            posterUrl = imageUrl(item.optString("poster_path"))
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val json = tmdbGet("${request.data}?page=$page")
            val results = json.optJSONArray("results")
                ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

            val fallbackType = when {
                request.data.startsWith("/movie") -> "movie"
                request.data.startsWith("/tv") -> "tv"
                else -> null
            }

            val items = ArrayList<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                searchResponse(item, fallbackType)?.let(items::add)
            }

            newHomePageResponse(
                request.name,
                items.distinctBy { it.url },
                hasNext = page < json.optInt("total_pages", 1)
            )
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("getMainPage", e.stackTraceToString())
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val json = tmdbGet("/search/multi?query=$encoded")
            val results = json.optJSONArray("results") ?: return emptyList()

            val output = ArrayList<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                searchResponse(item)?.let(output::add)
            }

            output.distinctBy { it.url }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("search", e.stackTraceToString())
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseVnRef(url) ?: run {
            ClipboardLogger.logAndCopy("load", "Invalid provider URL/data: $url")
            return null
        }

        return try {
            val json = tmdbGet("/${ref.type}/${ref.id}?append_to_response=external_ids")
            if (json.has("status_code")) {
                throw IllegalStateException(json.optString("status_message"))
            }

            val imdbId = json.optString("imdb_id", "").ifBlank {
                json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            }
            if (imdbId.startsWith("tt")) {
                imdbCache[ref.id] = imdbId
            }

            val title = if (ref.type == "movie") {
                json.optString("title", json.optString("original_title"))
            } else {
                json.optString("name", json.optString("original_name"))
            }.ifBlank { "Unknown title" }

            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val poster = imageUrl(json.optString("poster_path"))
            val date = if (ref.type == "movie") {
                json.optString("release_date")
            } else {
                json.optString("first_air_date")
            }
            val year = date.take(4).toIntOrNull()
            val canonicalUrl = vnDetailUrl(ref.type, ref.id)

            if (ref.type == "movie") {
                newMovieLoadResponse(
                    title,
                    canonicalUrl,
                    TvType.Movie,
                    "vidnet:movie:${ref.id}"
                ) {
                    posterUrl = poster
                    plot = overview
                    this.year = year
                }
            } else {
                val episodes = ArrayList<Episode>()
                val seasonCount = json.optInt("number_of_seasons", 0)

                for (seasonNumber in 1..seasonCount) {
                    try {
                        val seasonJson = tmdbGet("/tv/${ref.id}/season/$seasonNumber")
                        val episodeArray = seasonJson.optJSONArray("episodes") ?: continue

                        for (i in 0 until episodeArray.length()) {
                            val episodeJson = episodeArray.optJSONObject(i) ?: continue
                            val season = episodeJson.optInt("season_number", seasonNumber)
                            val episode = episodeJson.optInt("episode_number", i + 1)
                            val episodeName = episodeJson.optString("name")
                                .ifBlank { "Episode $episode" }

                            episodes.add(
                                newEpisode("vidnet:tv:${ref.id}:$season:$episode") {
                                    name = episodeName
                                    this.season = season
                                    this.episode = episode
                                    description = episodeJson.optString("overview")
                                        .takeIf { it.isNotBlank() }
                                    posterUrl = imageUrl(
                                        episodeJson.optString("still_path"),
                                        "w500"
                                    )
                                }
                            )
                        }
                    } catch (e: Exception) {
                        rethrowIfCancellation(e)
                        ClipboardLogger.logAndCopy(
                            "Season $seasonNumber",
                            e.message ?: "Unable to load season"
                        )
                    }
                }

                newTvSeriesLoadResponse(
                    title,
                    canonicalUrl,
                    TvType.TvSeries,
                    episodes
                ) {
                    posterUrl = poster
                    plot = overview
                    this.year = year
                }
            }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("load detail", e.stackTraceToString())
            null
        }
    }

    // =========================================================================
    // Subtitles (Vidrock sub.vdrk.site v2 + v1, plus OpenSubtitles)
    // =========================================================================

    private fun appendSubExtFragment(url: String, formatHint: String = "vtt"): String {
        val lower = url.lowercase()
        if (lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass")) {
            return url
        }
        val cleanExt = if (formatHint.equals("srt", ignoreCase = true)) "srt" else "vtt"
        return "$url#file.$cleanExt"
    }

    private fun parseVidrockSubItem(
        obj: JSONObject,
        fallbackLang: String,
        baseUrl: String
    ): Pair<String, String>? {
        val rawUrl = obj.optString("url").ifBlank { obj.optString("file") }
        val resolved = resolveHttpUrl(baseUrl, rawUrl) ?: return null
        val label = obj.optString("label")
            .ifBlank { obj.optString("language") }
            .ifBlank { obj.optString("lang") }
            .ifBlank { fallbackLang }
            .trim()
        return label to appendSubExtFragment(resolved, "vtt")
    }

    private suspend fun loadVidrockSubtitles(
        ref: VnMediaRef,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenSubs: HashSet<String>
    ): Int {
        val path = if (ref.type == "tv") {
            "tv/${ref.id}/${ref.season}/${ref.episode}"
        } else {
            "movie/${ref.id}"
        }
        val collected = mutableListOf<Pair<String, String>>()

        for (ver in listOf("v2", "v1")) {
            val apiUrl = "$VIDROCK_SUB_URL/$ver/$path"
            runCatching {
                val body = app.get(
                    apiUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$VIDROCK_BASE_URL/",
                        "Origin" to VIDROCK_BASE_URL
                    ),
                    timeout = 8L
                ).text.trim()

                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        parseVidrockSubItem(o, "Subtitle", apiUrl)?.let(collected::add)
                    }
                } else if (body.startsWith("{")) {
                    val root = JSONObject(body)
                    val arrayKeys = listOf("subtitles", "data", "results", "items", "list")
                    var foundArray = false
                    for (k in arrayKeys) {
                        val arr = root.optJSONArray(k) ?: continue
                        foundArray = true
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            parseVidrockSubItem(o, "Subtitle", apiUrl)?.let(collected::add)
                        }
                    }
                    if (!foundArray) {
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val o = root.optJSONObject(k) ?: continue
                            parseVidrockSubItem(o, k, apiUrl)?.let(collected::add)
                        }
                    }
                }
            }.onFailure { rethrowIfCancellation(it) }
        }

        // Prioritize Vietnamese & English first, limit excessive duplicate language spam
        val sorted = collected.sortedBy { (lbl, _) ->
            val l = lbl.lowercase()
            when {
                l.contains("viet") || l == "vi" -> 0
                l.contains("eng") || l == "en" -> 1
                else -> 2
            }
        }

        var added = 0
        val langCounts = HashMap<String, Int>()
        for ((lbl, url) in sorted) {
            if (!seenSubs.add(url)) continue
            val normLang = lbl.lowercase().replace(Regex("""[\d\s\-_().:]+"""), "").take(10)
            val cnt = langCounts[normLang] ?: 0
            if (cnt >= 5) continue
            langCounts[normLang] = cnt + 1

            subtitleCallback.invoke(SubtitleFile(lbl, url))
            added++
        }
        return added
    }

    private suspend fun loadOpenSubtitles(
        ref: VnMediaRef,
        imdbId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        seenSubs: HashSet<String>
    ): Int {
        if (imdbId.isBlank()) return 0
        var added = 0
        val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId.removePrefix("tt") }
        val osHeaders = mapOf(
            "User-Agent" to "trailers.to-UA",
            "X-User-Agent" to "trailers.to-UA"
        )

        val candidates = mutableListOf<Triple<String, String, String>>()

        for ((langCode, langLabel) in listOf("vie" to "Vietnamese", "eng" to "English")) {
            runCatching {
                val url = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                    "https://rest.opensubtitles.org/search/episode-${ref.episode}/imdbid-$cleanId/season-${ref.season}/sublanguageid-$langCode"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$langCode"
                }
                val res = app.get(url, headers = osHeaders, timeout = 8L).text.trim()
                if (!res.startsWith("[")) return@runCatching
                val arr = JSONArray(res)
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedBy { obj ->
                        when (obj.optString("SubFormat", "").lowercase()) {
                            "srt", "vtt" -> 0
                            else -> 1
                        }
                    }
                for (item in items.take(5)) {
                    val rawLink = item.optString("SubDownloadLink", "").trim()
                    if (!rawLink.startsWith("http")) continue
                    val direct = rawLink
                        .replace("/filead/", "/subencoding-utf8/filead/")
                        .replace("/file/", "/subencoding-utf8/file/")
                        .removeSuffix(".gz")
                    val fmt = item.optString("SubFormat", "srt")
                    val rel = item.optString("MovieReleaseName", "").trim().take(28)
                    val lName = item.optString("LanguageName", langLabel).trim()
                    candidates.add(Triple(lName, rel, appendSubExtFragment(direct, fmt)))
                }
            }.onFailure { rethrowIfCancellation(it) }
        }

        runCatching {
            val stremioUrl = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:${ref.season}:${ref.episode}.json"
            } else {
                "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
            }
            val body = app.get(stremioUrl, headers = mapOf("User-Agent" to userAgent), timeout = 8L).text.trim()
            if (!body.startsWith("{")) return@runCatching
            val arr = JSONObject(body).optJSONArray("subtitles") ?: return@runCatching
            var viCount = 0
            var enCount = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val subUrl = o.optString("url", "").trim()
                if (!subUrl.startsWith("http")) continue
                val l = o.optString("lang", "").lowercase()
                val rel = o.optString("releaseGroup", "").trim().take(28)
                when {
                    (l == "vie" || l == "vi") && viCount < 4 -> {
                        candidates.add(Triple("Vietnamese", rel, appendSubExtFragment(subUrl, "srt")))
                        viCount++
                    }
                    (l == "eng" || l == "en") && enCount < 4 -> {
                        candidates.add(Triple("English", rel, appendSubExtFragment(subUrl, "srt")))
                        enCount++
                    }
                }
            }
        }.onFailure { rethrowIfCancellation(it) }

        for ((langLbl, rel, url) in candidates) {
            if (!seenSubs.add(url)) continue
            val label = if (rel.isNotBlank()) "$langLbl (OpenSubtitles: $rel)" else "$langLbl (OpenSubtitles)"
            subtitleCallback.invoke(SubtitleFile(label, url))
            added++
        }
        return added
    }

    // =========================================================================
    // Vidrock API Fetching & AES-GCM Decryption
    // =========================================================================

    private fun parseVidrockApiJson(rawJson: String): List<VnDecryptedSource> {
        val text = rawJson.trim()
        if (!text.startsWith("{")) return emptyList()
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<VnDecryptedSource>()

        val keys = obj.keys()
        while (keys.hasNext()) {
            val srvName = keys.next()
            val entry = obj.optJSONObject(srvName) ?: continue
            val encUrl = entry.optString("url", "").trim()
            if (encUrl.isBlank()) continue

            val decUrl = VidrockCrypto.decryptUrl(encUrl)
            if (decUrl.isNullOrBlank()) {
                ClipboardLogger.logAndCopy("Decrypt Fail ($srvName)", "Không giải mã được URL")
                continue
            }

            val language = entry.optString("language", "Original audio").ifBlank { "Original audio" }
            val flag = entry.optString("flag", "")
            val type = entry.optString("type", "").lowercase()
            out.add(VnDecryptedSource(srvName, decUrl, language, flag, type))
        }
        return out
    }

    private suspend fun fetchVidrockApiSources(ref: VnMediaRef): List<VnDecryptedSource> {
        val path = if (ref.type == "tv") {
            "tv/${ref.id}/${ref.season}/${ref.episode}"
        } else {
            "movie/${ref.id}"
        }
        val apiUrl = "$VIDROCK_BASE_URL/api/$path"
        val hdrs = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$VIDROCK_BASE_URL/",
            "Origin" to VIDROCK_BASE_URL,
            "Accept" to "application/json,text/plain,*/*",
            "Cache-Control" to "no-cache"
        )

        for (attempt in 0 until 3) {
            val resp = runCatching {
                app.get(apiUrl, headers = hdrs, timeout = 12L)
            }.onFailure { rethrowIfCancellation(it) }.getOrNull()

            if (resp != null) {
                if (resp.code in 200..299) {
                    val list = parseVidrockApiJson(resp.text)
                    ClipboardLogger.logAndCopy(
                        "Vidrock API",
                        "HTTP ${resp.code} -> giải mã ${list.size} server: ${list.joinToString { it.serverName }}"
                    )
                    if (list.isNotEmpty()) return list
                    break
                } else {
                    ClipboardLogger.logAndCopy("Vidrock API Retry", "Attempt ${attempt + 1}: HTTP ${resp.code}")
                    if (resp.code != 429 && resp.code < 500) break
                }
            }
            if (attempt < 2) {
                java.lang.Thread.sleep(600L * (attempt + 1))
            }
        }
        return emptyList()
    }

    // =========================================================================
    // Stream Probe (MP4 / JSON-Quality / M3U8 + PNG-TS First-Segment Check)
    // =========================================================================

    private enum class SegKind { CLEAN_TS, PNG_TS, INVALID }

    private fun isLikelyValidM3u8(body: String): Boolean {
        val firstNonEmpty = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
        if (!firstNonEmpty.startsWith("#EXTM3U", ignoreCase = true)) return false
        if (body.contains("<html", ignoreCase = true) ||
            body.contains("500 Internal Server Error", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun mapQuality(res: Int): Int {
        return when {
            res >= 2160 -> Qualities.P2160.value
            res >= 1440 -> Qualities.P1440.value
            res >= 1080 -> Qualities.P1080.value
            res >= 720 -> Qualities.P720.value
            res >= 480 -> Qualities.P480.value
            res >= 360 -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun inspectFirstSegmentBlocking(segUrl: String, hdrs: Map<String, String>): Pair<SegKind, String> {
        var current = segUrl
        for (hop in 0..3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    for ((k, v) in hdrs) setRequestProperty(k, v)
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")?.trim()
                    if (!loc.isNullOrBlank()) {
                        current = resolveHttpUrl(current, loc) ?: loc
                        continue
                    }
                }
                if (code !in 200..299) {
                    return SegKind.INVALID to "Seg HTTP $code"
                }

                val ct = conn.contentType.orEmpty()
                val contentLenHeader = conn.contentLength
                val buf = ByteArray(4096)
                var totalRead = 0
                conn.inputStream.use { inp ->
                    while (totalRead < buf.size) {
                        val r = inp.read(buf, totalRead, buf.size - totalRead)
                        if (r <= 0) break
                        totalRead += r
                    }
                }

                if (totalRead < 256) {
                    return SegKind.INVALID to "Placeholder body ($totalRead bytes, ct=$ct)"
                }

                val headStr = runCatching { String(buf, 0, minOf(128, totalRead), Charsets.UTF_8) }.getOrDefault("")
                if (headStr.contains("<html", ignoreCase = true) || headStr.contains("<!doctype", ignoreCase = true)) {
                    return SegKind.INVALID to "HTML error in segment"
                }

                val startsWithPng = totalRead >= 8 &&
                    (buf[0].toInt() and 0xFF) == 0x89 &&
                    (buf[1].toInt() and 0xFF) == 0x50 &&
                    (buf[2].toInt() and 0xFF) == 0x4E &&
                    (buf[3].toInt() and 0xFF) == 0x47

                if (startsWithPng || ct.contains("image/", ignoreCase = true)) {
                    val sync = VidnetHlsProxy.findTsSyncOffset(buf, totalRead)
                    val hasTsDa = headStr.contains("tsDa")
                    if (sync >= 0 || (hasTsDa && (contentLenHeader < 0 || contentLenHeader > 1024))) {
                        return SegKind.PNG_TS to "PNG-wrapped TS (sync=$sync, ct=$ct)"
                    }
                    return SegKind.INVALID to "Non-video PNG/Image (bytes=$totalRead, ct=$ct)"
                }

                return SegKind.CLEAN_TS to "Clean segment (HTTP $code, ct=$ct)"
            } catch (e: Exception) {
                return SegKind.INVALID to "Seg err: ${e.message}"
            } finally {
                conn?.disconnect()
            }
        }
        return SegKind.INVALID to "Too many redirects"
    }

    private suspend fun probeAndEmitVidrockSource(
        src: VnDecryptedSource,
        emittedUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit,
        qualityHint: Int = Qualities.P1080.value
    ): Boolean {
        val hdrs = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Referer" to "$VIDROCK_BASE_URL/",
            "Origin" to VIDROCK_BASE_URL
        )

        val displayLabel = buildString {
            append("Vidnet • ${src.serverName}")
            if (src.language.isNotBlank() && !src.language.equals("English", ignoreCase = true) &&
                !src.language.equals("Original audio", ignoreCase = true)
            ) {
                append(" (${src.language})")
            }
        }

        val isMp4Hint = src.type == "mp4" ||
            Regex("""\.(mp4|mkv|webm)(?:$|[?#])""", RegexOption.IGNORE_CASE).containsMatchIn(src.url)

        if (isMp4Hint) {
            val probeResp = runCatching {
                app.get(src.url, headers = hdrs + ("Range" to "bytes=0-1023"), timeout = 10L)
            }.onFailure { rethrowIfCancellation(it) }.getOrNull()

            if (probeResp != null && probeResp.code in 200..299) {
                val head = probeResp.text.take(200).trimStart()
                if (!head.startsWith("<!doctype", ignoreCase = true) && !head.startsWith("<html", ignoreCase = true)) {
                    if (emittedUrls.add(src.url)) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$displayLabel (MP4)",
                                url = src.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$VIDROCK_BASE_URL/"
                                this.quality = qualityHint
                                this.headers = hdrs
                            }
                        )
                        ClipboardLogger.logAndCopy("Live (${src.serverName})", "MP4 HTTP ${probeResp.code} -> ${src.url.take(150)}")
                        return true
                    }
                }
            }
            ClipboardLogger.logAndCopy("Dead (${src.serverName})", "MP4 HTTP ${probeResp?.code ?: "err"} -> ${src.url.take(150)}")
            return false
        }

        val resp = runCatching {
            app.get(src.url, headers = hdrs, timeout = 10L)
        }.onFailure { rethrowIfCancellation(it) }.getOrNull() ?: run {
            ClipboardLogger.logAndCopy("Dead (${src.serverName})", "Timeout/Error -> ${src.url.take(150)}")
            return false
        }

        if (resp.code !in 200..299) {
            ClipboardLogger.logAndCopy("Dead (${src.serverName})", "HTTP ${resp.code} -> ${src.url.take(150)}")
            return false
        }

        val body = resp.text.trim()

        // Case 1: Vidrock JSON quality array (`[{"resolution": 1080, "url": "..."}, ...]`)
        if (body.startsWith("[")) {
            val arr = runCatching { JSONArray(body) }.getOrNull()
            if (arr != null && arr.length() > 0) {
                val items = (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .mapNotNull { o ->
                        val res = o.optInt("resolution", 0)
                        val qUrl = resolveHttpUrl(src.url, o.optString("url")) ?: return@mapNotNull null
                        res to qUrl
                    }
                    .sortedByDescending { it.first }

                var subEmitted = 0
                for ((res, qUrl) in items) {
                    val ok = probeAndEmitVidrockSource(
                        VnDecryptedSource(
                            serverName = "${src.serverName} ${if (res > 0) "${res}p" else ""}".trim(),
                            url = qUrl,
                            language = src.language,
                            flag = src.flag,
                            type = if (qUrl.contains(".m3u8", ignoreCase = true)) "hls" else "mp4"
                        ),
                        emittedUrls,
                        callback,
                        if (res > 0) mapQuality(res) else Qualities.P1080.value
                    )
                    if (ok) subEmitted++
                }
                return subEmitted > 0
            }
        }

        // Case 2: M3U8 playlist validation
        if (!isLikelyValidM3u8(body)) {
            ClipboardLogger.logAndCopy("Dead (${src.serverName})", "Invalid M3U8 HTTP ${resp.code} -> ${body.take(140)}")
            return false
        }

        // Follow master playlist down to child media playlist so we can inspect first segment
        var mediaUrl = src.url
        var mediaBody = body
        if (body.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            val childLine = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            val resolvedChild = resolveHttpUrl(src.url, childLine)
            if (resolvedChild != null) {
                val childResp = runCatching {
                    app.get(resolvedChild, headers = hdrs, timeout = 10L)
                }.onFailure { rethrowIfCancellation(it) }.getOrNull()
                if (childResp != null && childResp.code in 200..299 && isLikelyValidM3u8(childResp.text)) {
                    mediaUrl = resolvedChild
                    mediaBody = childResp.text
                }
            }
        }

        // Probe first segment to catch 70-byte placeholder PNGs & detect PNG-wrapped MPEG-TS
        val firstSegLine = mediaBody.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        val firstSegUrl = resolveHttpUrl(mediaUrl, firstSegLine)
        var unwrapPng = false
        if (firstSegUrl != null) {
            val (segKind, segNote) = inspectFirstSegmentBlocking(firstSegUrl, hdrs)
            if (segKind == SegKind.INVALID) {
                ClipboardLogger.logAndCopy("Dead (${src.serverName})", "$segNote -> ${src.url.take(120)}")
                return false
            }
            unwrapPng = (segKind == SegKind.PNG_TS)
            ClipboardLogger.logAndCopy("Probe (${src.serverName})", segNote)
        }

        val finalUrl: String
        val finalHeaders: Map<String, String>
        val modeTag: String
        if (unwrapPng) {
            finalUrl = VidnetHlsProxy.registerPlaylist(
                targetUrl = src.url,
                unwrapPngTs = true,
                referer = "$VIDROCK_BASE_URL/",
                origin = VIDROCK_BASE_URL,
                userAgent = userAgent
            ) ?: src.url
            finalHeaders = mapOf("User-Agent" to userAgent)
            modeTag = "HLS-PngProxy"
        } else {
            finalUrl = if (src.url.contains(".m3u8", ignoreCase = true)) src.url else "${src.url}#file.m3u8"
            finalHeaders = hdrs
            modeTag = "HLS"
        }

        if (emittedUrls.add(finalUrl)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$displayLabel (HLS)",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$VIDROCK_BASE_URL/"
                    this.quality = qualityHint
                    this.headers = finalHeaders
                }
            )
            ClipboardLogger.logAndCopy("Live (${src.serverName})", "$modeTag HTTP ${resp.code} -> ${finalUrl.take(150)}")
            return true
        }
        return false
    }

    // =========================================================================
    // Attached Off-Screen WebView Fallback (Strictly Vidrock-only)
    // =========================================================================

    private data class VnWebSniffResult(
        val capturedStreams: List<String>,
        val capturedSubs: List<String>,
        val observedRequests: List<String>
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVidrockFallback(
        pageUrl: String,
        timeoutMs: Long = 30_000L
    ): VnWebSniffResult {
        return suspendCoroutine { continuation ->
            val activity = try {
                CommonActivity.activity
            } catch (_: Throwable) {
                null
            }
            val appCtx = activity?.applicationContext ?: activity ?: runCatching {
                val at = Class.forName("android.app.ActivityThread")
                at.getMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()

            if (appCtx == null) {
                continuation.resume(VnWebSniffResult(emptyList(), emptyList(), emptyList()))
                return@suspendCoroutine
            }

            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                val webView = try {
                    WebView(appCtx)
                } catch (_: Exception) {
                    continuation.resume(VnWebSniffResult(emptyList(), emptyList(), emptyList()))
                    return@post
                }

                var finished = false
                var graceScheduled = false
                val w = 1080
                val h = 1920

                val capturedStreams = linkedSetOf<String>()
                val capturedSubs = linkedSetOf<String>()
                val observedRequests = linkedSetOf<String>()

                val streamRegex = Regex("""(?i)(\.m3u8(?:$|[?#])|\.mp4(?:$|[?#])|\.mpd(?:$|[?#])|bigtits\.m3u8|master\.m3u8|master\.txt)""")
                val assetIgnoreRegex = Regex("""(?i)\.(?:css|js|png|jpe?g|gif|svg|ico|woff2?|ttf|wasm|ts)(?:$|[?#])""")
                val subRegex = Regex("""(?i)\.(?:vtt|srt)(?:$|[?#])""")

                var timeoutTask: Runnable? = null
                var graceTask: Runnable? = null

                fun complete() {
                    if (finished) return
                    finished = true
                    timeoutTask?.let(mainHandler::removeCallbacks)
                    graceTask?.let(mainHandler::removeCallbacks)

                    val streams = synchronized(capturedStreams) { capturedStreams.toList() }
                    val subs = synchronized(capturedSubs) { capturedSubs.toList() }
                    val reqs = synchronized(observedRequests) { observedRequests.toList() }

                    try {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    } catch (_: Exception) { }

                    continuation.resume(VnWebSniffResult(streams, subs, reqs))
                }

                fun scheduleGraceFinish() {
                    if (finished || graceScheduled) return
                    graceScheduled = true
                    graceTask = Runnable { complete() }
                    mainHandler.postDelayed(graceTask!!, 1800L)
                }

                timeoutTask = Runnable { complete() }

                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = userAgent
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false

                        override fun onConsoleMessage(cm: ConsoleMessage?): Boolean = true
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http://") && !u.startsWith("https://")) return null

                            if (blockedWebViewDomains.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            val isMedia = !assetIgnoreRegex.containsMatchIn(u) && streamRegex.containsMatchIn(u)
                            if (isMedia || !u.contains("/seg/")) {
                                synchronized(observedRequests) {
                                    if (observedRequests.size < 80) observedRequests.add(u.take(200))
                                }
                            }

                            if (subRegex.containsMatchIn(u)) {
                                synchronized(capturedSubs) {
                                    if (capturedSubs.size < 20) capturedSubs.add(u)
                                }
                            }

                            if (isMedia) {
                                synchronized(capturedStreams) {
                                    if (capturedStreams.size < 15) capturedStreams.add(u)
                                }
                                mainHandler.post { scheduleGraceFinish() }
                            }
                            return null
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) { }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) { }
                    }

                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, w, h)
                    webView.translationX = -4000f
                    try {
                        activity?.addContentView(webView, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) { }

                    timeoutTask?.let { mainHandler.postDelayed(it, timeoutMs) }
                    webView.loadUrl(pageUrl, mapOf("User-Agent" to userAgent, "Referer" to "$VIDROCK_BASE_URL/"))
                } catch (_: Exception) {
                    complete()
                }
            }
        }
    }

    // =========================================================================
    // loadLinks (Strictly Vidrock.net-only)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startMs = SystemClock.elapsedRealtime()
        ClipboardLogger.clear()
        ClipboardLogger.logAndCopy("Start", "Vidnet loadLinks: $data (casting=$isCasting)")

        val ref = parseVnRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        if (ref.type == "tv" && (ref.season == null || ref.episode == null)) {
            ClipboardLogger.logAndCopy("Error", "TV series cần season và episode: $data")
            return false
        }

        var imdbId = imdbCache[ref.id] ?: ""
        if (imdbId.isBlank()) {
            runCatching {
                val json = tmdbGet("/${ref.type}/${ref.id}?append_to_response=external_ids")
                imdbId = json.optString("imdb_id", "").ifBlank {
                    json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
                }
                if (imdbId.startsWith("tt")) {
                    imdbCache[ref.id] = imdbId
                }
            }.onFailure { rethrowIfCancellation(it) }
        }

        val seenSubs = HashSet<String>()
        var subCount = loadVidrockSubtitles(ref, subtitleCallback, seenSubs)
        subCount += loadOpenSubtitles(ref, imdbId, subtitleCallback, seenSubs)
        if (subCount > 0) {
            ClipboardLogger.logAndCopy("Subtitles", "Đã nạp $subCount phụ đề (Vidrock + OpenSubtitles)")
        }

        var emitted = 0
        val emittedUrls = HashSet<String>()

        try {
            // 1. Primary: Direct Vidrock API + native AES-256-GCM decryption
            val apiSources = fetchVidrockApiSources(ref)
            for (src in apiSources) {
                if (probeAndEmitVidrockSource(src, emittedUrls, callback)) {
                    emitted++
                }
            }

            // 2. Fallback only if direct API returned 0 live streams: Vidrock WebView sniff
            if (emitted == 0) {
                val embedUrl = if (ref.type == "movie") {
                    "$VIDROCK_BASE_URL/movie/${ref.id}"
                } else {
                    "$VIDROCK_BASE_URL/tv/${ref.id}/${ref.season}/${ref.episode}"
                }
                val remMs = (80_000L - (SystemClock.elapsedRealtime() - startMs)).coerceIn(15_000L, 30_000L)
                ClipboardLogger.logAndCopy("WebView Fallback", "Thử sniff Vidrock (${remMs / 1000}s): $embedUrl")
                val sniff = sniffVidrockFallback(embedUrl, remMs)

                for (subUrl in sniff.capturedSubs) {
                    val norm = appendSubExtFragment(resolveHttpUrl(embedUrl, subUrl) ?: subUrl, "vtt")
                    if (seenSubs.add(norm)) {
                        subtitleCallback.invoke(SubtitleFile("Vidnet • VTT", norm))
                        subCount++
                    }
                }

                var idx = 1
                for (streamUrl in sniff.capturedStreams) {
                    val src = VnDecryptedSource(
                        serverName = "WebView #$idx",
                        url = streamUrl,
                        language = "Original audio",
                        flag = "",
                        type = if (streamUrl.contains(".mp4", ignoreCase = true)) "mp4" else "hls"
                    )
                    if (probeAndEmitVidrockSource(src, emittedUrls, callback)) {
                        emitted++
                        idx++
                    }
                }
            }
        } catch (e: Exception) {
            rethrowIfCancellation(e)
            ClipboardLogger.logAndCopy("Exception", "${e.javaClass.simpleName}: ${e.message}")
        }

        ClipboardLogger.logAndCopy(
            if (emitted > 0) "Done" else "Failed",
            "Đã nạp $emitted luồng phát & $subCount phụ đề từ Vidnet."
        )
        return emitted > 0
    }
}

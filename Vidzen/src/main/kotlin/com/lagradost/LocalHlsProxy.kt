package com.lagradost

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object LocalHlsProxy {
    private const val TAG = "VidZen-Proxy"
    private var server: ServerSocket? = null
    @Volatile private var port: Int = 0
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var logCount = 0
    @Volatile private var segLogged = 0
    @Volatile private var playlistLogged = 0
    @Volatile private var defaultHeaders: Map<String, String> = emptyMap()

    fun setDefaultHeaders(headers: Map<String, String>) {
        defaultHeaders = headers
    }

    @Synchronized
    fun ensureStarted(): Int {
        if (server != null && port > 0) return port
        try {
            server = ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"))
            port = server!!.localPort
            Thread({
                while (server?.isClosed == false) {
                    try {
                        val socket = server!!.accept()
                        Thread({ handleRequest(socket) }, "VidZen-Proxy-Worker").apply {
                            isDaemon = true
                        }.start()
                    } catch (_: Exception) {
                        if (server?.isClosed == true) break
                    }
                }
            }, "VidZen-Proxy-Main").apply { isDaemon = true }.start()
            Log.d(TAG, "Proxy started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "start fail: ${e.message}")
        }
        return port
    }

    fun proxyUrl(remoteUrl: String): String {
        val p = ensureStarted()
        return "http://127.0.0.1:$p/proxy?url=${URLEncoder.encode(remoteUrl, "UTF-8")}"
    }

    private fun handleRequest(socket: Socket) {
        try {
            socket.use {
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                val requestHeaders = mutableMapOf<String, String>()
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    val idx = h.indexOf(':')
                    if (idx > 0) {
                        requestHeaders[h.substring(0, idx).trim().lowercase()] =
                            h.substring(idx + 1).trim()
                    }
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val uri = parts[1]

                if (!uri.startsWith("/proxy")) {
                    sendError(socket, 404, "Not Found")
                    return
                }
                val params = parseQuery(uri.substringAfter("?", ""))
                val remoteUrl = params["url"] ?: run {
                    sendError(socket, 400, "Missing url")
                    return
                }
                // Playlist hay đoạn video: quyết định theo NỘI DUNG phản hồi (không đoán theo URL)

                // Parse Range from client
                val rangeHeader = requestHeaders["range"]
                var rangeStart = -1L
                var rangeEnd = -1L
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val spec = rangeHeader.removePrefix("bytes=").trim()
                    val dashIdx = spec.indexOf('-')
                    if (dashIdx >= 0) {
                        rangeStart = spec.substring(0, dashIdx).toLongOrNull() ?: -1L
                        val endStr = spec.substring(dashIdx + 1)
                        if (endStr.isNotBlank()) rangeEnd = endStr.toLongOrNull() ?: -1L
                    }
                }
                Log.d(TAG, "$method ${remoteUrl.take(80)} | range=${rangeStart}..${rangeEnd}")

                // Build upstream request
                val reqBuilder = Request.Builder().url(remoteUrl).get()
                defaultHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
                if (rangeStart >= 0) {
                    val rangeStr = if (rangeEnd >= 0) "bytes=$rangeStart-$rangeEnd" else "bytes=$rangeStart-"
                    reqBuilder.header("Range", rangeStr)
                }

                val response = try {
                    httpClient.newCall(reqBuilder.build()).execute()
                } catch (e: Exception) {
                    Log.e(TAG, "upstream fail: ${e.message}")
                    sendError(socket, 502, "Upstream fail")
                    return
                }

                val code = response.code
                val body = response.body
                if (body == null) {
                    sendError(socket, 502, "No body")
                    return
                }

                try {
                    val input = java.io.BufferedInputStream(body.byteStream(), 64 * 1024)
                    input.mark(32)
                    val peek = ByteArray(16)
                    val pn = input.read(peek).coerceAtLeast(0)
                    input.reset()
                    val peekStr = String(peek, 0, pn, Charsets.ISO_8859_1).trimStart('\uFEFF', 'ï', '»', '¿', ' ', '\r', '\n', '\t')
                    val isM3u8 = code in 200..299 && peekStr.startsWith("#EXTM3U")
                    if (logCount++ < 40 && !isM3u8) {
                        ClipboardLogger.logAndCopy(
                            "PX",
                            "$method code=$code ct=${response.header("Content-Type")} len=${response.header("Content-Length")} " +
                                "range=$rangeStart..$rangeEnd m3u8=$isM3u8 head=${peek.take(6).joinToString("") { "%02x".format(it) }} url=${remoteUrl.take(110)}"
                        )
                    }

                    // === M3U8: rewrite — luôn trả 200 ===
                    if (isM3u8) {
                        val text = String(input.readBytes(), Charsets.UTF_8)
                        if (playlistLogged++ < 7) ClipboardLogger.logAndCopy("PLAYLIST", text.lines().filter { it.startsWith("#EXT-X-MAP") || it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-BYTERANGE") || it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-TARGETDURATION") || it.startsWith("#EXT-X-PLAYLIST-TYPE") || it.startsWith("#EXT-X-DISCONTINUITY") || it.startsWith("#EXTINF") }.take(12).joinToString(" | ") { it.take(110) } + " || total=" + text.length + " segs=" + text.lines().count { it.startsWith("#EXTINF") })
                        val rewritten = rewriteM3u8(text, remoteUrl)
                        val bytes = rewritten.toByteArray(Charsets.UTF_8)
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
                        out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        out.write("Connection: close\r\n\r\n".toByteArray())
                        if (!method.equals("HEAD", true)) out.write(bytes)
                        out.flush()
                        return
                    }

                    // === SEGMENT ===
                    val rawCt = response.header("Content-Type").orEmpty()
                    val ct = when {
                        peekStr.startsWith("WEBVTT") -> "text/vtt"
                        remoteUrl.contains(".m4s", true) -> "video/iso.segment"
                        remoteUrl.contains(".mp4", true) -> "video/mp4"
                        remoteUrl.contains(".vtt", true) -> "text/vtt"
                        remoteUrl.contains(".aac", true) -> "audio/aac"
                        remoteUrl.contains(".ts", true) -> "video/mp2t"
                        rawCt.startsWith("image/") || rawCt.isBlank() || rawCt.contains("octet-stream") -> "video/mp2t"
                        else -> rawCt
                    }

                    val out = socket.getOutputStream()

                    // Case A0: 200 không Range -> đọc vài byte đầu để nhận dạng định dạng thật rồi truyền NGAY
                    //  - fMP4 (CMAF): "ftyp"/"styp"/"moof"/"sidx" -> gửi Content-Type video/mp4 (máy chủ gắn nhầm video/MP2T)
                    //  - TS giả dạng ảnh: bỏ phần đầu tới khi gặp 0x47 lặp mỗi 188 byte
                    if (code in 200..299 && rangeStart < 0 && !ct.startsWith("text/")) {
                        val first = ByteArray(9000)
                        var n = 0
                        while (n < first.size) {
                            val k = input.read(first, n, first.size - n)
                            if (k < 0) break
                            n += k
                        }
                        val box = if (n >= 8) String(first, 4, 4, Charsets.ISO_8859_1) else ""
                        val isMp4 = box == "ftyp" || box == "styp" || box == "moof" || box == "sidx" || box == "emsg"
                        var off = 0
                        var outCt = ct
                        if (isMp4) {
                            outCt = if (ct.startsWith("audio/")) "audio/mp4" else "video/mp4"
                        } else if (ct == "video/mp2t" && n > 0 && first[0] != 0x47.toByte()) {
                            val lim = minOf(n - 376, 8192)
                            for (i in 0 until lim) {
                                if (first[i] == 0x47.toByte() && first[i + 188] == 0x47.toByte() && first[i + 376] == 0x47.toByte()) { off = i; break }
                            }
                        }
                        if (segLogged++ < 8) {
                            val boxes = StringBuilder()
                            var pos = 0
                            var guard = 0
                            while (pos + 8 <= n && guard++ < 10) {
                                val sz = ((first[pos].toLong() and 0xff) shl 24) or ((first[pos + 1].toLong() and 0xff) shl 16) or ((first[pos + 2].toLong() and 0xff) shl 8) or (first[pos + 3].toLong() and 0xff)
                                boxes.append(String(first, pos + 4, 4, Charsets.ISO_8859_1)).append(':').append(sz).append(' ')
                                if (sz < 8) break
                                pos += sz.toInt().coerceAtMost(Int.MAX_VALUE - pos)
                            }
                            val raw = String(first, 0, n, Charsets.ISO_8859_1)
                            val flags = listOf("encv", "enca", "tenc", "cenc", "cbcs", "pssh", "avc1", "hvc1", "hev1", "mp4a", "av01", "vp09", "moov", "trex").filter { raw.contains(it) }
                            ClipboardLogger.logAndCopy("SEG", "box=$box off=$off ct=$outCt n=$n boxes=[$boxes] has=$flags head=${first.take(24).joinToString("") { "%02x".format(it) }}")
                        }
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: $outCt\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
                        if (!method.equals("HEAD", true)) {
                            if (n > off) out.write(first, off, n - off)
                            out.flush()
                            streamBody(input, out, null)
                        }
                        out.flush()
                        return
                    }

                    // Case A: upstream hỗ trợ Range (206)
                    if (code == 206) {
                        val contentRange = response.header("Content-Range")
                        val contentLength = response.header("Content-Length")
                            ?: body.contentLength().takeIf { it >= 0 }?.toString()
                        Log.d(TAG, "206 forward | Content-Range=$contentRange")
                        out.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                        out.write("Content-Type: $ct\r\n".toByteArray())
                        if (contentRange != null) out.write("Content-Range: $contentRange\r\n".toByteArray())
                        if (contentLength != null) out.write("Content-Length: $contentLength\r\n".toByteArray())
                        out.write("Accept-Ranges: bytes\r\n".toByteArray())
                        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        out.write("Connection: close\r\n\r\n".toByteArray())
                        if (!method.equals("HEAD", true)) streamBody(input, out, null)
                        out.flush()
                        Log.d(TAG, "206 done")
                        return
                    }

                    // Case B: upstream trả 200 (không hỗ trợ Range) NHƯNG client gửi Range
                    // → proxy tự skip start bytes và trả 206
                    if (code in 200..299 && rangeStart >= 0) {
                        val total = body.contentLength() // -1 nếu chunked
                
                        // Skip rangeStart bytes
                        var skipped = 0L
                        while (skipped < rangeStart) {
                            val n = input.skip(rangeStart - skipped)
                            if (n <= 0) break
                            skipped += n
                        }
                        if (skipped < rangeStart) {
                            Log.w(TAG, "skip fail: $skipped < $rangeStart")
                            sendError(socket, 416, "Range not satisfiable")
                            return
                        }

                        // Tính Content-Range và Content-Length
                        val end = if (rangeEnd >= 0) rangeEnd
                                  else if (total > 0) total - 1
                                  else -1L
                        val contentLen = if (end >= 0 && total > 0) end - rangeStart + 1
                                         else if (total > 0) total - rangeStart
                                         else -1L

                        Log.d(TAG, "200→206 skip=$rangeStart total=$total cl=$contentLen")
                        out.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                        out.write("Content-Type: $ct\r\n".toByteArray())
                        if (end >= 0 && total > 0) {
                            out.write("Content-Range: bytes $rangeStart-$end/$total\r\n".toByteArray())
                        }
                        if (contentLen > 0) out.write("Content-Length: $contentLen\r\n".toByteArray())
                        out.write("Accept-Ranges: bytes\r\n".toByteArray())
                        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        out.write("Connection: close\r\n\r\n".toByteArray())
                        if (!method.equals("HEAD", true)) {
                            streamBody(input, out, if (contentLen > 0) contentLen else null)
                        }
                        out.flush()
                        Log.d(TAG, "200→206 done")
                        return
                    }

                    // Case C: 200 OK — no Range
                    if (code in 200..299) {
                        val contentLength = response.header("Content-Length")
                            ?: body.contentLength().takeIf { it >= 0 }?.toString()
                        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        out.write("Content-Type: $ct\r\n".toByteArray())
                        if (contentLength != null) out.write("Content-Length: $contentLength\r\n".toByteArray())
                        out.write("Accept-Ranges: bytes\r\n".toByteArray())
                        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                        out.write("Connection: close\r\n\r\n".toByteArray())
                        if (!method.equals("HEAD", true)) streamBody(input, out, null)
                        out.flush()
                        return
                    }

                    Log.w(TAG, "upstream $code for ${remoteUrl.take(80)}")
                    sendError(socket, code, "Upstream $code")
                } finally {
                    try { body.close() } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            // Bỏ qua Broken pipe — client đã đóng kết nối là bình thường
            if (e.message?.contains("Broken pipe", true) != true &&
                e.message?.contains("Connection reset", true) != true) {
                Log.e(TAG, "handle fail: ${e.message}")
            }
        }
    }

    /** Stream body → out, giới hạn maxBytes nếu khác null. Đóng đúng cách. */
    private fun streamBody(input: InputStream, out: java.io.OutputStream, maxBytes: Long?) {
        try {
            val buf = ByteArray(64 * 1024)
            var remaining = maxBytes ?: -1L
            var total = 0L
            while (true) {
                val toRead = if (remaining > 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                if (toRead <= 0) break
                val n = try { input.read(buf, 0, toRead) } catch (_: Throwable) { -1 }
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
                if (remaining > 0) remaining -= n
            }
            Log.d(TAG, "streamed $total bytes")
        } catch (e: Throwable) {
            if (e.message?.contains("Broken pipe", true) != true) {
                Log.w(TAG, "stream fail: ${e.message}")
            }
        }
    }

    private fun rewriteM3u8(content: String, baseUrl: String): String {
        val baseUri = try { URI(baseUrl) } catch (_: Exception) { null }
        val sb = StringBuilder()
        content.lineSequence().forEachIndexed { idx, line ->
            if (idx > 0) sb.append("\n")
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> sb.append(line)
                trimmed.startsWith("#") -> {
                    if (trimmed.contains("URI=\"")) {
                        val replaced = trimmed.replace(Regex("""URI="([^"]+)"""")) { m ->
                            val abs = resolveUrl(baseUri, m.groupValues[1])
                            "URI=\"${proxyUrl(abs)}\""
                        }
                        sb.append(replaced)
                    } else {
                        sb.append(line)
                    }
                }
                else -> sb.append(proxyUrl(resolveUrl(baseUri, trimmed)))
            }
        }
        return sb.toString()
    }

    private fun resolveUrl(base: URI?, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        if (relative.startsWith("//")) return "https:$relative"
        return try { base?.resolve(relative)?.toString() ?: relative } catch (_: Exception) { relative }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return try {
            query.split("&").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) null
                else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                     URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun sendError(socket: Socket, code: Int, msg: String) {
        try {
            val body = msg.toByteArray()
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $code Error\r\n".toByteArray())
            out.write("Content-Type: text/plain\r\n".toByteArray())
            out.write("Content-Length: ${body.size}\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        } catch (_: Throwable) {}
    }
}

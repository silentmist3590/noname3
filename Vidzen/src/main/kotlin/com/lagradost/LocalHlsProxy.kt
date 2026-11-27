package com.lagradost

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
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
                val isM3u8 = remoteUrl.contains(".m3u8", true) ||
                             remoteUrl.contains("/api/stream/", true) ||
                             remoteUrl.contains("v1_", true)
                Log.d(TAG, "$method ${remoteUrl.take(100)} | range=${requestHeaders["range"] ?: "none"}")

                // --- Build upstream request ---
                val reqBuilder = Request.Builder().url(remoteUrl).get()
                defaultHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
                // Forward Range & các header quan trọng
                listOf("range", "if-range", "if-modified-since", "if-none-match").forEach { h ->
                    requestHeaders[h]?.let { reqBuilder.header(h.replaceFirstChar { c -> c.uppercase() }, it) }
                }
                // Forward user-agent nếu có
                requestHeaders["user-agent"]?.let { reqBuilder.header("User-Agent", it) }

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

                if (isM3u8 && code in 200..299) {
                    // ===== M3U8: rewrite URL → proxy =====
                    val text = body.string()
                    val rewritten = rewriteM3u8(text, remoteUrl)
                    val bytes = rewritten.toByteArray(Charsets.UTF_8)
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
                    out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                    out.write("Accept-Ranges: bytes\r\n".toByteArray())
                    out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                    out.write("Connection: close\r\n\r\n".toByteArray())
                    if (!method.equals("HEAD", true)) out.write(bytes)
                    out.flush()
                } else if (code in 200..299) {
                    // ===== SEGMENT (.ts, .m4s, .mp4): stream bytes, xử lý Range đúng =====
                    val out = socket.getOutputStream()
                    if (code == 206) {
                        out.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
                    } else {
                        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    }
                    // Content-Type
                    val ct = when {
                        remoteUrl.contains(".ts", true) -> "video/mp2t"
                        remoteUrl.contains(".m4s", true) -> "video/iso.segment"
                        remoteUrl.contains(".mp4", true) -> "video/mp4"
                        remoteUrl.contains(".vtt", true) -> "text/vtt"
                        remoteUrl.contains(".aac", true) -> "audio/aac"
                        else -> response.header("Content-Type") ?: "application/octet-stream"
                    }
                    out.write("Content-Type: $ct\r\n".toByteArray())
                    // Forward Content-Range nếu có
                    response.header("Content-Range")?.let {
                        out.write("Content-Range: $it\r\n".toByteArray())
                    }
                    // Content-Length
                    val cl = response.header("Content-Length") ?: body.contentLength().takeIf { it >= 0 }?.toString()
                    if (cl != null) out.write("Content-Length: $cl\r\n".toByteArray())
                    out.write("Accept-Ranges: bytes\r\n".toByteArray())
                    out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                    out.write("Connection: close\r\n\r\n".toByteArray())

                    if (!method.equals("HEAD", true)) {
                        // Stream từng chunk 64KB
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = try { input.read(buf) } catch (_: Throwable) { -1 }
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                        out.flush()
                        Log.d(TAG, "streamed $total bytes (code=$code)")
                    } else {
                        out.flush()
                    }
                } else {
                    Log.w(TAG, "upstream $code for ${remoteUrl.take(80)}")
                    sendError(socket, code, "Upstream $code")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handle fail: ${e.message}")
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
                    // Rewrite URI="..." trong tag (KEY, MAP, MEDIA)
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
                else -> {
                    val abs = resolveUrl(baseUri, trimmed)
                    sb.append(proxyUrl(abs))
                }
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

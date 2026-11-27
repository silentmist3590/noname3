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
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
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
                    } catch (e: Exception) {
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
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
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
                Log.d(TAG, "Fetch: ${remoteUrl.take(100)}")

                val reqBuilder = Request.Builder().url(remoteUrl).get()
                defaultHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
                val response = httpClient.newCall(reqBuilder.build()).execute()
                val bytes = response.body?.bytes()
                val code = response.code

                if (bytes == null || code !in 200..299) {
                    Log.w(TAG, "Upstream $code for ${remoteUrl.take(80)}")
                    sendError(socket, if (code in 100..599) code else 502, "Upstream fail")
                    return
                }

                val contentType = response.header("Content-Type") ?: "application/octet-stream"
                val isM3u8 = remoteUrl.contains(".m3u8", true) ||
                             contentType.contains("mpegurl", true)
                val finalBytes = if (isM3u8) {
                    rewriteM3u8(String(bytes, Charsets.UTF_8), remoteUrl).toByteArray(Charsets.UTF_8)
                } else bytes

                val out = socket.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Content-Type: $contentType\r\n".toByteArray())
                out.write("Content-Length: ${finalBytes.size}\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                if (!requestLine.startsWith("HEAD")) out.write(finalBytes)
                out.flush()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handle fail: ${e.message}")
        }
    }

    private fun rewriteM3u8(content: String, baseUrl: String): String {
        val baseUri = try { URI(baseUrl) } catch (_: Exception) { null }
        return content.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> {
                    if (trimmed.contains("URI=\"")) {
                        trimmed.replace(Regex("""URI="([^"]+)"""")) { match ->
                            val abs = resolveUrl(baseUri, match.groupValues[1])
                            """URI="${proxyUrl(abs)}""""
                        }
                    } else line
                }
                else -> proxyUrl(resolveUrl(baseUri, trimmed))
            }
        }
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
            out.write("Content-Length: ${body.size}\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        } catch (_: Throwable) {}
    }
}

package com.movieuniverse

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Proxy cục bộ 127.0.0.1: trình phát chỉ nói chuyện với proxy này, proxy tự tải playlist/phân đoạn
 * (đúng header) và viết lại các URL trong playlist để mọi request đều đi qua proxy.
 */
object LocalProxy {
    @Volatile private var server: ServerSocket? = null
    @Volatile var ua: String = ""
    @Volatile var referer: String = ""
    @Volatile private var logged = 0

    private fun log(msg: String) {
        if (logged++ < 40) ClipboardLogger.logAndCopy("ProxyReq", msg)
    }

    fun port(): Int = synchronized(this) {
        server?.let { return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                } catch (_: Throwable) {
                }
            }
        }.apply { isDaemon = true }.start()
        s.localPort
    }

    fun wrap(url: String): String = "http://127.0.0.1:${port()}/p?u=" + URLEncoder.encode(url, "UTF-8")

    private fun abs(base: String, u: String): String =
        try { URI(base).resolve(u).toString() } catch (_: Throwable) { u }

    private fun rewrite(text: String, base: String): String =
        text.lines().joinToString("\n") { l ->
            when {
                l.isBlank() -> l
                l.startsWith("#") -> Regex("URI=\"([^\"]+)\"").replace(l) { m -> "URI=\"" + wrap(abs(base, m.groupValues[1])) + "\"" }
                else -> wrap(abs(base, l.trim()))
            }
        }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60_000
            val inp = BufferedReader(InputStreamReader(c.getInputStream()))
            val first = inp.readLine() ?: return
            var range: String? = null
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
                if (l.startsWith("Range:", true)) range = l.substring(6).trim()
            }
            val raw = first.split(" ").getOrNull(1) ?: return
            log("in: ${first.take(60)} range=$range")
            val out = c.getOutputStream()
            if (raw.startsWith("/ping")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                out.flush()
                return
            }
            val target = URLDecoder.decode(raw.substringAfter("u="), "UTF-8")
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) conn.setRequestProperty("Range", range)
            val code = conn.responseCode
            log("out: code=$code ct=${conn.contentType} len=${conn.contentLengthLong} url=${target.take(110)}")
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val ct = conn.contentType ?: "application/octet-stream"
            val finalUrl = conn.url.toString()
            val looksPl = ct.contains("mpegurl", true) || target.contains(".m3u8", true) || target.contains("_stream") || target.contains("cap.php")
            if (stream != null && looksPl && code < 400) {
                val bytes = stream.readBytes()
                val txt = String(bytes, Charsets.UTF_8)
                val body = if (txt.trimStart().startsWith("#EXTM3U")) rewrite(txt, finalUrl).toByteArray() else bytes
                out.write("HTTP/1.1 $code OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
                return
            }
            val sb = StringBuilder("HTTP/1.1 $code OK\r\nContent-Type: $ct\r\nAccept-Ranges: bytes\r\n")
            val len = conn.contentLengthLong
            if (len >= 0) sb.append("Content-Length: $len\r\n")
            conn.getHeaderField("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            if (stream != null) {
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            out.flush()
        } catch (e: Throwable) {
            log("ERR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }
}

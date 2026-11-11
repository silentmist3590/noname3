package com.movieuniverse

import java.io.BufferedReader
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
 * Proxy cục bộ 127.0.0.1. Trình phát chỉ nói chuyện với proxy này:
 *  - /play.m3u8?ref=..&t=..&e=..&o=.. : proxy tự xin link phát MỚI lúc bấm phát (link của site hết hạn rất nhanh),
 *    tải playlist, viết lại URL trong playlist để mọi request đều đi qua proxy.
 *  - /p.m3u8?u=.. (playlist) và /seg?u=.. (phân đoạn): tải với đúng header; phân đoạn bị ngụy trang
 *    (ảnh PNG...) được cắt phần đầu thừa để còn MPEG-TS chuẩn (byte 0x47).
 */
object LocalProxy {
    @Volatile private var server: ServerSocket? = null
    @Volatile var ua: String = ""
    @Volatile var referer: String = ""
    @Volatile private var logged = 0

    private fun log(msg: String) {
        if (logged++ < 60) ClipboardLogger.logAndCopy("ProxyReq", msg)
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun isPlaylistUrl(u: String) = u.contains(".m3u8", true) || u.contains("_stream") || u.contains("cap.php")

    fun wrap(url: String, playlist: Boolean = isPlaylistUrl(url)): String =
        "http://127.0.0.1:${port()}/" + (if (playlist) "p.m3u8" else "seg") + "?u=" + enc(url)

    /** Link "lười": proxy sẽ xin link mới khi trình phát thực sự mở nó. */
    fun lazy(ref: String, t: String, embed: String, origin: String): String =
        "http://127.0.0.1:${port()}/play.m3u8?ref=${enc(ref)}&t=${enc(t)}&e=${enc(embed)}&o=${enc(origin)}"

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

    private fun params(raw: String): Map<String, String> =
        raw.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    /** Tìm vị trí bắt đầu MPEG-TS thật (0x47 lặp mỗi 188 byte) trong 8KB đầu. */
    private fun tsOffset(b: ByteArray): Int {
        if (b.isNotEmpty() && b[0] == 0x47.toByte()) return 0
        val lim = minOf(b.size - 376, 8192)
        for (i in 0 until lim) {
            if (b[i] == 0x47.toByte() && b[i + 188] == 0x47.toByte() && b[i + 376] == 0x47.toByte()) return i
        }
        return -1
    }

    private fun mint(p: Map<String, String>): String? {
        val origin = p["o"] ?: return null
        for (attempt in 0..1) {
            try {
                val u = "$origin/api.php?a=play&ref=${enc(p["ref"] ?: "")}&t=${enc(p["t"] ?: "")}" + (if (attempt > 0) "&fresh=1" else "")
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Referer", p["e"] ?: "$origin/")
                c.setRequestProperty("Accept", "application/json, */*")
                val body = (if (c.responseCode >= 400) c.errorStream else c.inputStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                val m = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)
                if (m != null) return abs(p["e"] ?: origin, m.groupValues[1].replace("\\/", "/"))
                log("mint fail code=${c.responseCode} ${body.take(100)}")
            } catch (e: Throwable) {
                log("mint ERR ${e.message}")
            }
        }
        return null
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 60_000
            val inp = BufferedReader(InputStreamReader(c.getInputStream()))
            val first = inp.readLine() ?: return
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
            }
            val raw = first.split(" ").getOrNull(1) ?: return
            val out = c.getOutputStream()
            if (raw.startsWith("/ping")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                out.flush()
                return
            }
            val p = params(raw)
            val isPlay = raw.startsWith("/play")
            val target = (if (isPlay) mint(p) else p["u"]) ?: run {
                log("no target for ${raw.take(60)}")
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val ct = conn.contentType ?: "application/octet-stream"
            log("${if (isPlay) "play" else "get"}: code=$code ct=$ct len=${conn.contentLengthLong} url=${target.take(100)}")
            if (code >= 400 || stream == null) {
                out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val finalUrl = conn.url.toString()
            val isPl = raw.startsWith("/p.m3u8") || isPlay
            val bytes = stream.readBytes()
            if (isPl) {
                val txt = String(bytes, Charsets.UTF_8)
                val body = if (txt.trimStart().startsWith("#EXTM3U")) rewrite(txt, finalUrl).toByteArray() else bytes
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
                return
            }
            // Phân đoạn: cắt phần đầu ngụy trang để còn MPEG-TS chuẩn
            val off = tsOffset(bytes)
            val isMp4 = bytes.size > 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"
            val data = if (off > 0) bytes.copyOfRange(off, bytes.size) else bytes
            val type = if (off >= 0 && !isMp4) "video/mp2t" else ct
            if (off != 0) log("seg strip off=$off size=${bytes.size} -> ${data.size} type=$type")
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(data)
            out.flush()
        } catch (e: Throwable) {
            log("ERR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }
}

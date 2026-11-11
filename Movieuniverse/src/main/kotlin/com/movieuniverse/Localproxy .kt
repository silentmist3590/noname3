package com.movieuniverse

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
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

    /** Link "lười": proxy tự tải lại trang embed + xin link phát ngay khi trình phát mở (token của site hết hạn rất nhanh). */
    fun lazy(name: String, index: Int, embed: String, origin: String, site: String): String =
        "http://127.0.0.1:${port()}/play.m3u8?k=${enc(name)}&i=$index&e=${enc(embed)}&o=${enc(origin)}&m=${enc(site)}"

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

    private class R(val code: Int, val ct: String, val url: String, val bytes: ByteArray)

    /** Tải bằng chính OkHttp của CloudStream (cùng kiểu kết nối với app.get, site không chặn như HttpURLConnection). */
    private fun fetch(url: String, headers: Map<String, String>): R {
        val rb = okhttp3.Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        app.baseClient.newCall(rb.build()).execute().use { r ->
            return R(r.code, r.header("Content-Type") ?: "application/octet-stream", r.request.url.toString(), r.body?.bytes() ?: ByteArray(0))
        }
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private fun jsonAfter(html: String, marker: String): JSONObject? {
        val i = html.indexOf(marker)
        if (i < 0) return null
        val st = html.indexOf('{', i)
        if (st < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (k in st until html.length) {
            val c = html[k]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return try { JSONObject(html.substring(st, k + 1)) } catch (_: Throwable) { null } }
            }
        }
        return null
    }

    private fun mint(p: Map<String, String>): String? {
        val origin = p["o"] ?: return null
        val embed = p["e"] ?: return null
        val key = p["k"] ?: ""
        val ck = "$embed|$key"
        cache[ck]?.let { if (System.currentTimeMillis() - it.first < 15_000) return it.second }
        try {
            val er = fetch(embed, mapOf("User-Agent" to ua, "Referer" to (p["m"] ?: "$origin/")))
            val q = jsonAfter(String(er.bytes, Charsets.UTF_8), "var Q")
            if (q == null) { log("embed no Q code=${er.code}"); return null }
            val t = q.optString("t")
            var servers = q.optJSONObject("ssr")?.optJSONArray("servers")
            if (servers == null || servers.length() == 0) {
                val qs = "type=${q.optString("type")}&id=${enc(q.optString("id"))}&s=${q.optInt("s")}&e=${q.optInt("e")}&t=${enc(t)}"
                val sr = fetch("$origin/api.php?a=sources&$qs", mapOf("User-Agent" to ua, "Referer" to embed, "Accept" to "application/json, */*"))
                servers = try { JSONObject(String(sr.bytes, Charsets.UTF_8)).optJSONArray("servers") } catch (_: Throwable) { null }
            }
            var ref: String? = null
            if (servers != null) for (i in 0 until servers.length()) {
                val o = servers.optJSONObject(i) ?: continue
                if (key.isNotBlank() && o.optString("name") == key) { ref = o.optString("ref"); break }
            }
            if (ref == null && servers != null) {
                val idx = p["i"]?.toIntOrNull()
                if (idx != null && idx in 0 until servers.length()) ref = servers.optJSONObject(idx)?.optString("ref")
            }
            if (ref == null) { log("server not found: name=$key count=${servers?.length()} names=${(0 until (servers?.length() ?: 0)).joinToString(",") { servers!!.optJSONObject(it)?.optString("name").orEmpty() }}"); return null }
            for (attempt in 0..1) {
                val u = "$origin/api.php?a=play&ref=${enc(ref)}&t=${enc(t)}" + (if (attempt > 0) "&fresh=1" else "")
                val r = fetch(u, mapOf("User-Agent" to ua, "Referer" to embed, "Accept" to "application/json, */*"))
                val body = String(r.bytes, Charsets.UTF_8)
                val m = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)
                if (m != null) {
                    val res = abs(embed, m.groupValues[1].replace("\\/", "/"))
                    cache[ck] = System.currentTimeMillis() to res
                    return res
                }
                log("mint fail code=${r.code} ${body.take(100)}")
                if (r.code == 429) Thread.sleep(1500)
            }
        } catch (e: Throwable) {
            log("mint ERR ${e.message}")
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
            val r = fetch(target, mapOf("User-Agent" to ua, "Referer" to referer))
            val code = r.code
            val ct = r.ct
            log("${if (isPlay) "play" else "get"}: code=$code ct=$ct len=${r.bytes.size} url=${target.take(100)}")
            if (code >= 400) {
                out.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            val finalUrl = r.url
            val isPl = raw.startsWith("/p.m3u8") || isPlay
            val bytes = r.bytes
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

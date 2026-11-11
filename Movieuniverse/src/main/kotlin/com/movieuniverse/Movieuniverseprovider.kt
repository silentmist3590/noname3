package com.movieuniverse

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MovieuniverseProvider : MainAPI() {
    override var mainUrl = "https://movieuniverse.skin"
    override var name = "Movieuniverse"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val hdr get() = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "Trending",
        "$mainUrl/just-added" to "Just Added (Movies)",
        "$mainUrl/latest-episodes" to "Latest Episodes",
        "$mainUrl/latest" to "Latest Movies",
        "$mainUrl/tvshows" to "TV Shows",
        "$mainUrl/toprated" to "Top Rated Movies",
        "$mainUrl/tvtopps" to "Top TV Shows"
    )

    private suspend fun getRetry(url: String, headers: Map<String, String>, tries: Int = 3): com.lagradost.nicehttp.NiceResponse {
        var last: com.lagradost.nicehttp.NiceResponse? = null
        var err: Throwable? = null
        for (i in 1..tries) {
            try {
                val r = app.get(url, headers = headers, timeout = 30)
                if (r.code < 500) return r
                last = r
            } catch (e: Throwable) {
                err = e
            }
            kotlinx.coroutines.delay(800L * i)
        }
        return last ?: throw (err ?: Exception("GET failed: $url"))
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst("h6.movie-title a[href]") ?: selectFirst("a.movie-play[href]") ?: selectFirst("a[href*=watch-]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = (selectFirst("h6.movie-title a")?.text() ?: selectFirst("img")?.attr("alt") ?: "").trim()
        if (title.isBlank()) return null
        val img = selectFirst("div.movie-img img") ?: selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src")
        val year = selectFirst("span.movie-quality")?.text()?.trim()?.toIntOrNull()
        val isTv = href.contains("watch-tv-")
        return if (isTv) newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        } else newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val seen = HashSet<String>()
        return doc.select("div.movie-item").mapNotNull { it.toCard() }.filter { seen.add(it.url) }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val base = request.data
            val url = if (page <= 1) base else base + (if (base.contains("?")) "&" else "?") + "page=$page"
            val r = getRetry(url, hdr)
            val items = parseCards(r.text)
            ClipboardLogger.logAndCopy("MainPage", "url=$url code=${r.code} items=${items.size}\n" + (if (items.isEmpty()) r.text.take(500) else ""))
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && page < 50)
        } catch (e: Throwable) {
            ClipboardLogger.logAndCopy("MainPage Error", e.stackTraceToString().take(800))
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = ArrayList<SearchResponse>()
        for (cat in listOf("movie", "tv")) {
            try {
                out += parseCards(getRetry("$mainUrl/search?q=$q&category=$cat", hdr).text)
            } catch (e: Throwable) {
                ClipboardLogger.logAndCopy("Search Error", e.stackTraceToString().take(500))
            }
        }
        return out.distinctBy { it.url }
    }

    private fun cleanTitle(doc: org.jsoup.nodes.Document): String {
        val raw = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null } ?: doc.title()
        return raw.replace(Regex("^\\s*Watch\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*MovieUniverse.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+S\\d+E\\d+\\s*$"), "")
            .trim()
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val r = getRetry(url, hdr)
            val doc = Jsoup.parse(r.text, mainUrl)
            val title = cleanTitle(doc)
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?: doc.selectFirst("div.movie-img img, img[src*=tmdb]")?.attr("src")
            val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("a[href*=genre]").map { it.text() }.filter { it.isNotBlank() }.distinct()
            val isTv = url.contains("watch-tv-")
            if (!isTv) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                }
            }
            val id = Regex("watch-tv-(\\d+)").find(url)?.groupValues?.get(1) ?: return null
            val curSeason = Regex("season=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            fun seasonsOf(d: org.jsoup.nodes.Document): List<Int> =
                d.select("option").mapNotNull { Regex("^\\s*Season\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.get(1)?.toIntOrNull() }.distinct()
            fun episodesOf(d: org.jsoup.nodes.Document): List<Pair<Int, String>> =
                d.select("option").mapNotNull {
                    val m = Regex("^\\s*EP\\s+(\\d+)\\s*[—–-]?\\s*(.*)$").find(it.text()) ?: return@mapNotNull null
                    m.groupValues[1].toInt() to m.groupValues[2].trim()
                }.distinctBy { it.first }
            val seasons = seasonsOf(doc).ifEmpty { listOf(curSeason) }
            val eps = ArrayList<Episode>()
            val perSeason: Map<Int, List<Pair<Int, String>>> = coroutineScope {
                seasons.take(40).map { s ->
                    async {
                        try {
                            val d = if (s == curSeason) doc else Jsoup.parse(getRetry("$mainUrl/watch-tv-$id&season=$s&episode=1", hdr).text, mainUrl)
                            s to episodesOf(d)
                        } catch (_: Throwable) {
                            s to emptyList<Pair<Int, String>>()
                        }
                    }
                }.awaitAll().toMap()
            }
            for (s in seasons) {
                val list = perSeason[s].orEmpty().ifEmpty { listOf(1 to "Episode 1") }
                for ((e, n) in list) {
                    eps += newEpisode("$mainUrl/watch-tv-$id&season=$s&episode=$e") {
                        this.name = n.ifBlank { "Episode $e" }
                        this.season = s
                        this.episode = e
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } catch (e: Throwable) {
            ClipboardLogger.logAndCopy("Load Error", e.stackTraceToString().take(800))
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Link extraction: trang xem -> iframe player (videm.*) -> Q.ssr.servers -> api.php?a=play -> link
    // ---------------------------------------------------------------------------------------------
    private fun absUrl(base: String, u: String): String =
        try { URI(base).resolve(u.replace("\\/", "/")).toString() } catch (_: Throwable) { u }

    private fun jsonAfter(html: String, marker: String): JSONObject? {
        val i = html.indexOf(marker)
        if (i < 0) return null
        val s = html.indexOf('{', i)
        if (s < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (k in s until html.length) {
            val c = html[k]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return try { JSONObject(html.substring(s, k + 1)) } catch (_: Throwable) { null } }
            }
        }
        return null
    }

    /** Duyệt JSON, gom mọi chuỗi trông giống link stream / phụ đề. */
    private fun collect(o: Any?, key: String, out: MutableList<Pair<String, String>>) {
        when (o) {
            is JSONObject -> o.keys().forEach { k -> collect(o.opt(k), k, out) }
            is JSONArray -> for (i in 0 until o.length()) collect(o.opt(i), key, out)
            is String -> {
                val v = o.replace("\\/", "/")
                if (v.startsWith("http") || (v.startsWith("/") && v.length > 4)) out.add(key to v)
            }
        }
    }

    private suspend fun variantsOf(masterUrl: String, h: Map<String, String>): List<Pair<Int?, String>> {
        val mr = app.get(masterUrl, headers = h, timeout = 30)
        val lines = mr.text.lines()
        val out = ArrayList<Pair<Int?, String>>()
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull()
            out.add(height to absUrl(mr.url, uri))
        }
        return out
    }

    private var diagDone = false
    @Volatile private var proxyOk = false

    /** Chẩn đoán lỗi 2004: dùng link mới riêng, kiểm tra dùng-một-lần, Range, và nội dung playlist. */
    private suspend fun diag(masterUrl: String, origin: String) {
        val h = mapOf("User-Agent" to ua, "Referer" to "$origin/")
        try {
            val m1 = app.get(masterUrl, headers = h, timeout = 30)
            val m2 = app.get(masterUrl, headers = h, timeout = 30)
            ClipboardLogger.logAndCopy("DIAG master", "1st=${m1.code} 2nd=${m2.code} same=${m1.text == m2.text}\n${m1.text.take(500)}")
            val vline = m1.text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return
            val vu = absUrl(m1.url, vline.trim())
            val v1 = app.get(vu, headers = h, timeout = 30)
            val v2 = app.get(vu, headers = h, timeout = 30)
            ClipboardLogger.logAndCopy("DIAG variant", "1st=${v1.code} 2nd=${v2.code} len=${v1.text.length} ct=${v1.headers["content-type"]}\n${v1.text.take(1200)}")
            val seg = v1.text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: return
            val su = absUrl(v1.url, seg)
            for ((nm, hh) in listOf(
                "plain" to h,
                "range" to (h + ("Range" to "bytes=0-")),
                "exo" to (h + ("Accept-Encoding" to "identity") + ("Icy-MetaData" to "1"))
            )) {
                try {
                    val sr = app.get(su, headers = hh, timeout = 30)
                    ClipboardLogger.logAndCopy("DIAG seg $nm", "code=${sr.code} ct=${sr.headers["content-type"]} len=${sr.headers["content-length"]} cr=${sr.headers["content-range"]}")
                } catch (e: Throwable) {
                    ClipboardLogger.logAndCopy("DIAG seg $nm Error", e.message.orEmpty())
                }
            }
        } catch (e: Throwable) {
            ClipboardLogger.logAndCopy("DIAG Error", e.message.orEmpty())
        }
    }

    private suspend fun emitStream(url: String, label: String, embed: String, callback: (ExtractorLink) -> Unit, remint: (suspend () -> String?)? = null, lazyUrl: String? = null) {
        val origin = Regex("^https?://[^/]+").find(embed)?.value ?: mainUrl
        val isM3u8 = url.contains(".m3u8", true) || url.contains("/hls", true) || url.contains("playlist", true) || url.contains("_stream") || url.contains("cap.php")
        // Phân đoạn video cần Referer (một số nguồn), không gửi Origin
        val h = mapOf("User-Agent" to ua, "Referer" to "$origin/")
        fun px(u: String) = if (proxyOk) LocalProxy.wrap(u) else u
        if (false && isM3u8 && !diagDone && url.contains("_stream") && remint != null) {
            diagDone = true
            remint.invoke()?.let { diag(it, origin) }
        }
        if (isM3u8) {
            try {
                val mr = app.get(url, headers = h, timeout = 30)
                val txt = mr.text
                val hasAudio = txt.contains("#EXT-X-MEDIA:TYPE=AUDIO")
                if (!txt.contains("#EXTM3U")) {
                    ClipboardLogger.logAndCopy("Master Bad $label", "code=${mr.code}\n${txt.take(200)}")
                    return
                }
                if (proxyOk && lazyUrl != null) {
                    val height = Regex("RESOLUTION=\\d+x(\\d+)").findAll(txt).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                    callback(
                        newExtractorLink(name, "$label ${height?.let { "${it}p" } ?: ""}".trim(), lazyUrl, ExtractorLinkType.M3U8) {
                            this.referer = ""
                            this.quality = height ?: Qualities.Unknown.value
                            this.headers = emptyMap()
                        }
                    )
                    return
                }
                if (!hasAudio && txt.contains("#EXT-X-STREAM-INF")) {
                    val lines = txt.lines()
                    var n = 0
                    for (i in lines.indices) {
                        if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                        val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                        val height = Regex("RESOLUTION=\\d+x(\\d+)").find(lines[i])?.groupValues?.get(1)?.toIntOrNull()
                        callback(
                            newExtractorLink(name, "$label ${height?.let { "${it}p" } ?: ""}".trim(), px(absUrl(mr.url, uri)), ExtractorLinkType.M3U8) {
                                this.referer = if (proxyOk) "" else "$origin/"
                                this.quality = height ?: Qualities.Unknown.value
                                this.headers = if (proxyOk) emptyMap() else h
                            }
                        )
                        n++
                    }
                    if (n > 0) return
                }
                // Có nhiều track âm thanh: đưa nguyên playlist chính (link mới, chưa bị tải) cho trình phát
                val u2 = remint?.invoke() ?: url
                callback(
                    newExtractorLink(name, label, px(u2), ExtractorLinkType.M3U8) {
                        this.referer = if (proxyOk) "" else "$origin/"
                        this.quality = Qualities.Unknown.value
                        this.headers = if (proxyOk) emptyMap() else h
                    }
                )
                return
            } catch (e: Throwable) {
                ClipboardLogger.logAndCopy("Master Error $label", e.message.orEmpty())
            }
        }
        callback(
            newExtractorLink(name, label, url, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = h
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val seen = ArrayList<String>()
        var found = false
        try {
            ClipboardLogger.reset()
            diagDone = false
            val page = getRetry(data, hdr)
            val html = page.text.replace("\\/", "/")
            var embed = Regex("""(?:data-src|src)\s*=\s*["'](https?://[^"']*/embed/(?:movie|tv)/[^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""https?://[^"'\s<>]*/embed/(?:movie|tv)/[^"'\s<>]+""").find(html)?.value
            ClipboardLogger.logAndCopy("LoadLinks", "data=$data code=${page.code}\nembed=$embed")
            if (embed == null) {
                val ctx = html.indexOf("embed").let { if (it >= 0) html.substring(maxOf(0, it - 200), minOf(html.length, it + 400)) else html.take(400) }
                ClipboardLogger.logAndCopy("LoadLinks no embed", ctx)
            } else {
                embed = embed.replace("&amp;", "&")
                val emb = getRetry(embed, mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"))
                val ehtml = emb.text
                val q = jsonAfter(ehtml, "var Q") ?: jsonAfter(ehtml, "Q =")
                ClipboardLogger.logAndCopy("Embed", "code=${emb.code} Q=${q != null} cookies=${emb.cookies.keys}\n" + (if (q == null) ehtml.take(600) else ""))
                if (q != null) {
                    val eOrigin = Regex("^https?://[^/]+").find(embed)!!.value
                    LocalProxy.ua = ua
                    LocalProxy.referer = "$eOrigin/"
                    proxyOk = try {
                        app.get("http://127.0.0.1:${LocalProxy.port()}/ping", timeout = 5).text.trim() == "ok"
                    } catch (_: Throwable) { false }
                    ClipboardLogger.logAndCopy("Proxy", "ok=$proxyOk")
                    val t = q.optString("t")
                    val qs = "type=${q.optString("type")}&id=${URLEncoder.encode(q.optString("id"), "UTF-8")}&s=${q.optInt("s")}&e=${q.optInt("e")}&t=${URLEncoder.encode(t, "UTF-8")}"
                    val eh = mapOf("User-Agent" to ua, "Referer" to embed, "Accept" to "application/json, */*")
                    val ck = emb.cookies
                    // danh sách server
                    var servers: JSONArray? = q.optJSONObject("ssr")?.optJSONArray("servers")
                    var tries = 0
                    while ((servers == null || servers.length() == 0) && tries < 24) {
                        tries++
                        try {
                            val r = app.get("$eOrigin/api.php?a=sources&$qs", headers = eh, cookies = ck, timeout = 30)
                            if (tries <= 2 || tries % 6 == 0 || r.text.contains("\"ok\"")) ClipboardLogger.logAndCopy("Sources#$tries", "code=${r.code}\n${r.text.take(500)}")
                            val j = JSONObject(r.text)
                            servers = j.optJSONArray("servers")
                        } catch (e: Throwable) {
                            ClipboardLogger.logAndCopy("Sources Error", e.message.orEmpty())
                        }
                        if (servers == null || servers.length() == 0) kotlinx.coroutines.delay(3000)
                    }
                    ClipboardLogger.logAndCopy("Servers", "count=${servers?.length() ?: 0}")
                    // phụ đề
                    try {
                        val r = app.get("$eOrigin/api.php?a=subs&$qs", headers = eh, cookies = ck, timeout = 30)
                        ClipboardLogger.logAndCopy("Subs", "code=${r.code}\n${r.text.take(600)}")
                        val arr = JSONObject(r.text).optJSONArray("subs")
                        if (arr != null) for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val u = o.optString("url").ifBlank { null }?.let { absUrl(embed, it) }
                                ?: o.optString("ref").ifBlank { null }?.let { "$eOrigin/api.php?a=sub&ref=" + URLEncoder.encode(it, "UTF-8") }
                                ?: continue
                            val label = o.optString("label").ifBlank { o.optString("lang").ifBlank { "Subtitle ${i + 1}" } }
                            subtitleCallback(SubtitleFile(label, u))
                        }
                    } catch (e: Throwable) {
                        ClipboardLogger.logAndCopy("Subs Error", e.message.orEmpty())
                    }
                    // từng server
                    if (servers != null) for (i in 0 until servers.length()) {
                        val s = servers.optJSONObject(i) ?: continue
                        val ref = s.optString("ref")
                        val sname = s.optString("name").ifBlank { "Server ${i + 1}" }
                        val lg = s.optString("lang").takeIf { it.isNotBlank() && it != "null" }
                        val label = if (lg != null) "$sname [$lg]" else sname
                        try {
                            suspend fun mint(fresh: Boolean): String {
                                val r = app.get(
                                    "$eOrigin/api.php?a=play&ref=" + URLEncoder.encode(ref, "UTF-8") + "&t=" + URLEncoder.encode(t, "UTF-8") + (if (fresh) "&fresh=1" else ""),
                                    headers = eh, cookies = ck, timeout = 30
                                )
                                ClipboardLogger.logAndCopy("Play $sname fresh=$fresh", "code=${r.code}\n${r.text.take(500)}")
                                return r.text
                            }
                            val body = mint(false)
                            val j = try { JSONObject(body) } catch (_: Throwable) { null }
                            val urls = ArrayList<Pair<String, String>>()
                            if (j != null) collect(j, "", urls) else if (body.trim().startsWith("http")) urls.add("" to body.trim())
                            val done = HashSet<String>()
                            for ((k, v) in urls) {
                                val u = absUrl(embed, v)
                                val kl = k.lowercase()
                                val isSub = kl.contains("sub") || kl.contains("track") || kl.contains("caption") || Regex("""\.(vtt|srt)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(u)
                                if (isSub) {
                                    subtitleCallback(SubtitleFile("$sname sub", u))
                                    continue
                                }
                                val isMedia = Regex("""\.(m3u8|mp4|mkv|webm|mpd)(\?|$|#)""", RegexOption.IGNORE_CASE).containsMatchIn(u) ||
                                    kl in setOf("url", "file", "src", "stream", "hls", "link", "source", "playlist")
                                if (isMedia && done.add(u)) {
                                    emitStream(u, label, embed, callback, {
                                        try {
                                            val rr = app.get("$eOrigin/api.php?a=play&ref=" + URLEncoder.encode(ref, "UTF-8") + "&t=" + URLEncoder.encode(t, "UTF-8") + "&fresh=1", headers = eh, cookies = ck, timeout = 30)
                                            JSONObject(rr.text).optString("url").replace("\\/", "/").takeIf { it.isNotBlank() }?.let { absUrl(embed, it) }
                                        } catch (_: Throwable) { null }
                                    }, if (proxyOk) LocalProxy.lazy(ref, t, embed, eOrigin) else null)
                                    found = true
                                }
                            }
                        } catch (e: Throwable) {
                            ClipboardLogger.logAndCopy("Play Error $sname", e.stackTraceToString().take(500))
                        }
                    }
                }
            }
            if (!found) {
                val hit = sniffStream(data, "$mainUrl/", ua, seen, Regex("""\.m3u8|\.mpd|\.mp4(\?|$)""", RegexOption.IGNORE_CASE), 60_000L)
                ClipboardLogger.logAndCopy("Sniff", "hit=$hit\nseen=\n" + seen.joinToString("\n"))
                if (hit != null) {
                    val origin = Regex("^https?://[^/]+").find(hit)?.value ?: mainUrl
                    callback(
                        newExtractorLink(name, "$name WebView", hit, if (hit.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to ua, "Origin" to origin, "Referer" to "$mainUrl/")
                        }
                    )
                    found = true
                }
            }
        } catch (e: Throwable) {
            ClipboardLogger.logAndCopy("LoadLinks Error", e.stackTraceToString().take(900))
        }
        return found
    }

    // =====================================================================================
    // WebView riêng: tải trang player, chặn mọi popup, gửi chạm THẬT (MotionEvent) vào giữa màn hình để bấm Play
    // và bắt URL .m3u8/.mpd đầu tiên mà trang gọi.
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>, streamRegex: Regex, timeoutMs: Long = 45_000L): String? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val appCtx: Context? = ClipboardLogger.appContext()
                val act = CommonActivity.activity
                val ctx: Context? = act ?: appCtx
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff", "Không có Context để tạo WebView")
                    cont.resume(null)
                } else try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    fun finish(res: String?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {
                        }
                        cont.resume(res)
                    }
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    // Popup quảng cáo: không mở cửa sổ mới
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    }
                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play]'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();"
                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(fakePopup + clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {
                        }
                        if (taps < 25) handler.postDelayed({ tap() }, 1500)
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!junkRegex.containsMatchIn(u) && seen.size < 80) seen.add(u.take(220))
                            if (streamRegex.containsMatchIn(u)) {
                                handler.post { finish(u) }
                            }
                            return null
                        }

                        // chặn mọi chuyển hướng sang trang quảng cáo
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val ok = u.startsWith(pageUrl.substringBefore("?")) || u.startsWith(Regex("^https?://[^/]+").find(pageUrl)?.value ?: pageUrl)
                            if (!ok) seen.add("BLOCKED NAV: " + u.take(160))
                            return !ok
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
                        }
                    }
                    // Gắn vào cửa sổ (ngoài màn hình) để WebView nhận chạm và chạy như bình thường
                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) {
                    }
                    handler.postDelayed({ finish(null) }, timeoutMs)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }


}

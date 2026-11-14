package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.movieuniverse.MovieuniverseProvider
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MoviesProProvider : MainAPI() {
    override var mainUrl = "https://moviespro.top"
    override var name = "MoviesPro"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/category/movies/" to "Movies",
        "/category/tv-series/" to "TV Series",
        "/top-imdb" to "Top IMDb",
        "/category/action/" to "Action",
        "/category/comedy/" to "Comedy",
        "/category/horror/" to "Horror",
        "/category/animation/" to "Animation",
        "/category/science-fiction/" to "Science Fiction",
        "/category/drama/" to "Drama"
    )

    private val ua = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private val hdr get() = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
    private val mu by lazy { MovieuniverseProvider() }

    private suspend fun document(url: String): org.jsoup.nodes.Document {
        MoviesProClipboardLogger.stage("HTTP/GET", url)
        val r = app.get(url, headers = hdr)
        MoviesProClipboardLogger.stage("HTTP/RESULT", "status=${r.code}; final=${r.url}; bytes=${r.text.length}")
        if (r.code !in 200..299) {
            MoviesProClipboardLogger.stage("HTTP/BODY", r.text.replace(Regex("\\s+"), " ").take(500))
            throw IllegalStateException("HTTP ${r.code} @ $url")
        }
        return Jsoup.parse(r.text, r.url.toString())
    }

    private fun Element.toCard(): SearchResponse? {
        val a = selectFirst(".poster a[href]") ?: selectFirst("a[href]") ?: return null
        val href = a.absUrl("href")
        if (!href.startsWith(mainUrl) || href.contains("/category/") || href.contains("/country/")) return null
        val img = selectFirst("img")
        val title = (selectFirst(".meta > a")?.text() ?: img?.attr("alt") ?: selectFirst(".title, .name")?.text() ?: a.attr("title")).orEmpty().trim()
        if (title.isBlank()) return null
        val poster = img?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }?.takeIf { it.startsWith("http") }
        val type = selectFirst(".type")?.text().orEmpty()
        val tv = type.isNotBlank() && !type.equals("Movie", true)
        return if (tv) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select("div.filmlist div.item, .filmlist .item").mapNotNull { it.toCard() }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        MoviesProClipboardLogger.reset("home ${request.name} p=$page")
        return try {
            val base = request.data.trimEnd('/')
            val url = if (page <= 1) "$mainUrl$base/" else "$mainUrl$base/page/$page/"
            val items = cards(document(url))
            MoviesProClipboardLogger.stage("HOME/PARSE", "section=${request.name}; cards=${items.size}")
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Throwable) {
            MoviesProClipboardLogger.error("getMainPage", request.name, e as? Exception ?: Exception(e))
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        MoviesProClipboardLogger.reset("search: $query")
        return try {
            val r = cards(document("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"))
            MoviesProClipboardLogger.stage("SEARCH/PARSE", "results=${r.size}")
            r
        } catch (e: Throwable) {
            MoviesProClipboardLogger.error("search", query, e as? Exception ?: Exception(e)); emptyList()
        }
    }

    /** Lấy biến JS (Servers / Episodes) – nằm thẳng trong HTML hoặc mã hoá base64 trong data-URI. */
    private fun jsVar(html: String, name: String): JSONObject? {
        val texts = ArrayList<String>()
        texts.add(html.replace("\\/", "/"))
        Regex("""base64,([A-Za-z0-9+/=]{20,})""").findAll(html).forEach {
            try { texts.add(String(Base64.decode(it.groupValues[1], Base64.DEFAULT)).replace("\\/", "/")) } catch (_: Throwable) {}
        }
        for (t in texts) {
            val i = t.indexOf("var $name")
            if (i < 0) continue
            val s = t.indexOf('{', i)
            if (s < 0) continue
            var d = 0; var str = false; var esc = false
            for (k in s until t.length) {
                val c = t[k]
                if (str) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') str = false }
                else when (c) {
                    '"' -> str = true
                    '{' -> d++
                    '}' -> { d--; if (d == 0) return try { JSONObject(t.substring(s, k + 1)) } catch (_: Throwable) { null } }
                }
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        MoviesProClipboardLogger.reset("load: $url")
        return try {
            val r = app.get(url, headers = hdr)
            val html = r.text
            val doc = Jsoup.parse(html, url)
            val title = doc.selectFirst("h1.name, h1")?.text()?.trim().orEmpty().ifBlank { doc.title() }
            val poster = doc.selectFirst("#w-info .poster img")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }?.takeIf { it.startsWith("http") }
            val plot = doc.selectFirst(".description")?.text()?.trim()
            val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.selectFirst("#w-info .info")?.text().orEmpty())?.value?.toIntOrNull()
            val tags = doc.select("#w-info a[href*=/category/]").map { it.text() }.distinct()
            val ep = jsVar(html, "Episodes")
            val sv = jsVar(html, "Servers")
            MoviesProClipboardLogger.stage("DETAIL/PARSE", "title=$title; tv=${ep != null}; tmdb=${sv?.optString("id")}; tvid=${ep?.optString("tvid")}")
            if (ep != null) {
                val tvid = ep.optString("tvid")
                val key = ep.optString("tvapikey")
                val seasons = doc.select("a.season-item[data-tab]").mapNotNull { Regex("season(\\d+)").find(it.attr("data-tab"))?.groupValues?.get(1)?.toIntOrNull() }
                    .filter { it > 0 }.ifEmpty { listOf(1) }
                val eps = ArrayList<Episode>()
                for (s in seasons) {
                    try {
                        val j = JSONObject(app.get("https://api.themoviedb.org/3/tv/$tvid/season/$s?api_key=$key&language=en-US", headers = hdr).text)
                        val arr = j.optJSONArray("episodes") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val e = arr.getJSONObject(i)
                            val n = e.optInt("episode_number")
                            eps.add(newEpisode("mu:$tvid|$s|$n|${ep.optString("tvimdbid")}") {
                                this.name = e.optString("name").ifBlank { "Episode $n" }
                                this.season = s
                                this.episode = n
                                this.description = e.optString("overview").ifBlank { null }
                                this.posterUrl = e.optString("still_path").takeIf { it.isNotBlank() && it != "null" }?.let { p -> "https://image.tmdb.org/t/p/w500$p" }
                            })
                        }
                    } catch (t: Throwable) {
                        MoviesProClipboardLogger.error("load/season", "s=$s", t as? Exception ?: Exception(t))
                    }
                }
                MoviesProClipboardLogger.stage("DETAIL/EPS", "episodes=${eps.size}")
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags }
            } else {
                val tmdb = sv?.optString("id").orEmpty()
                newMovieLoadResponse(title, url, TvType.Movie, if (tmdb.isNotBlank()) "mu:movie|$tmdb|${sv?.optString("imdb_id").orEmpty()}" else url) {
                    posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
                }
            }
        } catch (e: Throwable) {
            MoviesProClipboardLogger.error("load", url, e as? Exception ?: Exception(e)); null
        }
    }

    private val PLAYER = "https://ghostplayer.store/wp-content/plugins/fmovie-core/player/player.php"

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        MoviesProClipboardLogger.reset("loadLinks: $data")
        return try {
            var d = data
            if (!d.startsWith("mu:")) {
                val sv = jsVar(app.get(d, headers = hdr).text, "Servers")
                val tmdb = sv?.optString("id").orEmpty()
                if (tmdb.isBlank()) { MoviesProClipboardLogger.stage("LINKS/ERR", "không tìm thấy tmdb id"); return false }
                d = "mu:movie|$tmdb|${sv?.optString("imdb_id").orEmpty()}"
            }
            val p = d.removePrefix("mu:").split("|")
            val isMovie = p[0] == "movie"
            val imdb = (if (isMovie) p.getOrNull(2) else p.getOrNull(3)).orEmpty()
            var n = 0

            // ===== Nguồn chính: Filemoon (player.php nhúng trong moviespro.top) =====
            if (imdb.isNotBlank()) {
                val playerUrl = if (isMovie) "$PLAYER?video_id=$imdb" else "$PLAYER?video_id=$imdb&s=${p[1]}&e=${p[2]}"
                val res = FilemoonSource.run(playerUrl, ua)
                if (res != null) {
                    val done = HashSet<String>()
                    for ((nm, urls) in res.servers) {
                        for (u in urls) {
                            if (!done.add(u)) continue
                            try {
                                if (u.contains(".m3u8", true) || u.contains(".mp4", true)) {
                                    n++
                                    callback(newExtractorLink(name, "Filemoon $nm", u, if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                        referer = "https://streamingnow.mov/"
                                        headers = mapOf("User-Agent" to ua, "Referer" to "https://streamingnow.mov/")
                                    })
                                } else if (u.startsWith("http")) {
                                    val before = n
                                    loadExtractor(u, "https://streamingnow.mov/", subtitleCallback) { l -> n++; callback(l) }
                                    MoviesProClipboardLogger.stage("Extractor $nm", "${u.take(110)} -> ${n - before} link")
                                }
                            } catch (e: Throwable) {
                                if (e is java.util.concurrent.CancellationException) throw e
                            }
                        }
                    }
                }
                MoviesProClipboardLogger.stage("Filemoon/KẾT QUẢ", "tổng $n link")
            }

            // ===== Dự phòng: Movieuniverse =====
            if (n == 0) {
                val target = if (isMovie) "https://movieuniverse.skin/watch-movieuniverse-${p[1]}"
                else "https://movieuniverse.skin/watch-tv-${p[0]}&season=${p[1]}&episode=${p[2]}"
                MoviesProClipboardLogger.stage("LINKS/FALLBACK", target)
                if (mu.loadLinks(target, isCasting, subtitleCallback, callback)) n++
            }
            n > 0
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            MoviesProClipboardLogger.error("loadLinks", data, e as? Exception ?: Exception(e)); false
        }
    }
}

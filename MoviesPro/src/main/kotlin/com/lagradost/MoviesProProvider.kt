package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class MoviesProProvider : MainAPI() {
    override var mainUrl = "https://moviespro.top"
    override var name = "MoviesPro"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/?type=movie" to "Movies",
        "/?type=tv" to "TV Series"
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private fun absolute(url: String?): String? = url?.takeIf { it.isNotBlank() && it != "#" }?.let {
        try { URI(mainUrl).resolve(it).toString() } catch (_: Exception) { null }
    }
    private suspend fun document(
        url: String
    ): org.jsoup.nodes.Document {
        MoviesProClipboardLogger.stage("HTTP/GET", url)

        val response = app.get(
            url,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer" to "$mainUrl/"
            )
        )

        MoviesProClipboardLogger.stage(
            "HTTP/RESULT",
            "status=${response.code}; final=${response.url}; bytes=${response.text.length}"
        )

        if (response.code !in 200..299) {
            throw IllegalStateException("HTTP ${response.code} @ $url")
        }

        return Jsoup.parse(response.text, response.url.toString())
    }

    private fun cards(doc: org.jsoup.nodes.Document): List<Element> {
        val selectors = listOf("article", ".movie-item", ".item", ".poster", ".film-item")
        for (selector in selectors) {
            val found = doc.select(selector).filter { it.selectFirst("a[href]") != null }
            if (found.size >= 2) return found
        }
        return doc.select("a[href]").filter { a ->
            val href = a.absUrl("href")
            href.contains(mainUrl.removePrefix("https://")) && a.selectFirst("img") != null
        }
    }

    private fun toSearch(element: Element): SearchResponse? {
        val a = element.selectFirst("a[href]") ?: return null
        val href = a.absUrl("href").ifBlank { absolute(a.attr("href")) ?: return null }
        if (!href.startsWith(mainUrl)) return null
        val img = element.selectFirst("img") ?: a.selectFirst("img")
        val title = (element.selectFirst(".title, .name, h2, h3")?.text()
            ?: a.attr("title").ifBlank { img?.attr("alt") ?: a.text() }).trim()
        if (title.isBlank()) return null
        val poster = img?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") }.ifBlank { it.absUrl("data-lazy-src") } }
        val tv = href.contains("/tv", true) || element.text().contains("episode", true) || element.text().contains("season", true)
        return newMovieSearchResponse(title, href, if (tv) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster?.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        MoviesProClipboardLogger.reset("home page ${request.name}, page=$page")
        return try {
            val path = request.data.trimEnd('/')
            val url = if (page <= 1) "$mainUrl${if (path.isEmpty()) "/" else path}" else "$mainUrl${if (path.isEmpty()) "" else path}/page/$page/"
            val doc = document(url)
            val items = cards(doc).mapNotNull(::toSearch).distinctBy { it.url }
            MoviesProClipboardLogger.stage("HOME/PARSE", "section=${request.name}; cards=${items.size}; title=${doc.title()}")
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            MoviesProClipboardLogger.error("getMainPage", "${request.name} page=$page", e)
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        MoviesProClipboardLogger.reset("search: $query")
        return try {
            val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}" 
            val doc = document(url)
            val result: List<SearchResponse> = cards(doc)
                .mapNotNull { element -> toSearch(element) }
                .distinctBy { item -> item.url }
            MoviesProClipboardLogger.stage("SEARCH/PARSE", "query=$query; results=${result.size}")
            result
        } catch (e: Exception) {
            MoviesProClipboardLogger.error("search", query, e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        MoviesProClipboardLogger.reset("load detail: $url")
        return try {
            val doc = document(url)
            val title = doc.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank { doc.title() }
            val poster = doc.selectFirst(".poster img, .thumbnail img, .cover img, img.wp-post-image")?.let { it.absUrl("data-src").ifBlank { it.absUrl("src") } }
            val plot = doc.selectFirst(".description, .plot, .overview, .entry-content")?.text()?.trim()
            val year = Regex("\\b(19|20)\\d{2}\\b").find(doc.selectFirst(".info, .meta, .entry-content")?.text().orEmpty())?.value?.toIntOrNull()
            val isTv = url.contains("/tv", true) || doc.text().contains("Episodes", true) || doc.select(".episode, .episodes a").isNotEmpty()
            MoviesProClipboardLogger.stage("DETAIL/PARSE", "title=$title; tv=$isTv; poster=${poster?.isNotBlank()}; episodes=${doc.select(".episode a, .episodes a").size}")
            if (isTv) {
                val eps: List<Episode> = doc
                    .select(".episode a[href], .episodes a[href], a[href*='episode']")
                    .mapNotNull { a ->
                        val epUrl = a.absUrl("href")
                        if (epUrl.isBlank()) return@mapNotNull null

                        newEpisode(epUrl) {
                            name = a.text().trim().ifBlank { "Episode" }
                            posterUrl = poster
                        }
                    }
                    .distinctBy { it.data }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) { this.posterUrl = poster; this.plot = plot; this.year = year }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) { this.posterUrl = poster; this.plot = plot; this.year = year }
            }
        } catch (e: Exception) {
            MoviesProClipboardLogger.error("load/detail", url, e); null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        MoviesProClipboardLogger.reset("loadLinks: $data")
        MoviesProClipboardLogger.stage("LINKS/START", "data=$data; casting=$isCasting")
        var emitted = 0
        val seen = linkedSetOf<String>()
        try {
            val doc = document(data)
            // Captures exposed player URLs and source links; does not defeat DRM or access controls.
            val frames = doc.select("iframe[src], iframe[data-src], video source[src], video[src], a[href]")
            MoviesProClipboardLogger.stage("LINKS/DOM", "candidate nodes=${frames.size}")
            for (node in frames) {
                val raw = node.attr("src").ifBlank { node.attr("data-src") }.ifBlank { node.attr("href") }
                val candidate = absolute(raw) ?: node.absUrl("src").ifBlank { node.absUrl("href") }
                if (candidate.isBlank() || !candidate.startsWith("http") || !seen.add(candidate)) continue
                val label = node.attr("title").ifBlank { node.text().trim() }.ifBlank { "Source ${seen.size}" }
                MoviesProClipboardLogger.stage("LINKS/CANDIDATE", "$label -> $candidate")
                try {
                    if (
                        candidate.contains(".m3u8", true) ||
                        candidate.contains(".mpd", true) ||
                        candidate.contains(".mp4", true)
                    ) {
                        val kind = when {
                            candidate.contains(".mpd", true) ->
                                ExtractorLinkType.DASH

                            candidate.contains(".m3u8", true) ->
                                ExtractorLinkType.M3U8

                            else ->
                                ExtractorLinkType.VIDEO
                        }

                        callback.invoke(
                            newExtractorLink(
                                name,
                                label,
                                candidate,
                                kind
                            ) {
                                referer = data
                            }
                       )

                       emitted++

                       MoviesProClipboardLogger.stage(
                           "LINKS/DIRECT",
                           "emitted $kind: $candidate"
                       )
                   } else {
                       val before = emitted

                       loadExtractor(
                           candidate,
                           data,
                           subtitleCallback
                       ) { link: ExtractorLink ->
                           callback.invoke(link)
                           emitted++

                           MoviesProClipboardLogger.stage(
                               "EXTRACTOR/VIDEO",
                               "${link.name}; quality=${link.quality}; type=${link.type}; url=${link.url}"
                           )
                       }

                       MoviesProClipboardLogger.stage(
                           "EXTRACTOR/RESULT",
                           "candidate=$candidate; callbacks=${emitted-before}"
                       )
                    }
                } catch (e: Exception) {
                    MoviesProClipboardLogger.error("candidate/extractor", candidate, e)
                }
            }
            // Public subtitle tracks visible in HTML.
            doc.select("track[src], a[href$=.vtt], a[href$=.srt]").forEach { track ->
                val sub = absolute(track.attr("src").ifBlank { track.attr("href") }) ?: return@forEach
                val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Unknown" }
                subtitleCallback.invoke(SubtitleFile(lang, sub))
                MoviesProClipboardLogger.stage("SUBTITLE", "$lang -> $sub")
            }
            MoviesProClipboardLogger.stage("LINKS/END", "unique candidates=${seen.size}; video links emitted=$emitted")
        } catch (e: Exception) {
            MoviesProClipboardLogger.error("loadLinks", data, e)
        }
        return emitted > 0
    }
}

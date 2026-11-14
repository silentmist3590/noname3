package com.movieshdhub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class MoviesHDHub : MainAPI() {

    override var mainUrl = "https://movieshdhub.com"
    override var name = "MoviesHDHub"
    override var lang = "en"
    override val hasMainPage = true
    override var hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false
    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured & Latest",
        "$mainUrl/movies/" to "All Movies",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/adventure/" to "Adventure",
        "$mainUrl/genre/animation/" to "Animation",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/drama/" to "Drama",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/science-fiction/" to "Sci-Fi",
        "$mainUrl/genre/thriller/" to "Thriller"
    )

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun headers(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to UA,
        "Referer" to referer,
        "Origin" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun streamHeaders(referer: String): Map<String, String> =
        headers(referer) + mapOf("Referer" to referer)

    // =====================================================================
    // HOME
    // =====================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.reset()
        val base = request.data.ifBlank { mainUrl }
        val url = if (page <= 1) base else "$mainUrl/page/$page/"
        ClipboardLogger.logAndCopy("HOME", "page=$page url=$url")

        val doc = app.get(url, headers = headers(url)).document
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
            .ifEmpty {
                doc.select(".search-page .result-item article, .result-item")
                    .mapNotNull { it.toSearchResult() }
            }
        ClipboardLogger.logAndCopy("HOME", "parsed ${items.size}")
        return newHomePageResponse(request.name, items, false)
    }

    // =====================================================================
    // SEARCH
    // =====================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        ClipboardLogger.logAndCopy("SEARCH", "query=$query")
        val url = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = try {
            app.get(url, headers = headers(url)).document
        } catch (t: Throwable) {
            ClipboardLogger.logAndCopy("SEARCH", "html fail: ${t.message}")
            return emptyList()
        }
        val items = doc.select("article.item, .search-page .result-item article")
            .mapNotNull { it.toSearchResult() }
        ClipboardLogger.logAndCopy("SEARCH", "html returned ${items.size}")
        return items
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst(".details .title a")
            ?: selectFirst(".data h3 a")
            ?: selectFirst("h3 a")
            ?: return null
        val title = a.text().trim().ifBlank { return null }
        val href = fixUrl(a.attr("href"))
        if (href.contains("/episodes/") || href.contains("/seasons/")) return null

        val poster = (selectFirst(".thumbnail img")?.attr("src")
            ?: selectFirst(".poster img")?.attr("src")
            ?: selectFirst(".image img")?.attr("src")
            ?: selectFirst("img")?.attr("src"))
            ?.replace(Regex("/(w92|w154|w185|w300|w342|w780)/"), "/w500/")
            ?.takeIf { it.startsWith("http") }
            ?.let { fixUrl(it) }

        val typeClass = selectFirst(".search-type")?.attr("class").orEmpty()
        val isTv = typeClass.contains("tv-type")
                || typeClass.contains("season-type")
                || typeClass.contains("episode-type")
                || hasClass("tvshows")
                || href.contains("/tvshows/")

        val year = selectFirst(".meta .year a")?.text()?.toIntOrNull()
            ?: selectFirst(".meta .year")?.text()?.toIntOrNull()

        return if (isTv) newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = year
        } else newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
        }
    }

    // =====================================================================
    // LOAD
    // =====================================================================
    override suspend fun load(url: String): LoadResponse {
        ClipboardLogger.logAndCopy("LOAD", "url=$url")
        val doc = app.get(url, headers = headers(url)).document

        val rawTitle = doc.selectFirst(".sheader .data h1")?.text()?.trim().orEmpty()
        val title = rawTitle.replace(Regex("[-\\s]+\\(?(19|20)\\d{2}\\)?$"), "").trim()

        val poster = (doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".sheader .poster img")?.attr("src"))
            ?.replace("w185", "w500")
            ?.let { fixUrl(it) }

        val plot = doc.selectFirst("#info .wp-content p")?.text()?.trim().orEmpty()

        val dateText = doc.selectFirst(".sheader .data .extra .date")?.text().orEmpty()
        val year = Regex("(19|20)\\d{2}").find(dateText)?.value?.toIntOrNull()

        val duration = doc.selectFirst(".sheader .data .extra .runtime")?.text()
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val tags = doc.select(".sgeneros a").map { it.text().trim() }

        val isSeries = url.contains("/tvshows/")
                || doc.selectFirst("#seasons, .single-tvshows") != null
                || doc.selectFirst(".tvshows .se-c") != null

        ClipboardLogger.logAndCopy(
            "LOAD", "title=$title | year=$year | dur=$duration | series=$isSeries"
        )

        return if (isSeries) {
            val eps = parseEpisodes(doc, url)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = duration
            }
        }
    }

    private fun parseEpisodes(doc: Document, baseUrl: String): List<Episode> {
        val list = mutableListOf<Episode>()
        val seasonBlocks = doc.select("#seasons .se-c")

        if (seasonBlocks.isNotEmpty()) {
            seasonBlocks.forEachIndexed { sIdx, seC ->
                val seasonNum = seC.selectFirst(".se-q .se-t, .se-q")?.text()
                    ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    ?: (sIdx + 1)
                seC.select(".se-a .episodios li").forEachIndexed { eIdx, li ->
                    val a = li.selectFirst(".episodiotitle a, a") ?: return@forEachIndexed
                    val epHref = fixUrl(a.attr("href"))
                    if (epHref.isBlank()) return@forEachIndexed
                    val epNum = li.selectFirst(".numerando")?.text()?.let {
                        Regex("(\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toIntOrNull()
                    } ?: (eIdx + 1)
                    list.add(newEpisode(epHref) {
                        this.name = a.text().trim().ifBlank { "Episode $epNum" }
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
        } else {
            list.add(newEpisode(baseUrl) {
                this.name = "Full"
                this.season = 1
                this.episode = 1
            })
        }
        return list
    }

    // =====================================================================
    // LOAD LINKS
    // =====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("=== NEW PLAY ===", "data=$data")
        val doc = app.get(data, headers = headers(data)).document

        val postId = doc.selectFirst("meta#dooplay-ajax-counter")?.attr("data-postid").orEmpty()
            .ifBlank { doc.selectFirst("[data-postid]")?.attr("data-postid").orEmpty() }
            .ifBlank {
                Regex("/movies?/([^/]+)/?$").find(data)?.groupValues?.get(1).orEmpty()
            }

        val servers = doc.select("li.dooplay_player_option")
            .filter {
                it.attr("data-nume").isNotEmpty() && it.attr("data-nume") != "trailer"
            }

        ClipboardLogger.logAndCopy("LINKS", "postId=$postId | servers=${servers.size}")
        if (servers.isEmpty()) return false

        var emitted = false
        for (li in servers) {
            val nume = li.attr("data-nume")
            val type = li.attr("data-type").ifBlank { "movie" }
            val label = li.selectFirst("span.title")?.text()?.trim() ?: "Server $nume"
            try {
                val embed = fetchEmbedUrl(postId, nume, type, data)
                if (embed.isNullOrBlank()) {
                    ClipboardLogger.logAndCopy("SERVER-$nume", "no embed url")
                    continue
                }
                ClipboardLogger.logAndCopy("SERVER-$nume", "embed=$embed")

                val sniffed = WebViewSniffer.sniff(
                    url = embed,
                    referer = data,
                    headers = headers(embed),
                    timeoutMs = 60_000L
                )
                ClipboardLogger.logAndCopy("SERVER-$nume", "sniffed=${sniffed.size}")

                // Xử lý phát link
                for (s in sniffed) {
                    if (!s.url.contains(".m3u8", true) && !s.url.contains(".mp4", true)) {
                        continue
                    }

                    // Fix cứng Referer cho các domain đặc biệt để tránh lỗi 403
                    val domainRef = when {
                        s.url.contains("juniperandjade.space") -> "https://juniperandjade.space/"
                        s.url.contains("vaplayer") -> "https://streamdata.vaplayer.ru/"
                        else -> s.referer
                    }
                    val hdrs: Map<String, String> = streamHeaders(domainRef)
                    
                    ClipboardLogger.logAndCopy("EMIT", "url=${s.url}")

                    if (s.url.contains(".m3u8", true)) {
                        // 1. Ưu tiên phát link trực tiếp để ExoPlayer tự xử lý (tránh bị treo do M3u8Helper)
                        try {
                            callback(newExtractorLink(
                                source = "$name [$label]",
                                name = "$name [$label] • HLS",
                                url = s.url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = domainRef
                                this.headers = hdrs
                            })
                            emitted = true
                        } catch (t: Throwable) {
                            ClipboardLogger.logAndCopy("DIRECT-FAIL", "${t.message}")
                        }

                        // 2. Chạy M3u8Helper để bóc tách đa chất lượng (nếu thành công)
                        try {
                            val generatedLinks = M3u8Helper.generateM3u8(
                                source = "$name [$label]",
                                streamUrl = s.url,
                                referer = domainRef,
                                headers = hdrs
                            )
                            ClipboardLogger.logAndCopy("M3U8-RES", "found ${generatedLinks.size} links")
                            if (generatedLinks.isNotEmpty()) {
                                generatedLinks.forEach {
                                    callback(it)
                                    emitted = true
                                }
                            }
                        } catch (t: Throwable) {
                            ClipboardLogger.logAndCopy("M3U8-FAIL", "${t.message}")
                        }
                        
                    } else if (s.url.contains(".mp4", true)) {
                        try {
                            callback(newExtractorLink(
                                source = "$name [$label]",
                                name = "$name [$label] • MP4",
                                url = s.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = domainRef
                                this.headers = hdrs
                            })
                            emitted = true
                        } catch (t: Throwable) {
                            ClipboardLogger.logAndCopy("MP4-FAIL", "${t.message}")
                        }
                    }
                }
            } catch (t: Throwable) {
                ClipboardLogger.logAndCopy("SERVER-$nume", "FAIL ${t.message}")
            }
            if (emitted) break
        }

        if (!emitted) ClipboardLogger.logAndCopy("LINKS", "NO PLAYABLE SOURCE")
        return emitted
    }

    // =====================================================================
    // FETCH EMBED URL
    // =====================================================================
    private suspend fun fetchEmbedUrl(postId: String, nume: String, type: String, referer: String): String? {
        try {
            val formData = mapOf(
                "action" to "doo_player_ajax",
                "post" to postId,
                "nume" to nume,
                "type" to type
            )
            val reqHeaders = headers(referer).toMutableMap()
            reqHeaders["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            reqHeaders["X-Requested-With"] = "XMLHttpRequest"

            val resp = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = reqHeaders,
                data = formData
            ).text
            
            ClipboardLogger.logAndCopy("AJAX-$nume", resp)
            val json = JSONObject(resp)
            val embed = json.optString("embed_url")
            if (embed.isNotBlank()) return embed
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("AJAX-$nume", "fail: ${e.message}")
        }
        return null
    }
}

package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

class VidZenProvider : MainAPI() {

    override var mainUrl = "https://vidzen.fun"
    override var name = "VidZen"
    override var lang = "en"
    override val hasMainPage = true
    override var hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    private val tmdbApi = "https://tmdb-proxy.silentmist3590.workers.dev/3"
    private val tmdbKey = "c3d997893bf55c01f39f821b53781547"

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/all/week?api_key=$tmdbKey" to "Trending",
        "$tmdbApi/movie/popular?api_key=$tmdbKey" to "Popular Movies",
        "$tmdbApi/tv/popular?api_key=$tmdbKey" to "Popular TV"
    )

    private fun headers() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.reset()
        val url = if (page > 1) "${request.data}&page=$page" else request.data
        val res = app.get(url, headers = headers()).parsedSafe<TmdbResponse>()
        val items = res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        ClipboardLogger.reset()
        val url = "$tmdbApi/search/multi?api_key=$tmdbKey&query=${URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(url, headers = headers()).parsedSafe<TmdbResponse>()
        return res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()
    }

    private fun TmdbItem.toSearchResult(): SearchResponse? {
        val tmdbId = id ?: return null
        val title = title ?: name ?: return null
        val type = mediaType ?: if (title != null) "movie" else "tv"
        val isTv = type == "tv"

        val poster = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

        return if (isTv) {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$tmdbId", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$tmdbId", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val tmdbId = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2) ?: return null
        val type = if (isTv) "tv" else "movie"

        val metaUrl = "$tmdbApi/$type/$tmdbId?api_key=$tmdbKey"
        val meta = app.get(metaUrl, headers = headers()).parsedSafe<TmdbDetail>()

        val title = meta?.title ?: meta?.name ?: "Unknown"
        val poster = meta?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val plot = meta?.overview
        val year = (meta?.releaseDate ?: meta?.firstAirDate)?.take(4)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        if (isTv) {
            val seasonUrl = "$tmdbApi/tv/$tmdbId/season/1?api_key=$tmdbKey"
            val season = app.get(seasonUrl, headers = headers()).parsedSafe<TmdbSeason>()
            season?.episodes?.forEach { ep ->
                val epNum = ep.episodeNumber ?: return@forEach
                episodes.add(newEpisode("$mainUrl/tv/$tmdbId/1/$epNum") {
                    this.name = "Tập $epNum"
                    this.season = 1
                    this.episode = epNum
                })
            }
        }

        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("=== PLAY ===", "data=$data")

        val tmdbId = Regex("""/(movie|tv)/(\d+)""").find(data)?.groupValues?.get(2)
        if (tmdbId != null) {
            SubtitleFetcher.fetchSubtitles(tmdbId, null, null, subtitleCallback)
        }

        // Sniff URL từ WebView
        val sniffed = VidZenSniffer.sniff(
            url = data,
            referer = "$mainUrl/",
            timeoutMs = 45_000L,
            waitForResultsMs = 5_000L
        )

        ClipboardLogger.logAndCopy("SNIFF", "Bắt được ${sniffed.size} URL")

        // Lấy tối đa 3 link tốt nhất
        val candidates = sniffed
            .filter {
                it.url.contains(".m3u8", true) ||
                it.url.contains(".mp4", true) ||
                it.url.contains("/api/stream/", true) ||
                it.url.contains("v1_", true)
            }
            .filterNot { it.url.contains(".js", true) }
            .distinctBy { it.url }
            .take(3)

        if (candidates.isEmpty()) {
            ClipboardLogger.logAndCopy("LINKS", "❌ Không có ứng viên nào")
            return false
        }

        var emitted = false
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        for (hit in candidates) {
    ClipboardLogger.logAndCopy("HIT", "[${hit.source}] ${hit.url.take(100)}... | cookie.len=${hit.cookies.length}")

    try {
        callback(
            newExtractorLink(
                source = name,
                name = "$name • Auto",
                url = hit.url,
                type = if (hit.url.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = hit.referer
                val headerMap = mutableMapOf(
                    "Referer" to hit.referer,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
                if (hit.cookies.isNotBlank()) {
                    headerMap["Cookie"] = hit.cookies
                }
                this.headers = headerMap
            }
        )
        emitted = true
        ClipboardLogger.logAndCopy("EMIT", "✓ Emit thành công (cookie.len=${hit.cookies.length})")
        break
    } catch (t: Throwable) {
        ClipboardLogger.logAndCopy("EMIT-FAIL", "${t.message?.take(80)}")
    }
}

        if (!emitted) ClipboardLogger.logAndCopy("LINKS", "❌ Không emit được link nào")
        return emitted
    }

    // TMDB Data Models
    data class TmdbResponse(val results: List<TmdbItem>?)
    data class TmdbItem(
        val id: Int?, val title: String?, val name: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("media_type") val mediaType: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("poster_path") val posterPath: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("release_date") val releaseDate: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("first_air_date") val firstAirDate: String?
    )
    data class TmdbDetail(
        val id: Int?, val title: String?, val name: String?, val overview: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("poster_path") val posterPath: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("release_date") val releaseDate: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("first_air_date") val firstAirDate: String?
    )
    data class TmdbSeason(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(
        @com.fasterxml.jackson.annotation.JsonProperty("episode_number") val episodeNumber: Int?
    )
}

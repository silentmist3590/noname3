package com.vidzen

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

    // Sử dụng TMDB Proxy có sẵn (bạn có thể thay bằng proxy của bạn)
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

    // =====================================================================
    // HOME PAGE (Dùng TMDB)
    // =====================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.reset()
        val url = if (page > 1) "${request.data}&page=$page" else request.data
        ClipboardLogger.logAndCopy("HOME", "page=$page | $url")

        val res = app.get(url, headers = headers()).parsedSafe<TmdbResponse>()
        val items = res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()

        ClipboardLogger.logAndCopy("HOME", "→ ${items.size} items")
        return newHomePageResponse(request.name, items, false)
    }

    // =====================================================================
    // SEARCH (Dùng TMDB)
    // =====================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        ClipboardLogger.reset()
        val url = "$tmdbApi/search/multi?api_key=$tmdbKey&query=${URLEncoder.encode(query, "UTF-8")}"
        ClipboardLogger.logAndCopy("SEARCH", "query=$query")

        val res = app.get(url, headers = headers()).parsedSafe<TmdbResponse>()
        val items = res?.results?.mapNotNull { it.toSearchResult() } ?: emptyList()

        ClipboardLogger.logAndCopy("SEARCH", "→ ${items.size} results")
        return items
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

    // =====================================================================
    // LOAD
    // =====================================================================
    override suspend fun load(url: String): LoadResponse? {
        ClipboardLogger.logAndCopy("LOAD", "url=$url")

        // URL có dạng: https://vidzen.fun/movie/{tmdbId} hoặc /tv/{tmdbId}
        val isTv = url.contains("/tv/")
        val tmdbId = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)

        if (tmdbId == null) {
            ClipboardLogger.logAndCopy("LOAD", "✗ Không lấy được TMDB ID")
            return null
        }

        // Lấy metadata từ TMDB
        val type = if (isTv) "tv" else "movie"
        val metaUrl = "$tmdbApi/$type/$tmdbId?api_key=$tmdbKey&append_to_response=external_ids"
        val meta = app.get(metaUrl, headers = headers()).parsedSafe<TmdbDetail>()

        val title = meta?.title ?: meta?.name ?: "Unknown"
        val poster = meta?.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val plot = meta?.overview
        val year = (meta?.releaseDate ?: meta?.firstAirDate)?.take(4)?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        if (isTv) {
            // Lấy danh sách mùa và tập
            val seasonUrl = "$tmdbApi/tv/$tmdbId/season/1?api_key=$tmdbKey"
            val season = app.get(seasonUrl, headers = headers()).parsedSafe<TmdbSeason>()
            season?.episodes?.forEach { ep ->
                val epNum = ep.episodeNumber ?: return@forEach
                val epUrl = "$mainUrl/tv/$tmdbId/1/$epNum"
                episodes.add(newEpisode(epUrl) {
                    this.name = "Tập $epNum"
                    this.season = 1
                    this.episode = epNum
                })
            }
        }

        ClipboardLogger.logAndCopy("LOAD", "✓ $title | TMDB=$tmdbId | Số tập=${episodes.size}")

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

    // =====================================================================
    // LOAD LINKS (Sniff stream + Phụ đề)
    // =====================================================================
        // =====================================================================
    // LOAD LINKS (Sniff stream + Phụ đề)
    // =====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.logAndCopy("=== PLAY ===", "data=$data")

        // 1. Lấy TMDB ID từ URL
        val tmdbId = Regex("""/(movie|tv)/(\d+)""").find(data)?.groupValues?.get(2)
        val type = if (data.contains("/tv/")) "tv" else "movie"

        // 2. Tự động tải phụ đề
        if (tmdbId != null) {
            SubtitleFetcher.fetchSubtitles(tmdbId, null, null, subtitleCallback)
        }

        // 3. Mở WebView để sniff stream
        val sniffed = VidZenSniffer.sniff(
            url = data,
            referer = "$mainUrl/",
            timeoutMs = 60_000L,
            waitForResultsMs = 10_000L
        )

        ClipboardLogger.logAndCopy("SNIFF", "Bắt được ${sniffed.size} URL")

        var emitted = false
        for (hit in sniffed) {
            // Bỏ qua các file JS/CSS không liên quan
            if (hit.url.contains("cdn.jsdelivr.net") || hit.url.contains(".js", true)) continue

            // Chấp nhận: có đuôi .m3u8, .mp4, hoặc link API stream của VidZen
            val isMedia = hit.url.contains(".m3u8", true) ||
                          hit.url.contains(".mp4", true) ||
                          hit.url.contains("/api/stream/", true) ||
                          hit.url.contains("v1_", true)

            if (!isMedia) continue

            ClipboardLogger.logAndCopy("HIT", "[${hit.source}] ${hit.url.take(200)}")

            try {
                // Ép qua M3u8Helper cho mọi link (kể cả link API không có đuôi)
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = hit.url,
                    referer = hit.referer,
                    headers = mapOf("Referer" to hit.referer, "Origin" to mainUrl)
                )

                if (links.isNotEmpty()) {
                    links.forEach { callback(it); emitted = true }
                    ClipboardLogger.logAndCopy("M3U8", "✓ ${links.size} chất lượng từ ${hit.url.take(50)}...")
                } else if (hit.url.contains(".mp4", true)) {
                    // Fallback cho MP4
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name • MP4",
                            url = hit.url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = hit.referer
                            this.headers = mapOf("Referer" to hit.referer, "Origin" to mainUrl)
                        }
                    )
                    emitted = true
                    ClipboardLogger.logAndCopy("MP4", "✓")
                }
            } catch (t: Throwable) {
                ClipboardLogger.logAndCopy("FAIL", "✗ ${hit.url.take(50)}: ${t.message?.take(80)}")
            }
        }

        if (!emitted) {
            ClipboardLogger.logAndCopy("LINKS", "❌ Không có stream nào phát được")
        }
        return emitted
    }

    // =====================================================================
    // TMDB Data Models
    // =====================================================================
    data class TmdbResponse(val results: List<TmdbItem>?)
    data class TmdbItem(
        val id: Int?,
        val title: String?,
        val name: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("media_type") val mediaType: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("poster_path") val posterPath: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("release_date") val releaseDate: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("first_air_date") val firstAirDate: String?
    )

    data class TmdbDetail(
        val id: Int?,
        val title: String?,
        val name: String?,
        val overview: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("poster_path") val posterPath: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("release_date") val releaseDate: String?,
        @com.fasterxml.jackson.annotation.JsonProperty("first_air_date") val firstAirDate: String?
    )

    data class TmdbSeason(val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(
        @com.fasterxml.jackson.annotation.JsonProperty("episode_number") val episodeNumber: Int?
    )
}

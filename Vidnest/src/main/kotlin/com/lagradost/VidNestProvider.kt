package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class VidNestProvider : MainAPI() {
    override var mainUrl = "https://vidnext.pro"
    override var name = "VidNest"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val playerKey =
        "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

    // TMDB Proxy + Fallback Hosts chống chặn DNS
    private val tmdbHosts = listOf(
        "https://tmdb-proxy.silentmist3590.workers.dev/3",
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0

    private val tmdbKeys = listOf(
        "c3d997893bf55c01f39f821b53781547",
        "439c478a771f35c05022f9feabcca01c",
        "8476a7ab80ad76f0936744df0430e67c"
    )

    private val tmdbBearerToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"

    private val posterCache = ConcurrentHashMap<String, String>()
    private val imdbCache = ConcurrentHashMap<Int, String>()

    override val mainPage = mainPageOf(
        "/api/discover/views?type=movie&views=week" to "Trending Movies",
        "/api/discover/views?type=tv&views=week" to "Trending TV Shows",
        "/api/discover/movies?ordering=created_at&direction=desc" to "Latest Movies",
        "/api/discover/tv?ordering=last_upload_date&direction=desc" to "Latest TV Shows",
        "/api/discover/movies?slider=1&ordering=views_month&direction=desc" to "Featured Movies",
        "/api/discover/movies?ordering=views&direction=desc" to "Top Viewed Movies",
        "/api/discover/tv?ordering=views&direction=desc" to "Top Viewed TV Shows"
    )

    // =========================================================================
    // Helpers & TMDB Enrichment
    // =========================================================================

    private fun apiHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "x-player-key" to playerKey,
        "Accept" to "application/json"
    )

    private fun playbackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun proxyImage(path: String?, size: String = "w500"): String {
        if (path.isNullOrBlank() || path == "null") return ""
        val clean = if (path.startsWith("/")) path else "/$path"
        val tmdbImg = "https://image.tmdb.org/t/p/$size$clean"
        return "https://wsrv.nl/?url=${URLEncoder.encode(tmdbImg, "UTF-8")}&w=${size.removePrefix("w")}&output=webp"
    }

    private fun parseYear(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        return Regex("^(\\d{4})").find(date)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]

            for (apiKey in tmdbKeys) {
                val url = "$host$path${sep}api_key=$apiKey"
                val res = runCatching {
                    app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Accept" to "application/json"
                        ),
                        timeout = 10L
                    ).text
                }.getOrNull()

                if (!res.isNullOrBlank()) {
                    val json = runCatching { JSONObject(res) }.getOrNull()
                    if (json != null && !json.has("status_code")) {
                        tmdbPrefer = hi
                        return json
                    }
                    if (json?.optInt("status_code") == 34) return null
                }
            }

            val bearerRes = runCatching {
                app.get(
                    "$host$path",
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Authorization" to "Bearer $tmdbBearerToken",
                        "Accept" to "application/json"
                    ),
                    timeout = 10L
                ).text
            }.getOrNull()

            if (!bearerRes.isNullOrBlank()) {
                val json = runCatching { JSONObject(bearerRes) }.getOrNull()
                if (json != null && !json.has("status_code")) {
                    tmdbPrefer = hi
                    return json
                }
            }
        }
        return null
    }

    private suspend fun resolvePoster(mediaType: String, tmdbId: Int): String {
        val cacheKey = "$mediaType:$tmdbId"
        posterCache[cacheKey]?.let { return it }

        val details = tmdbGet("/$mediaType/$tmdbId") ?: return ""
        val posterPath = details.optString("poster_path", "")
        val url = proxyImage(posterPath, "w500")
        if (url.isNotEmpty()) {
            posterCache[cacheKey] = url
        }
        return url
    }

    private data class CatalogEntry(
        val type: String,
        val tmdbId: Int,
        val title: String,
        val year: Int?,
        val quality: String,
        val imdbId: String
    )

    private fun parseCatalogArray(arr: JSONArray, defaultType: String): List<CatalogEntry> {
        val list = mutableListOf<CatalogEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val tmdbStr = obj.optString("tmdbid", "").trim()
            val tmdbId = tmdbStr.toIntOrNull() ?: obj.optInt("tmdbid", 0)
            if (tmdbId <= 0) continue

            val rawTitle = obj.optString("title", "").ifBlank { obj.optString("orig_title", "") }
            val title = rawTitle.replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) continue

            val rawType = obj.optString("type", defaultType).lowercase()
            val mediaType = if (rawType == "tv" || rawType == "series") "tv" else "movie"
            val year = obj.optInt("year", 0).takeIf { it > 1900 }
            val quality = obj.optString("quality", "").ifBlank { obj.optString("Quality", "") }.trim()
            val imdbId = obj.optString("imdb_id", "").trim()
            if (imdbId.startsWith("tt")) {
                imdbCache[tmdbId] = imdbId
            }

            list.add(
                CatalogEntry(
                    type = mediaType,
                    tmdbId = tmdbId,
                    title = title,
                    year = year,
                    quality = quality,
                    imdbId = imdbId
                )
            )
        }
        return list
    }

    private suspend fun enrichCatalogItems(entries: List<CatalogEntry>): List<SearchResponse> {
        return entries.amap { entry ->
            val poster = resolvePoster(entry.type, entry.tmdbId)
            val dataUrl = "vidnest:${entry.type}:${entry.tmdbId}"
            if (entry.type == "tv") {
                newTvSeriesSearchResponse(entry.title, dataUrl, TvType.TvSeries) {
                    this.posterUrl = poster.ifBlank { null }
                    this.year = entry.year
                }
            } else {
                newMovieSearchResponse(entry.title, dataUrl, TvType.Movie) {
                    this.posterUrl = poster.ifBlank { null }
                    this.year = entry.year
                }
            }
        }
    }

    private fun tmdbObjToSearchResponse(obj: JSONObject): SearchResponse? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null
        val mediaType = obj.optString("media_type")
        if (mediaType.isNotBlank() && mediaType != "movie" && mediaType != "tv") return null
        val isTv = if (mediaType.isNotBlank()) mediaType == "tv" else (obj.has("first_air_date") || obj.has("name"))
        val type = if (isTv) "tv" else "movie"

        val title = obj.optString(if (isTv) "name" else "title", "").trim()
        if (title.isBlank()) return null

        val posterPath = obj.optString("poster_path", "")
        val poster = proxyImage(posterPath, "w500")
        if (poster.isNotEmpty()) {
            posterCache["$type:$id"] = poster
        }
        val year = parseYear(obj.optString(if (isTv) "first_air_date" else "release_date", ""))
        val dataUrl = "vidnest:$type:$id"

        return if (isTv) {
            newTvSeriesSearchResponse(title, dataUrl, TvType.TvSeries) {
                this.posterUrl = poster.ifBlank { null }
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, dataUrl, TvType.Movie) {
                this.posterUrl = poster.ifBlank { null }
                this.year = year
            }
        }
    }

    // =========================================================================
    // Catalog API: MainPage & Search
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${sep}page=$page&resultsPerPage=20"
        val defaultType = if (request.data.contains("tv")) "tv" else "movie"

        val body = runCatching {
            app.get(
                url,
                headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                timeout = 12L
            ).text
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val dataArr = json.optJSONArray("data") ?: JSONArray()
        val entries = parseCatalogArray(dataArr, defaultType)
        val items = enrichCatalogItems(entries)
        val currentPage = json.optInt("current_page", page)
        val lastPage = json.optInt("last_page", currentPage)

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = currentPage < lastPage
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        // API /api/discover của VidNest yêu cầu query tối thiểu 3 ký tự
        if (q.length >= 3) {
            val catalogEntries = listOf("movies" to "movie", "tv" to "tv").amap { (endpoint, type) ->
                runCatching {
                    val url = "$mainUrl/api/discover/$endpoint?query=$encoded&resultsPerPage=20"
                    val body = app.get(
                        url,
                        headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                        timeout = 10L
                    ).text
                    val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                    parseCatalogArray(arr, type)
                }.getOrDefault(emptyList())
            }.flatten()

            if (catalogEntries.isNotEmpty()) {
                return enrichCatalogItems(catalogEntries.distinctBy { "${it.type}:${it.tmdbId}" })
            }
        }

        // Fallback về TMDB khi từ khóa < 3 ký tự hoặc Catalog không trả kết quả
        val tmdbJson = tmdbGet("/search/multi?query=$encoded&include_adult=false&page=1")
            ?: return emptyList()
        val results = tmdbJson.optJSONArray("results") ?: JSONArray()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            tmdbObjToSearchResponse(obj)?.let { out.add(it) }
        }
        return out
    }

    // =========================================================================
    // Load Details (Movie / TV Series) - Tương thích CloudStream 4.5.2 (No rating/score)
    // =========================================================================

    private data class MediaRef(val type: String, val tmdbId: Int, val season: Int?, val episode: Int?)

    private fun parseRef(input: String): MediaRef? {
        val vidnestRegex = Regex("""vidnest:(movie|tv):(\d+)(?::(\d+):(\d+))?""")
        vidnestRegex.find(input)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return MediaRef(
                type = m.groupValues[1],
                tmdbId = id,
                season = m.groupValues[3].toIntOrNull(),
                episode = m.groupValues[4].toIntOrNull()
            )
        }

        val urlRegex = Regex("""/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""")
        urlRegex.find(input)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return MediaRef(
                type = m.groupValues[1],
                tmdbId = id,
                season = m.groupValues[3].toIntOrNull(),
                episode = m.groupValues[4].toIntOrNull()
            )
        }

        if (input.trim().startsWith("{")) {
            return runCatching {
                val j = JSONObject(input)
                val id = j.optInt("id", j.optString("tmdbid", "").toIntOrNull() ?: 0)
                if (id <= 0) null
                else MediaRef(
                    type = if (j.optString("type") == "tv") "tv" else "movie",
                    tmdbId = id,
                    season = j.optInt("s", 0).takeIf { it > 0 },
                    episode = j.optInt("e", 0).takeIf { it > 0 }
                )
            }.getOrNull()
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val tmdbId = ref.tmdbId
        val isTv = ref.type == "tv"

        val json = tmdbGet("/${ref.type}/$tmdbId?append_to_response=external_ids")
            ?: throw ErrorLoadingException("Không tải được thông tin phim TMDB ID: $tmdbId")

        val title = json.optString(if (isTv) "name" else "title", "Unknown").trim()
        val poster = proxyImage(json.optString("poster_path", ""), "w500").ifBlank { null }
        val backdrop = proxyImage(json.optString("backdrop_path", ""), "w780").ifBlank { null }
        val plot = json.optString("overview", "")
        val year = parseYear(json.optString(if (isTv) "first_air_date" else "release_date", ""))
        val genres = mutableListOf<String>()
        val gArr = json.optJSONArray("genres")
        if (gArr != null) {
            for (i in 0 until gArr.length()) {
                gArr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }

        val imdbId = json.optString("imdb_id", "").ifBlank {
            json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
        }
        if (imdbId.startsWith("tt")) {
            imdbCache[tmdbId] = imdbId
        }

        val canonical = "vidnest:${ref.type}:$tmdbId"

        if (!isTv) {
            val runtime = json.optInt("runtime", 0).takeIf { it > 0 }
            return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.duration = runtime
                this.tags = genres
            }
        } else {
            val showStatus = if (json.optString("status").equals("Ended", ignoreCase = true)) {
                ShowStatus.Completed
            } else {
                ShowStatus.Ongoing
            }

            val seasonsJson = json.optJSONArray("seasons") ?: JSONArray()
            val seasonNums = mutableListOf<Int>()
            for (i in 0 until seasonsJson.length()) {
                val s = seasonsJson.optJSONObject(i) ?: continue
                val sn = s.optInt("season_number", -1)
                val ec = s.optInt("episode_count", 0)
                if (sn > 0 && ec > 0) seasonNums.add(sn)
            }
            seasonNums.sort()

            val episodes = seasonNums.amap { sNum ->
                val list = mutableListOf<Episode>()
                runCatching {
                    val sJson = tmdbGet("/tv/$tmdbId/season/$sNum")
                    val epArray = sJson?.optJSONArray("episodes") ?: JSONArray()
                    for (ei in 0 until epArray.length()) {
                        val ep = epArray.optJSONObject(ei) ?: continue
                        val epNum = ep.optInt("episode_number", ei + 1)
                        val epName = ep.optString("name", "Episode $epNum")
                        val still = proxyImage(ep.optString("still_path", ""), "w300").ifBlank { null }
                        val epPlot = ep.optString("overview", "")
                        list.add(
                            newEpisode("vidnest:tv:$tmdbId:$sNum:$epNum") {
                                this.name = epName
                                this.season = sNum
                                this.episode = epNum
                                this.posterUrl = still
                                this.description = epPlot
                            }
                        )
                    }
                }
                list
            }.flatten()

            return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.showStatus = showStatus
                this.tags = genres
            }
        }
    }

    // =========================================================================
    // Stream Extraction + Pre-Flight Probe + Subtitles
    // =========================================================================

    private data class ServerDef(val id: String, val label: String, val query: String)

    private val servers = listOf(
        ServerDef("vidora", "Nova", ""),
        ServerDef("f3xr", "Zenith", "?source=f3xr"),
        ServerDef("q7mv", "Comet", "?source=q7mv"),
        ServerDef("j5wq", "Raven", "?source=j5wq")
    )

    private data class ResolvedSub(val label: String, val url: String)
    private data class ResolvedStream(
        val serverLabel: String,
        val finalUrl: String,
        val linkType: ExtractorLinkType,
        val quality: Int
    )
    private data class ServerFetchResult(
        val logLine: String,
        val streams: List<ResolvedStream>,
        val subs: List<ResolvedSub>
    )

    private suspend fun probeStreamType(
        rawUrl: String,
        headers: Map<String, String>
    ): Pair<ExtractorLinkType?, Int> {
        return runCatching {
            val resp = app.get(rawUrl, headers = headers, timeout = 6L)
            if (resp.code !in 200..299) {
                return Pair(null, resp.code)
            }
            val snippet = resp.text.take(1500).trimStart()
            val detected = when {
                snippet.contains("#EXTM3U") -> ExtractorLinkType.M3U8
                snippet.contains("<MPD", ignoreCase = true) -> ExtractorLinkType.DASH
                snippet.startsWith("<!doctype", ignoreCase = true) ||
                    snippet.startsWith("<html", ignoreCase = true) ||
                    snippet.startsWith("{") -> null
                rawUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                rawUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                rawUrl.contains(".mp4", ignoreCase = true) ||
                    rawUrl.contains(".mkv", ignoreCase = true) -> ExtractorLinkType.VIDEO
                else -> null
            }
            Pair(detected, resp.code)
        }.getOrElse { Pair(null, 0) }
    }

    private fun normalizeSubUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed
        else "$mainUrl/api/stream/${trimmed.removePrefix("/")}"
    }

    private fun extractTracks(arr: JSONArray?): List<ResolvedSub> {
        if (arr == null) return emptyList()
        val subs = mutableListOf<ResolvedSub>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val rawSrc = t.optString("src", "").ifBlank { t.optString("file", "") }
            if (rawSrc.isBlank()) continue
            val fullUrl = normalizeSubUrl(rawSrc)
            val label = t.optString("languageName", "")
                .ifBlank { t.optString("label", "") }
                .ifBlank { t.optString("language", "Subtitle") }
                .trim()
            subs.add(ResolvedSub(label, fullUrl))
        }
        return subs
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "VidNest loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Không phân tích được data: $data")
            return false
        }

        val streamPath = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "tv/${ref.tmdbId}/$s/$e"
        } else {
            "movie/${ref.tmdbId}"
        }

        val reqHeaders = apiHeaders()
        val playHeaders = playbackHeaders()

        // 1. Tải phụ đề từ endpoint chuyên biệt của VidNest
        val dedicatedSubs = runCatching {
            val subUrl = "$mainUrl/api/stream/v1/subtitles/$streamPath"
            val resp = app.get(subUrl, headers = reqHeaders, timeout = 8L)
            if (resp.code in 200..299) {
                val json = JSONObject(resp.text)
                extractTracks(json.optJSONArray("tracks"))
            } else {
                ClipboardLogger.logAndCopy("Subtitles", "HTTP ${resp.code}")
                emptyList()
            }
        }.getOrDefault(emptyList())

        // 2. Truy vấn đồng thời 4 server (Nova, Zenith, Comet, Raven) + Pre-flight probe
        val serverResults = servers.amap { srv ->
            val apiUrl = "$mainUrl/api/stream/v1/$streamPath${srv.query}"
            val logSb = StringBuilder()
            val validStreams = mutableListOf<ResolvedStream>()
            val extraSubs = mutableListOf<ResolvedSub>()

            runCatching {
                val resp = app.get(apiUrl, headers = reqHeaders, timeout = 10L)
                if (resp.code !in 200..299) {
                    logSb.append("${srv.label} (${srv.id}) -> API HTTP ${resp.code}")
                    return@runCatching
                }

                val json = JSONObject(resp.text)
                if (!json.optBoolean("result", false)) {
                    logSb.append("${srv.label} (${srv.id}) -> result=false")
                    return@runCatching
                }

                val sources = json.optJSONArray("sources") ?: JSONArray()
                if (sources.length() == 0) {
                    logSb.append("${srv.label} (${srv.id}) -> 0 sources")
                    return@runCatching
                }

                for (i in 0 until sources.length()) {
                    val srcObj = sources.optJSONObject(i) ?: continue
                    extraSubs.addAll(extractTracks(srcObj.optJSONArray("tracks")))

                    val rawStreamUrl = srcObj.optString("url", "").trim()
                    if (!rawStreamUrl.startsWith("http")) continue

                    val (linkType, probeCode) = probeStreamType(rawStreamUrl, playHeaders)
                    if (linkType == null) {
                        logSb.append("${srv.label} (${srv.id}) -> DEAD HTTP $probeCode; ")
                        continue
                    }

                    // Đảm bảo ExoPlayer trên CloudStream 4.5.2 nhận đúng MediaSource (HLS/DASH)
                    val finalUrl = when (linkType) {
                        ExtractorLinkType.M3U8 ->
                            if (rawStreamUrl.contains(".m3u8", ignoreCase = true)) rawStreamUrl
                            else "$rawStreamUrl#file.m3u8"
                        ExtractorLinkType.DASH ->
                            if (rawStreamUrl.contains(".mpd", ignoreCase = true)) rawStreamUrl
                            else "$rawStreamUrl#file.mpd"
                        else -> rawStreamUrl
                    }

                    val tagFormat = when (linkType) {
                        ExtractorLinkType.DASH -> "DASH"
                        ExtractorLinkType.M3U8 -> "HLS"
                        else -> "VIDEO"
                    }
                    val displayName = if (sources.length() > 1) {
                        "VidNest • ${srv.label} ($tagFormat #${i + 1})"
                    } else {
                        "VidNest • ${srv.label} ($tagFormat)"
                    }

                    validStreams.add(
                        ResolvedStream(
                            serverLabel = displayName,
                            finalUrl = finalUrl,
                            linkType = linkType,
                            quality = Qualities.P1080.value
                        )
                    )
                    logSb.append("${srv.label} (${srv.id}) -> LIVE [$tagFormat HTTP $probeCode]")
                }
            }.onFailure { ex ->
                logSb.append("${srv.label} (${srv.id}) -> ERR: ${ex.message}")
            }

            ServerFetchResult(logSb.toString(), validStreams, extraSubs)
        }

        // 3. Ghi nhận phụ đề (loại trùng lặp URL)
        val seenSubUrls = HashSet<String>()
        val allSubs = dedicatedSubs + serverResults.flatMap { it.subs }
        var subCount = 0
        for (sub in allSubs) {
            if (seenSubUrls.add(sub.url)) {
                subtitleCallback.invoke(SubtitleFile(sub.label, sub.url))
                subCount++
            }
        }

        // 4. Phát luồng sống cho trình phát (suspend newExtractorLink)
        val seenStreamUrls = HashSet<String>()
        var streamCount = 0
        for (res in serverResults) {
            if (res.logLine.isNotBlank()) {
                ClipboardLogger.logAndCopy("Server", res.logLine)
            }
            for (st in res.streams) {
                if (!seenStreamUrls.add(st.finalUrl)) continue
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = st.serverLabel,
                        url = st.finalUrl,
                        type = st.linkType
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = st.quality
                        this.headers = playHeaders
                    }
                )
                streamCount++
            }
        }

        ClipboardLogger.logAndCopy(
            if (streamCount > 0) "Done" else "Failed",
            "Đã nạp $streamCount luồng phát & $subCount phụ đề."
        )
        return streamCount > 0
    }
}

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

class CinezoProvider : MainAPI() {
    override var mainUrl = "https://cinezo.live"
    override var name = "Cinezo"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val playerUrl = "https://player.cinezo.live"
    private val punkflixBase = "https://punkflix.net"
    private val flikhubStreamHosts = listOf(
        "https://proxy3.flikhub.net",
        "https://proxy1.flikhub.net"
    )
    private val flikhubSubHosts = listOf(
        "https://proxy1.flikhub.net",
        "https://proxy3.flikhub.net"
    )

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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

    private val imdbCache = ConcurrentHashMap<Int, String>()

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending TV Shows",
        "/movie/now_playing" to "Now Playing",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular TV Shows",
        "/tv/top_rated" to "Top Rated TV Shows"
    )

    // =========================================================================
    // TMDB Catalog & Metadata
    // =========================================================================

    private fun playerHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to "$playerUrl/",
        "Origin" to playerUrl,
        "Accept" to "application/json, text/event-stream, */*"
    )

    private fun playbackHeaders(): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to "$playerUrl/",
        "Origin" to playerUrl
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

    private fun toSearchResponse(obj: JSONObject, defaultTv: Boolean = false): SearchResponse? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null

        val mediaType = obj.optString("media_type")
        if (mediaType.isNotBlank() && mediaType != "movie" && mediaType != "tv") return null
        val isTv = when {
            mediaType.isNotBlank() -> mediaType == "tv"
            obj.has("first_air_date") || obj.has("name") -> true
            else -> defaultTv
        }

        val type = if (isTv) "tv" else "movie"
        val title = obj.optString(if (isTv) "name" else "title", "").trim()
        if (title.isBlank()) return null

        val poster = proxyImage(obj.optString("poster_path", ""), "w500").ifBlank { null }
        val year = parseYear(obj.optString(if (isTv) "first_air_date" else "release_date", ""))
        val dataUrl = "cinezo:$type:$id"

        return if (isTv) {
            newTvSeriesSearchResponse(title, dataUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, dataUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val json = tmdbGet("${request.data}${sep}page=$page")
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val defaultTv = request.data.contains("/tv")
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            toSearchResponse(obj, defaultTv)?.let { items.add(it) }
        }

        val hasNext = page < json.optInt("total_pages", 1)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")
        val json = tmdbGet("/search/multi?query=$encoded&include_adult=false&page=1")
            ?: return emptyList()

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            toSearchResponse(obj)?.let { items.add(it) }
        }
        return items
    }

    // =========================================================================
    // Load Details (Movie / TV Series) - Tương thích CloudStream 4.5.2 (No rating/score)
    // =========================================================================

    private data class MediaRef(val type: String, val tmdbId: Int, val season: Int?, val episode: Int?)

    private fun parseRef(input: String): MediaRef? {
        Regex("""cinezo:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(input)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return MediaRef(
                type = m.groupValues[1],
                tmdbId = id,
                season = m.groupValues[3].toIntOrNull(),
                episode = m.groupValues[4].toIntOrNull()
            )
        }

        Regex("""/(?:embed/)?(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""").find(input)?.let { m ->
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

        val canonical = "cinezo:${ref.type}:$tmdbId"

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
                            newEpisode("cinezo:tv:$tmdbId:$sNum:$epNum") {
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
    // Stream Extraction + Pre-Flight Probe + OpenSubtitles
    // =========================================================================

    private data class CandidateStream(
        val label: String,
        val rawUrl: String,
        val hintType: String,
        val quality: Int
    )

    private data class ResolvedSub(val label: String, val url: String)

    private data class ProbedStream(
        val label: String,
        val finalUrl: String,
        val linkType: ExtractorLinkType,
        val quality: Int,
        val statusCode: Int,
        val alive: Boolean
    )

    private fun parseQualityHeight(resStr: String?): Int {
        if (resStr.isNullOrBlank()) return Qualities.P1080.value
        val nums = Regex("(\\d{3,4})").findAll(resStr).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        val maxDim = nums.maxOrNull() ?: return Qualities.P1080.value
        return when {
            maxDim >= 3800 || resStr.contains("2160") || resStr.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            maxDim >= 1900 || resStr.contains("1080") -> Qualities.P1080.value
            maxDim >= 1200 || resStr.contains("720") -> Qualities.P720.value
            maxDim >= 800 || resStr.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    private suspend fun fetchJennifer(ref: MediaRef, headers: Map<String, String>): Pair<List<CandidateStream>, List<ResolvedSub>> {
        val url = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "$punkflixBase/tv/${ref.tmdbId}/$s/$e.json"
        } else {
            "$punkflixBase/movie/${ref.tmdbId}.json"
        }

        val candidates = mutableListOf<CandidateStream>()
        val subs = mutableListOf<ResolvedSub>()

        runCatching {
            val resp = app.get(url, headers = headers, timeout = 10L)
            if (resp.code !in 200..299) {
                ClipboardLogger.logAndCopy("Jennifer", "HTTP ${resp.code}")
                return@runCatching
            }
            val json = JSONObject(resp.text)
            val qualityRes = json.optJSONObject("quality")?.optString("resolution", "") ?: ""
            val qVal = parseQualityHeight(qualityRes)
            val streamObj = json.optJSONObject("stream")

            if (streamObj != null) {
                val hls = streamObj.optString("hls", "").trim()
                val original = streamObj.optString("original", "").trim()
                val remux = streamObj.optString("remux", "").trim()

                val primaryHls = hls.ifBlank { original }
                if (primaryHls.startsWith("http")) {
                    candidates.add(CandidateStream("Cinezo • Jennifer (HLS)", primaryHls, "hls", qVal))
                }
                if (original.startsWith("http") && original != primaryHls) {
                    val hint = if (original.contains(".m3u8", ignoreCase = true)) "hls" else "file"
                    candidates.add(CandidateStream("Cinezo • Jennifer (Original)", original, hint, qVal))
                }
                if (json.optBoolean("remux_playable", false) && remux.startsWith("http")) {
                    candidates.add(CandidateStream("Cinezo • Jennifer (Remux)", remux, "file", qVal))
                }
            }

            val directUrl = json.optString("url", "").trim()
            if (candidates.isEmpty() && directUrl.startsWith("http")) {
                candidates.add(CandidateStream("Cinezo • Jennifer", directUrl, "hls", qVal))
            }

            val subArr = json.optJSONArray("subtitles")
            if (subArr != null) {
                for (i in 0 until subArr.length()) {
                    val sObj = subArr.optJSONObject(i) ?: continue
                    val subUrl = sObj.optString("url", "").ifBlank { sObj.optString("file", "") }.trim()
                    if (!subUrl.startsWith("http")) continue
                    val label = sObj.optString("label", "").ifBlank { sObj.optString("language", "Subtitle") }.trim()
                    subs.add(ResolvedSub(label, subUrl))
                }
            }
        }.onFailure { ex ->
            ClipboardLogger.logAndCopy("Jennifer", "ERR: ${ex.message}")
        }

        return Pair(candidates, subs)
    }

    private data class FlikhubServer(val id: String, val label: String, val useHevc: Boolean)

    private val flikhubServers = listOf(
        FlikhubServer("zendaya", "Zendaya", true),
        FlikhubServer("berlin", "Berlin", true),
        FlikhubServer("sadie", "Sadie", true),
        FlikhubServer("scarlet", "Scarlet", false)
    )

    private suspend fun fetchFlikhubServer(
        srv: FlikhubServer,
        ref: MediaRef,
        headers: Map<String, String>
    ): List<CandidateStream> {
        val out = mutableListOf<CandidateStream>()
        val hevcParam = if (srv.useHevc) "&hevc=1" else ""

        for (host in flikhubStreamHosts) {
            val url = if (ref.type == "tv") {
                val s = ref.season ?: 1
                val e = ref.episode ?: 1
                "$host/tv?id=${ref.tmdbId}&season=$s&episode=$e&mode=json&sources=${srv.id}$hevcParam"
            } else {
                "$host/movie?id=${ref.tmdbId}&mode=json&sources=${srv.id}$hevcParam"
            }

            val ok = runCatching {
                val resp = app.get(url, headers = headers, timeout = 12L)
                if (resp.code !in 200..299) {
                    ClipboardLogger.logAndCopy("Flikhub", "${srv.label} ($host) -> HTTP ${resp.code}")
                    return@runCatching false
                }
                val text = resp.text.trim()
                if (!text.startsWith("{")) return@runCatching false

                val json = JSONObject(text)
                val sourceObj = json.optJSONObject("source") ?: return@runCatching true
                val streamUrl = sourceObj.optString("url", "").trim()
                val streamType = sourceObj.optString("type", "").lowercase()
                val srcLabel = sourceObj.optString("label", "").ifBlank { srv.label }.trim()

                if (streamUrl.startsWith("http")) {
                    out.add(
                        CandidateStream(
                            label = "Cinezo • $srcLabel",
                            rawUrl = streamUrl,
                            hintType = streamType,
                            quality = Qualities.P1080.value
                        )
                    )
                }

                // Kiểm tra thêm bảng qualities (nếu có nhiều độ phân giải)
                val qObj = sourceObj.optJSONObject("qualities")
                if (qObj != null) {
                    val keys = qObj.keys()
                    while (keys.hasNext()) {
                        val qKey = keys.next()
                        val item = qObj.optJSONObject(qKey) ?: continue
                        val qUrl = item.optString("url", "").trim()
                        val qType = item.optString("type", streamType).lowercase()
                        if (qUrl.startsWith("http") && qUrl != streamUrl) {
                            val qVal = parseQualityHeight(qKey)
                            out.add(
                                CandidateStream(
                                    label = "Cinezo • $srcLabel ($qKey)",
                                    rawUrl = qUrl,
                                    hintType = qType,
                                    quality = qVal
                                )
                            )
                        }
                    }
                }
                true
            }.getOrDefault(false)

            if (ok) break
        }
        return out
    }

    private suspend fun fetchSubtitles(ref: MediaRef, headers: Map<String, String>): List<ResolvedSub> {
        for (host in flikhubSubHosts) {
            val url = if (ref.type == "tv") {
                val s = ref.season ?: 1
                val e = ref.episode ?: 1
                "$host/subtitles/tv/${ref.tmdbId}/$s/$e"
            } else {
                "$host/subtitles/movie/${ref.tmdbId}"
            }

            val result = runCatching {
                val resp = app.get(url, headers = headers, timeout = 8L)
                if (resp.code !in 200..299) return@runCatching null
                val body = resp.text.trim()
                if (!body.startsWith("[")) return@runCatching null
                val arr = JSONArray(body)
                val list = mutableListOf<ResolvedSub>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val subUrl = item.optString("file", "").trim()
                    if (!subUrl.startsWith("http")) continue
                    val label = item.optString("label", "Subtitle").trim()
                    list.add(ResolvedSub(label, subUrl))
                }
                list
            }.getOrNull()

            if (result != null) return result
        }
        return emptyList()
    }

    private suspend fun probeCandidate(
        cand: CandidateStream,
        baseHeaders: Map<String, String>
    ): ProbedStream {
        val isLikelyManifest = cand.hintType == "hls" ||
            cand.hintType == "dash" ||
            cand.rawUrl.contains(".m3u8", ignoreCase = true) ||
            cand.rawUrl.contains(".mpd", ignoreCase = true) ||
            cand.rawUrl.contains("manifest", ignoreCase = true)

        return runCatching {
            val probeHeaders = if (isLikelyManifest) {
                baseHeaders
            } else {
                baseHeaders + ("Range" to "bytes=0-1023")
            }

            val resp = app.get(cand.rawUrl, headers = probeHeaders, timeout = 8L)
            if (resp.code !in 200..299) {
                return ProbedStream(cand.label, cand.rawUrl, ExtractorLinkType.VIDEO, cand.quality, resp.code, false)
            }

            if (isLikelyManifest) {
                val snippet = resp.text.take(1500).trimStart()
                val detectedType = when {
                    snippet.contains("#EXTM3U") -> ExtractorLinkType.M3U8
                    snippet.contains("<MPD", ignoreCase = true) -> ExtractorLinkType.DASH
                    snippet.startsWith("<!doctype", ignoreCase = true) ||
                        snippet.startsWith("<html", ignoreCase = true) ||
                        snippet.startsWith("{") -> null
                    cand.hintType == "dash" || cand.rawUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    cand.hintType == "hls" || cand.rawUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                    else -> null
                } ?: return ProbedStream(cand.label, cand.rawUrl, ExtractorLinkType.VIDEO, cand.quality, resp.code, false)

                val finalUrl = when (detectedType) {
                    ExtractorLinkType.M3U8 ->
                        if (cand.rawUrl.contains(".m3u8", ignoreCase = true)) cand.rawUrl
                        else "${cand.rawUrl}#file.m3u8"
                    ExtractorLinkType.DASH ->
                        if (cand.rawUrl.contains(".mpd", ignoreCase = true)) cand.rawUrl
                        else "${cand.rawUrl}#file.mpd"
                    else -> cand.rawUrl
                }
                val formatTag = if (detectedType == ExtractorLinkType.DASH) "DASH" else "HLS"
                val finalLabel = if (cand.label.contains(formatTag, ignoreCase = true)) {
                    cand.label
                } else {
                    "${cand.label} ($formatTag)"
                }
                ProbedStream(finalLabel, finalUrl, detectedType, cand.quality, resp.code, true)
            } else {
                // Kiểm tra luồng video MP4/Remux (đã giới hạn Range 1KB) không bị trả về HTML/JSON lỗi
                val snippet = resp.text.take(300).trimStart()
                if (snippet.startsWith("<!doctype", ignoreCase = true) ||
                    snippet.startsWith("<html", ignoreCase = true) ||
                    snippet.startsWith("{")
                ) {
                    return ProbedStream(cand.label, cand.rawUrl, ExtractorLinkType.VIDEO, cand.quality, resp.code, false)
                }
                val finalLabel = if (cand.label.contains("MP4", ignoreCase = true) ||
                    cand.label.contains("Remux", ignoreCase = true)
                ) {
                    cand.label
                } else {
                    "${cand.label} (MP4)"
                }
                ProbedStream(finalLabel, cand.rawUrl, ExtractorLinkType.VIDEO, cand.quality, resp.code, true)
            }
        }.getOrElse {
            ProbedStream(cand.label, cand.rawUrl, ExtractorLinkType.VIDEO, cand.quality, 0, false)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "Cinezo loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Không phân tích được data: $data")
            return false
        }

        val apiHdrs = playerHeaders()
        val playHdrs = playbackHeaders()

        // 1. Lấy song song: Jennifer 4K (punkflix.net), FlikHub servers & OpenSubtitles
        val externalSubs = fetchSubtitles(ref, apiHdrs)
        val (jenniferStreams, jenniferSubs) = fetchJennifer(ref, apiHdrs)
        val flikhubStreams = flikhubServers.amap { srv ->
            fetchFlikhubServer(srv, ref, apiHdrs)
        }.flatten()

        // 2. Loại trùng URL trước khi probe để tối ưu tốc độ
        val uniqueCandidates = (jenniferStreams + flikhubStreams).distinctBy { it.rawUrl }

        // 3. Pre-flight probe toàn bộ ứng viên song song (chặn link 410/404/403)
        val probedResults = uniqueCandidates.amap { cand ->
            probeCandidate(cand, playHdrs)
        }

        // 4. Đẩy phụ đề (đã khử trùng)
        val seenSubs = HashSet<String>()
        var subCount = 0
        for (sub in jenniferSubs + externalSubs) {
            if (seenSubs.add(sub.url)) {
                subtitleCallback.invoke(SubtitleFile(sub.label, sub.url))
                subCount++
            }
        }

        // 5. Đẩy các luồng sống cho trình phát qua suspend newExtractorLink
        val seenStreams = HashSet<String>()
        var streamCount = 0
        for (res in probedResults) {
            if (!res.alive) {
                ClipboardLogger.logAndCopy("Dead", "${res.label} -> HTTP ${res.statusCode}")
                continue
            }
            if (!seenStreams.add(res.finalUrl)) continue

            ClipboardLogger.logAndCopy("Live", "${res.label} -> HTTP ${res.statusCode}")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = res.label,
                    url = res.finalUrl,
                    type = res.linkType
                ) {
                    this.referer = "$playerUrl/"
                    this.quality = res.quality
                    this.headers = playHdrs
                }
            )
            streamCount++
        }

        ClipboardLogger.logAndCopy(
            if (streamCount > 0) "Done" else "Failed",
            "Đã nạp $streamCount luồng phát & $subCount phụ đề."
        )
        return streamCount > 0
    }
}

package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class VidsparkProvider : MainAPI() {
    override var mainUrl = "https://vidspark.to"
    override var name = "Vidspark"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        // Có thể dán sẵn API Key SubDL miễn phí (lấy tại https://subdl.com/panel/api)
        // Hoặc nhập trực tiếp trong mục Cài đặt Plugin (biểu tượng bánh răng) trên CloudStream
        private const val DEFAULT_SUBDL_API_KEY = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"

        private const val VIDSPARK_HOST = "https://vidspark.to"
        private const val VIDSPARK_CDN_HOST = "https://cdn.vidspark.to"

        private const val DEFAULT_VIDSPARK_KEY =
            "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"
        private const val DEFAULT_CDN_VIDSPARK_KEY =
            "f3b72e73c80c9a996574379798703796a1936efa3516a7105cb0e43048b46b5a"
    }

    // User-Agent Chrome chuẩn
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

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

    @Volatile
    private var cachedVidsparkKey: String = DEFAULT_VIDSPARK_KEY

    @Volatile
    private var cachedCdnKey: String = DEFAULT_CDN_VIDSPARK_KEY

    // Các server dự phòng trên cdn.vidspark.to
    private val cdnServers = listOf(
        "vjej" to "Vega",
        "w7dn" to "Nebula",
        "p9fx" to "Vertex"
    )

    // Chặn quảng cáo/popunder trên vidspark.to & cdn.vidspark.to
    private val blockedWebViewDomains = listOf(
        "anemonytahua.com", "yxnjqmjye2e7778pio1.rest", "3hxoppqji4etwm1r4.cfd",
        "secutorlunts.cfd", "benchform.org", "apiguinee.org", "static.nresystems.com",
        "googletagmanager.com", "google-analytics.com",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
        "adsterra.com", "adcash.com", "rtmark.net", "histats.com",
        "acscdn.com", "disable-devtool"
    )

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
        val dataUrl = "vidspark:$type:$id"

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
        Regex("""vidspark:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(input)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return MediaRef(
                type = m.groupValues[1],
                tmdbId = id,
                season = m.groupValues[3].toIntOrNull(),
                episode = m.groupValues[4].toIntOrNull()
            )
        }

        Regex("""/(movie|tv)/(\d+)(?:[/-](\d+)[/-](\d+))?""").find(input)?.let { m ->
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
        val year = parseYear(objDate(json, isTv))

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

        val canonical = "vidspark:${ref.type}:$tmdbId"

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
                            newEpisode("vidspark:tv:$tmdbId:$sNum:$epNum") {
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

    private fun objDate(json: JSONObject, isTv: Boolean): String =
        json.optString(if (isTv) "first_air_date" else "release_date", "")

    // =========================================================================
    // Subtitle List: Vidspark Built-in + OpenSubtitles + SubDL
    // =========================================================================

    private data class SubtitleCandidate(
        val source: String,
        val langLabel: String,
        val url: String,
        val releaseHint: String
    )

    private fun resolveAppContext(): Context? {
        VidsparkSubtitleStore.getAppContext()?.let { return it }
        CommonActivity.activity?.let { return it.applicationContext ?: it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            method.invoke(null) as? Context
        }.getOrNull()
    }

    private fun resolveSubdlApiKey(ctx: Context?): String {
        val saved = ctx?.let {
            runCatching {
                it.getSharedPreferences(VidsparkSubtitleStore.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(VidsparkSubtitleStore.KEY_SUBDL_API, "")?.trim() ?: ""
            }.getOrDefault("")
        } ?: ""
        return saved.ifBlank { DEFAULT_SUBDL_API_KEY.trim() }
    }

    private fun appendSubExtFragment(url: String, formatHint: String = "srt"): String {
        val lower = url.lowercase()
        if (lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass") || lower.contains(".ssa")) {
            return url
        }
        val cleanExt = when (formatHint.lowercase()) {
            "vtt", "webvtt" -> "vtt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            else -> "srt"
        }
        return "$url#file.$cleanExt"
    }

    // Proxy HTTP nội bộ (chỉ tải & giải nén .zip SubDL khi người dùng thực sự chọn xem phụ đề đó)
    private object SubdlLoopbackProxy {
        data class Entry(
            val zipUrl: String,
            val isTv: Boolean,
            val season: Int?,
            val episode: Int?,
            val ua: String
        )

        private var serverSocket: ServerSocket? = null
        private var port: Int = -1
        private val seq = AtomicInteger(0)
        private val entries = ConcurrentHashMap<String, Entry>()
        private val memCache = ConcurrentHashMap<String, Pair<String, ByteArray>>()

        @Synchronized
        fun ensureStarted(): Int {
            if (port > 0 && serverSocket?.isClosed == false) return port
            return runCatching {
                val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                Thread {
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            Thread { handleClient(client) }.apply { isDaemon = true }.start()
                        } catch (_: Exception) {
                            if (ss.isClosed) break
                        }
                    }
                }.apply { isDaemon = true }.start()
                port
            }.getOrDefault(-1)
        }

        fun register(
            zipUrl: String,
            isTv: Boolean,
            season: Int?,
            episode: Int?,
            ua: String
        ): String? {
            val p = ensureStarted()
            if (p <= 0) return null
            if (entries.size > 200) entries.clear()
            val token = seq.incrementAndGet().toString()
            entries[token] = Entry(
                zipUrl = zipUrl,
                isTv = isTv,
                season = season,
                episode = episode,
                ua = ua
            )
            return "http://127.0.0.1:$p/subdl/$token.srt"
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val rawPath = parts.getOrNull(1) ?: ""
                val token = Regex("""/subdl/(\d+)\.srt""").find(rawPath)?.groupValues?.getOrNull(1)
                val entry = token?.let { entries[it] }

                val out = socket.getOutputStream()
                if (token == null || entry == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val resolved = memCache[token] ?: fetchAndUnzip(entry)?.also { res ->
                    if (memCache.size >= 12) memCache.clear()
                    memCache[token] = res
                }

                if (resolved == null) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val (mime, bytes) = resolved
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") {
                    out.write(bytes)
                }
                out.flush()
            } catch (_: Exception) {
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }

        private fun fetchAndUnzip(entry: Entry): Pair<String, ByteArray>? {
            val zipBytes = downloadBlocking(
                entry.zipUrl,
                mapOf("User-Agent" to entry.ua, "Referer" to "https://subdl.com/", "Accept" to "*/*")
            ) ?: return null
            if (zipBytes.isEmpty()) return null

            if (zipBytes.size >= 4 && zipBytes[0] == 0x50.toByte() && zipBytes[1] == 0x4B.toByte()) {
                val validExts = listOf(".srt", ".vtt", ".ass", ".ssa")
                val collected = mutableListOf<Pair<String, ByteArray>>()
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                    var ze = zis.nextEntry
                    while (ze != null) {
                        val name = ze.name ?: ""
                        val lower = name.lowercase()
                        if (!ze.isDirectory && !lower.contains("__macosx/") && validExts.any { lower.endsWith(it) }) {
                            val data = zis.readBytes()
                            if (data.isNotEmpty()) collected.add(name to data)
                        }
                        zis.closeEntry()
                        ze = zis.nextEntry
                    }
                }
                if (collected.isEmpty()) return null

                val best = collected.sortedWith(
                    compareBy<Pair<String, ByteArray>>(
                        { if (entry.isTv && !matchesEpisodeHint(it.first, entry.season, entry.episode)) 1 else 0 },
                        {
                            val l = it.first.lowercase()
                            when {
                                l.endsWith(".srt") -> 0
                                l.endsWith(".vtt") -> 1
                                else -> 2
                            }
                        }
                    )
                ).first()

                val mime = if (best.first.lowercase().endsWith(".vtt")) {
                    "text/vtt; charset=utf-8"
                } else {
                    "application/x-subrip; charset=utf-8"
                }
                return mime to best.second
            }

            val head = zipBytes.take(150).toByteArray().toString(Charsets.UTF_8).trimStart()
            if (head.startsWith("<!doctype", ignoreCase = true) || head.startsWith("<html", ignoreCase = true)) {
                return null
            }
            val mime = if (head.contains("WEBVTT", ignoreCase = true)) {
                "text/vtt; charset=utf-8"
            } else {
                "application/x-subrip; charset=utf-8"
            }
            return mime to zipBytes
        }

        private fun matchesEpisodeHint(fileName: String, season: Int?, episode: Int?): Boolean {
            if (season == null || episode == null) return true
            val lower = fileName.lowercase()
            val s2 = season.toString().padStart(2, '0')
            val e2 = episode.toString().padStart(2, '0')
            return listOf(
                "s${s2}e${e2}", "s${season}e${e2}", "s${s2}e${episode}",
                "${season}x${e2}", "${s2}x${e2}", "episode.${e2}", "episode_${e2}", "ep${e2}"
            ).any { lower.contains(it) }
        }

        private fun downloadBlocking(urlStr: String, headers: Map<String, String>): ByteArray? {
            var current = urlStr
            for (hop in 0..3) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(current).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 12_000
                        readTimeout = 12_000
                        requestMethod = "GET"
                        headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")?.trim()
                        if (!loc.isNullOrBlank()) {
                            current = if (loc.startsWith("http", ignoreCase = true)) loc else URL(URL(current), loc).toString()
                            continue
                        }
                    }
                    if (code in 200..299) {
                        return conn.inputStream.use { it.readBytes() }
                    }
                    return null
                } catch (_: Exception) {
                    return null
                } finally {
                    conn?.disconnect()
                }
            }
            return null
        }
    }

    private suspend fun queryVidsparkSubtitles(ref: MediaRef): List<SubtitleCandidate> {
        val path = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "tv/${ref.tmdbId}/$s/$e"
        } else {
            "movie/${ref.tmdbId}"
        }
        val url = "$VIDSPARK_HOST/api/vidora/v1/subtitles/$path"
        val out = mutableListOf<SubtitleCandidate>()
        runCatching {
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$VIDSPARK_HOST/",
                    "x-player-key" to cachedVidsparkKey,
                    "Accept" to "application/json"
                ),
                timeout = 8L
            )
            if (resp.code !in 200..299) return@runCatching
            val json = JSONObject(resp.text)
            val tracks = json.optJSONArray("tracks") ?: return@runCatching

            val viList = mutableListOf<SubtitleCandidate>()
            val enList = mutableListOf<SubtitleCandidate>()
            val otherList = mutableListOf<SubtitleCandidate>()

            for (i in 0 until tracks.length()) {
                val obj = tracks.optJSONObject(i) ?: continue
                val rawSrc = obj.optString("src", "").trim()
                if (rawSrc.isBlank()) continue
                val subUrl = when {
                    rawSrc.startsWith("http", ignoreCase = true) -> rawSrc
                    rawSrc.startsWith("/") -> "$VIDSPARK_HOST/api/vidora$rawSrc"
                    else -> "$VIDSPARK_HOST/api/vidora/$rawSrc"
                }
                val langCode = obj.optString("language", "").lowercase()
                val langName = obj.optString("languageName", "").ifBlank {
                    obj.optString("label", "Subtitle")
                }.trim()
                when {
                    langCode.startsWith("vi") || langName.contains("viet", ignoreCase = true) ->
                        viList.add(SubtitleCandidate("Vidspark", "Vietnamese", subUrl, ""))
                    langCode.startsWith("en") || langName.contains("english", ignoreCase = true) ->
                        enList.add(SubtitleCandidate("Vidspark", "English", subUrl, ""))
                    else -> if (otherList.size < 4) {
                        otherList.add(SubtitleCandidate("Vidspark", langName, subUrl, ""))
                    }
                }
            }
            out.addAll(viList)
            out.addAll(enList)
            if (viList.isEmpty() && enList.isEmpty()) out.addAll(otherList)
        }
        return out
    }

    private suspend fun queryOpenSubtitlesRest(ref: MediaRef, imdbId: String): List<SubtitleCandidate> {
        if (imdbId.isBlank()) return emptyList()
        val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId.removePrefix("tt") }
        val headers = mapOf(
            "User-Agent" to "trailers.to-UA",
            "X-User-Agent" to "trailers.to-UA"
        )

        return listOf("vie" to "Vietnamese", "eng" to "English").amap { (langCode, langFallback) ->
            val out = mutableListOf<SubtitleCandidate>()
            runCatching {
                val url = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                    "https://rest.opensubtitles.org/search/episode-${ref.episode}/imdbid-$cleanId/season-${ref.season}/sublanguageid-$langCode"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$langCode"
                }
                val res = app.get(url, headers = headers, timeout = 8L).text.trim()
                if (!res.startsWith("[")) return@runCatching
                val arr = JSONArray(res)

                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedBy { obj ->
                        when (obj.optString("SubFormat", "").lowercase()) {
                            "srt", "vtt" -> 0
                            else -> 1
                        }
                    }

                for (item in items.take(6)) {
                    val rawLink = item.optString("SubDownloadLink", "").trim()
                    if (!rawLink.startsWith("http")) continue
                    val directLink = rawLink
                        .replace("/filead/", "/subencoding-utf8/filead/")
                        .replace("/file/", "/subencoding-utf8/file/")
                        .removeSuffix(".gz")
                    val subFmt = item.optString("SubFormat", "srt")
                    val finalLink = appendSubExtFragment(directLink, subFmt)
                    val langName = item.optString("LanguageName", langFallback).trim()
                    val relName = item.optString("MovieReleaseName", "").trim()
                    out.add(
                        SubtitleCandidate(
                            source = "OpenSubtitles",
                            langLabel = langName,
                            url = finalLink,
                            releaseHint = relName
                        )
                    )
                }
            }
            out
        }.flatten()
    }

    private suspend fun queryOpenSubtitlesStremio(ref: MediaRef, imdbId: String): List<SubtitleCandidate> {
        if (imdbId.isBlank()) return emptyList()
        val url = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$s:$e.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        }

        val out = mutableListOf<SubtitleCandidate>()
        runCatching {
            val text = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 8L).text.trim()
            if (!text.startsWith("{")) return@runCatching
            val arr = JSONObject(text).optJSONArray("subtitles") ?: return@runCatching

            val viList = mutableListOf<SubtitleCandidate>()
            val enList = mutableListOf<SubtitleCandidate>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("url", "").trim()
                if (!subUrl.startsWith("http")) continue
                val l = obj.optString("lang", "").lowercase()
                val rel = obj.optString("releaseGroup", "").ifBlank {
                    obj.optString("movieReleaseName", "")
                }.trim()
                val finalUrl = appendSubExtFragment(subUrl, "srt")
                when {
                    l == "vie" || l == "vi" -> if (viList.size < 5) {
                        viList.add(SubtitleCandidate("OpenSubtitles-V3", "Vietnamese", finalUrl, rel))
                    }
                    l == "eng" || l == "en" -> if (enList.size < 5) {
                        enList.add(SubtitleCandidate("OpenSubtitles-V3", "English", finalUrl, rel))
                    }
                }
            }
            out.addAll(viList)
            out.addAll(enList)
        }
        return out
    }

    private suspend fun querySubdl(ref: MediaRef, imdbId: String, apiKey: String): List<SubtitleCandidate> {
        if (apiKey.isBlank()) return emptyList()
        val encKey = URLEncoder.encode(apiKey, "UTF-8")
        val idParam = if (imdbId.startsWith("tt")) {
            "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}"
        } else {
            "tmdb_id=${ref.tmdbId}"
        }
        val queryUrl = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "https://api.subdl.com/api/v1/subtitles?api_key=$encKey&$idParam&type=tv&season_number=$s&episode_number=$e&languages=VI,EN"
        } else {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encKey&$idParam&type=movie&languages=VI,EN"
        }

        val out = mutableListOf<SubtitleCandidate>()
        runCatching {
            val resp = app.get(
                queryUrl,
                headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
                timeout = 10L
            )
            if (resp.code !in 200..299) {
                ClipboardLogger.logAndCopy("SubDL", "HTTP ${resp.code}: ${resp.text.take(150)}")
                return@runCatching
            }
            val json = JSONObject(resp.text)
            if (!json.optBoolean("status", false)) {
                ClipboardLogger.logAndCopy("SubDL", "API status=false: ${json.optString("error")}")
                return@runCatching
            }
            val subs = json.optJSONArray("subtitles") ?: return@runCatching

            val viItems = mutableListOf<JSONObject>()
            val enItems = mutableListOf<JSONObject>()
            for (i in 0 until subs.length()) {
                val obj = subs.optJSONObject(i) ?: continue
                val l = obj.optString("language", "").lowercase()
                if (l.contains("vietnam") || l == "vi" || l == "vie") {
                    viItems.add(obj)
                } else if (l.contains("english") || l == "en" || l == "eng") {
                    enItems.add(obj)
                }
            }

            // Ưu tiên bản không Hearing Impaired trước
            val sortedVi = viItems.sortedBy { if (it.optBoolean("hi", false)) 1 else 0 }.take(5)
            val sortedEn = enItems.sortedBy { if (it.optBoolean("hi", false)) 1 else 0 }.take(5)

            for ((langName, list) in listOf("Vietnamese" to sortedVi, "English" to sortedEn)) {
                for (item in list) {
                    val rawPath = item.optString("url", "").trim()
                    if (rawPath.isBlank()) continue
                    val fullZipUrl = when {
                        rawPath.startsWith("http", ignoreCase = true) -> rawPath
                        rawPath.startsWith("/") -> "https://dl.subdl.com$rawPath"
                        else -> "https://dl.subdl.com/$rawPath"
                    }
                    // Đăng ký link qua proxy nội bộ (chỉ giải nén khi người dùng bấm chọn sub)
                    val proxyUrl = SubdlLoopbackProxy.register(
                        fullZipUrl,
                        ref.type == "tv",
                        ref.season,
                        ref.episode,
                        ua
                    ) ?: continue
                    val release = item.optString("release_name", "").ifBlank {
                        item.optString("name", "")
                    }.trim()
                    out.add(
                        SubtitleCandidate(
                            source = "SubDL",
                            langLabel = langName,
                            url = proxyUrl,
                            releaseHint = release
                        )
                    )
                }
            }
        }.onFailure { ex ->
            ClipboardLogger.logAndCopy("SubDL Err", "${ex.message}")
        }
        return out
    }

    private suspend fun fetchSubtitleList(
        ref: MediaRef,
        imdbId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {
        val ctx = resolveAppContext()
        val subdlKey = resolveSubdlApiKey(ctx)

        // Gọi song song Vidspark built-in + OpenSubtitles REST + OpenSubtitles V3 + SubDL API
        val candidates = listOf(0, 1, 2, 3).amap { idx ->
            when (idx) {
                0 -> queryVidsparkSubtitles(ref)
                1 -> queryOpenSubtitlesRest(ref, imdbId)
                2 -> queryOpenSubtitlesStremio(ref, imdbId)
                else -> querySubdl(ref, imdbId, subdlKey)
            }
        }.flatten()

        val seenUrls = HashSet<String>()
        var count = 0
        for (cand in candidates) {
            if (!seenUrls.add(cand.url)) continue
            val label = "${cand.langLabel} • ${cand.source}" +
                (if (cand.releaseHint.isNotBlank()) " (${cand.releaseHint.take(28)})" else "")
            subtitleCallback.invoke(SubtitleFile(label, cand.url))
            count++
        }

        val srcInfo = if (subdlKey.isNotBlank()) "Vidspark + OpenSubtitles + SubDL" else "Vidspark + OpenSubtitles"
        ClipboardLogger.logAndCopy("Subtitles", "Đã nạp danh sách $count phụ đề ($srcInfo)")
        return count
    }

    // =========================================================================
    // Direct Vidspark API Extractor (Nhanh, tự làm mới x-player-key khi cần)
    // =========================================================================

    private data class DirectStreamCandidate(
        val serverName: String,
        val streamUrl: String,
        val originHost: String,
        val builtInTracks: List<Pair<String, String>>
    )

    private suspend fun refreshPlayerKeyFromHost(host: String, samplePageUrl: String): String? {
        return runCatching {
            val html = app.get(samplePageUrl, headers = mapOf("User-Agent" to ua), timeout = 8L).text
            val scriptUrls = Regex("""<script[^>]+src=["'](/assets/[^"']+\.js)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .filter { !it.contains("vidstack", ignoreCase = true) && !it.contains("rolldown", ignoreCase = true) && !it.contains("preload", ignoreCase = true) }
                .sortedBy { if (it.contains("main-") || it.contains("index-")) 0 else 1 }
                .toList()

            for (scriptSrc in scriptUrls) {
                val jsText = runCatching {
                    app.get("$host$scriptSrc", headers = mapOf("User-Agent" to ua), timeout = 10L).text
                }.getOrNull() ?: continue

                val match = Regex("""["']x-player-key["']\s*:\s*([a-zA-Z0-9_$]+)""").find(jsText)
                val varName = match?.groupValues?.getOrNull(1)
                if (!varName.isNullOrBlank()) {
                    val keyVal = Regex("""\b${Regex.escape(varName)}\s*=\s*[`"']([a-f0-9]{32,128})[`"']""")
                        .find(jsText)?.groupValues?.getOrNull(1)
                    if (!keyVal.isNullOrBlank()) return@runCatching keyVal
                }
            }
            null
        }.getOrNull()
    }

    private suspend fun callVidoraApi(
        host: String,
        apiPath: String,
        refererPage: String,
        isCdnHost: Boolean
    ): JSONObject? {
        val currentKey = if (isCdnHost) cachedCdnKey else cachedVidsparkKey
        val fullUrl = "$host/api/vidora$apiPath"

        var resp = runCatching {
            app.get(
                fullUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to refererPage,
                    "Origin" to host,
                    "x-player-key" to currentKey,
                    "Accept" to "application/json"
                ),
                timeout = 10L
            )
        }.getOrNull() ?: return null

        if (resp.code == 401 || resp.code == 403) {
            val freshKey = refreshPlayerKeyFromHost(host, refererPage)
            if (!freshKey.isNullOrBlank() && freshKey != currentKey) {
                if (isCdnHost) cachedCdnKey = freshKey else cachedVidsparkKey = freshKey
                resp = runCatching {
                    app.get(
                        fullUrl,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to refererPage,
                            "Origin" to host,
                            "x-player-key" to freshKey,
                            "Accept" to "application/json"
                        ),
                        timeout = 10L
                    )
                }.getOrNull() ?: return null
            }
        }

        if (resp.code !in 200..299) return null
        val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
        if (!json.optBoolean("result", false)) return null
        return json
    }

    private fun parseTracksFromJson(tracksArr: JSONArray?): List<Pair<String, String>> {
        if (tracksArr == null) return emptyList()
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until tracksArr.length()) {
            val obj = tracksArr.optJSONObject(i) ?: continue
            val fileUrl = obj.optString("file", "").ifBlank { obj.optString("src", "") }.trim()
            if (!fileUrl.startsWith("http")) continue
            val label = obj.optString("label", "Subtitle").trim()
            if (label.contains("viet", ignoreCase = true) || label.contains("english", ignoreCase = true)) {
                list.add("$label • Vidspark" to fileUrl)
            }
        }
        return list
    }

    private suspend fun fetchDirectVidsparkStreams(ref: MediaRef): List<DirectStreamCandidate> {
        val basePath = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "/v1/tv/${ref.tmdbId}/$s/$e"
        } else {
            "/v1/movie/${ref.tmdbId}"
        }
        val pageSuffix = if (ref.type == "tv") {
            "/tv/${ref.tmdbId}/${ref.season ?: 1}/${ref.episode ?: 1}"
        } else {
            "/movie/${ref.tmdbId}"
        }

        // Gọi song song nguồn chính vidspark.to + 3 server dự phòng trên cdn.vidspark.to
        val tasks = listOf(0, 1, 2, 3)
        return tasks.amap { idx ->
            val found = mutableListOf<DirectStreamCandidate>()
            if (idx == 0) {
                val json = callVidoraApi(VIDSPARK_HOST, basePath, "$VIDSPARK_HOST$pageSuffix", isCdnHost = false)
                val sources = json?.optJSONArray("sources")
                if (sources != null) {
                    for (si in 0 until sources.length()) {
                        val sObj = sources.optJSONObject(si) ?: continue
                        val surl = sObj.optString("url", "").trim()
                        if (!surl.startsWith("http")) continue
                        found.add(
                            DirectStreamCandidate(
                                serverName = "Primary",
                                streamUrl = surl,
                                originHost = VIDSPARK_HOST,
                                builtInTracks = parseTracksFromJson(sObj.optJSONArray("tracks"))
                            )
                        )
                    }
                }
            } else {
                val (srvKey, srvLabel) = cdnServers[idx - 1]
                val json = callVidoraApi(
                    VIDSPARK_CDN_HOST,
                    "$basePath?source=$srvKey",
                    "$VIDSPARK_CDN_HOST$pageSuffix",
                    isCdnHost = true
                )
                val sources = json?.optJSONArray("sources")
                if (sources != null) {
                    for (si in 0 until sources.length()) {
                        val sObj = sources.optJSONObject(si) ?: continue
                        val surl = sObj.optString("url", "").trim()
                        if (!surl.startsWith("http")) continue
                        found.add(
                            DirectStreamCandidate(
                                serverName = srvLabel,
                                streamUrl = surl,
                                originHost = VIDSPARK_CDN_HOST,
                                builtInTracks = parseTracksFromJson(sObj.optJSONArray("tracks"))
                            )
                        )
                    }
                }
            }
            found
        }.flatten()
    }

    // =========================================================================
    // WebView Sniffer Fallback (Bật ?autoplay=true khi API trực tiếp không khả dụng)
    // =========================================================================

    private data class SniffResult(
        val url: String,
        val reqHeaders: Map<String, String>,
        val subs: List<String>
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        refererUrl: String,
        excludedUrls: Set<String>,
        seenRequests: MutableList<String>
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val act = CommonActivity.activity
                val ctx = resolveAppContext()
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff", "Không có Context để tạo WebView")
                    cont.resume(null)
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    val capturedSubs = LinkedHashSet<String>()

                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) { }
                        cont.resume(res)
                    }

                    val streamRegex = Regex(
                        """\.(m3u8|mpd|mp4)(\?|$|#)|master\.txt|/playlist(\?|$)|/manifest(\?|$)|/dash-mpd(\?|$)""",
                        RegexOption.IGNORE_CASE
                    )
                    val segmentOrAssetRegex = Regex(
                        """\.(ts|m4s|aac|mp3|js|css|png|jpe?g|webp|gif|svg|ico|woff2?|ttf)(\?|$)|/assets/|/blank\.mp4""",
                        RegexOption.IGNORE_CASE
                    )
                    val subRegex = Regex("""\.(vtt|srt)(\?|$)""", RegexOption.IGNORE_CASE)

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false
                    }

                    val antiPopAndPlayJs = """
                        (function(){
                            try {
                                localStorage.setItem('shown_at', String(Date.now()));
                                window.open = function(){ return {closed:false, focus:function(){}, close:function(){}, blur:function(){}}; };
                            } catch(e){}
                            try {
                                var v = document.querySelector('video');
                                if (v && v.paused) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                }
                            } catch(e){}
                        })();
                    """.trimIndent()

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(antiPopAndPlayJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        } catch (_: Exception) { }
                        if (taps < 18) handler.postDelayed({ tap() }, 1800)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http", ignoreCase = true)) return null

                            if (blockedWebViewDomains.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!segmentOrAssetRegex.containsMatchIn(u) && seenRequests.size < 100) {
                                seenRequests.add(u.take(200))
                            }

                            if (subRegex.containsMatchIn(u) && !u.contains("thumbnails.vtt", ignoreCase = true)) {
                                capturedSubs.add(u)
                            }

                            if (!excludedUrls.contains(u) &&
                                streamRegex.containsMatchIn(u) &&
                                !segmentOrAssetRegex.containsMatchIn(u)
                            ) {
                                val hdrs = request.requestHeaders ?: emptyMap()
                                val hit = SniffResult(u, hdrs, capturedSubs.toList())
                                handler.post { finish(hit) }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            val allow = u.contains("vidspark.to", ignoreCase = true) ||
                                u.contains("challenges.cloudflare.com", ignoreCase = true)
                            if (!allow && seenRequests.size < 100) {
                                seenRequests.add("BLOCKED NAV: ${u.take(150)}")
                            }
                            return !allow
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            try {
                                wv.evaluateJavascript(antiPopAndPlayJs, null)
                            } catch (_: Exception) { }
                            handler.postDelayed({ tap() }, 2500)
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) { }

                    handler.postDelayed({ finish(null) }, 35_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to refererUrl))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =========================================================================
    // Pre-Flight Stream Probe + loadLinks
    // =========================================================================

    private data class ProbeOutcome(
        val linkType: ExtractorLinkType?,
        val statusCode: Int,
        val workingHeaders: Map<String, String>
    )

    private fun buildHeaderCandidates(
        capturedHeaders: Map<String, String>,
        preferredOrigin: String
    ): List<Map<String, String>> {
        val cleanedBase = capturedHeaders.toMutableMap().apply {
            put("User-Agent", ua)
        }
        val withPref = cleanedBase.toMutableMap().apply {
            put("Referer", "$preferredOrigin/")
            put("Origin", preferredOrigin)
        }
        val withVidspark = cleanedBase.toMutableMap().apply {
            put("Referer", "$VIDSPARK_HOST/")
            put("Origin", VIDSPARK_HOST)
        }
        val withCdn = cleanedBase.toMutableMap().apply {
            put("Referer", "$VIDSPARK_CDN_HOST/")
            put("Origin", VIDSPARK_CDN_HOST)
        }
        val noRef = cleanedBase.toMutableMap().apply {
            remove("Referer")
            remove("referer")
            remove("Origin")
            remove("origin")
        }
        return listOf(withPref, withVidspark, withCdn, noRef).distinct()
    }

    private suspend fun probeStreamWithFallbacks(
        rawUrl: String,
        headerCandidates: List<Map<String, String>>
    ): ProbeOutcome {
        val isMp4 = Regex("""\.mp4(\?|$|#)""", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)

        var lastCode = 0
        for (baseHdrs in headerCandidates) {
            val probeHdrs = if (isMp4) baseHdrs + ("Range" to "bytes=0-1023") else baseHdrs
            val outcome = runCatching {
                val resp = app.get(rawUrl, headers = probeHdrs, timeout = 8L)
                lastCode = resp.code
                if (resp.code !in 200..299) {
                    return@runCatching null
                }

                if (isMp4) {
                    val snippet = resp.text.take(300).trimStart()
                    if (snippet.startsWith("<!doctype", ignoreCase = true) ||
                        snippet.startsWith("<html", ignoreCase = true) ||
                        snippet.startsWith("{")
                    ) {
                        null
                    } else {
                        ProbeOutcome(ExtractorLinkType.VIDEO, resp.code, baseHdrs)
                    }
                } else {
                    val snippet = resp.text.take(1500).trimStart()
                    val detected = when {
                        snippet.contains("#EXTM3U") -> ExtractorLinkType.M3U8
                        snippet.contains("<MPD", ignoreCase = true) -> ExtractorLinkType.DASH
                        snippet.startsWith("<!doctype", ignoreCase = true) ||
                            snippet.startsWith("<html", ignoreCase = true) ||
                            snippet.startsWith("{") -> null
                        rawUrl.contains(".mpd", ignoreCase = true) || rawUrl.contains("dash-mpd", ignoreCase = true) ->
                            ExtractorLinkType.DASH
                        rawUrl.contains(".m3u8", ignoreCase = true) ->
                            ExtractorLinkType.M3U8
                        else -> null
                    }
                    detected?.let { ProbeOutcome(it, resp.code, baseHdrs) }
                }
            }.getOrNull()

            if (outcome != null) return outcome
        }
        return ProbeOutcome(null, lastCode, headerCandidates.firstOrNull() ?: emptyMap())
    }

    private suspend fun verifyAndEmitLink(
        serverLabel: String,
        rawUrl: String,
        capturedHeaders: Map<String, String>,
        preferredOrigin: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = buildHeaderCandidates(capturedHeaders, preferredOrigin)
        val probe = probeStreamWithFallbacks(rawUrl, candidates)
        val detectedType = probe.linkType
        if (detectedType == null) {
            ClipboardLogger.logAndCopy(
                "Dead ($serverLabel)",
                "HTTP ${probe.statusCode} -> ${rawUrl.take(120)}"
            )
            return false
        }

        val finalUrl = when (detectedType) {
            ExtractorLinkType.M3U8 ->
                if (rawUrl.contains(".m3u8", ignoreCase = true)) rawUrl else "$rawUrl#file.m3u8"
            ExtractorLinkType.DASH ->
                if (rawUrl.contains(".mpd", ignoreCase = true)) rawUrl else "$rawUrl#file.mpd"
            else -> rawUrl
        }

        val formatLabel = when (detectedType) {
            ExtractorLinkType.DASH -> "DASH"
            ExtractorLinkType.M3U8 -> "HLS"
            else -> "MP4"
        }
        val usedReferer = probe.workingHeaders["Referer"] ?: probe.workingHeaders["referer"] ?: ""

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Vidspark • $serverLabel ($formatLabel)",
                url = finalUrl,
                type = detectedType
            ) {
                this.referer = usedReferer
                this.quality = Qualities.P1080.value
                this.headers = probe.workingHeaders
            }
        )
        ClipboardLogger.logAndCopy(
            "Live ($serverLabel)",
            "HTTP ${probe.statusCode} ($formatLabel) -> ${finalUrl.take(120)}"
        )
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "Vidspark loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        // 1. Nạp danh sách phụ đề (Vidspark built-in + OpenSubtitles + SubDL) - không tải sẵn
        var imdbId = imdbCache[ref.tmdbId] ?: ""
        if (imdbId.isBlank()) {
            val details = tmdbGet("/${ref.type}/${ref.tmdbId}?append_to_response=external_ids")
            imdbId = details?.optString("imdb_id", "")?.ifBlank {
                details.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            } ?: ""
            if (imdbId.startsWith("tt")) {
                imdbCache[ref.tmdbId] = imdbId
            }
        }
        var subCount = fetchSubtitleList(ref, imdbId, subtitleCallback)
        val seenSubUrls = HashSet<String>()

        // 2. Tier 1: Gọi trực tiếp API Vidspark đa server (Primary + Vega + Nebula + Vertex)
        var emittedLinks = 0
        val emittedUrls = HashSet<String>()

        val directCandidates = fetchDirectVidsparkStreams(ref)
        for (cand in directCandidates) {
            for ((subLabel, subUrl) in cand.builtInTracks) {
                if (seenSubUrls.add(subUrl)) {
                    subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
                    subCount++
                }
            }
            if (!emittedUrls.add(cand.streamUrl)) continue
            if (verifyAndEmitLink(cand.serverName, cand.streamUrl, emptyMap(), cand.originHost, callback)) {
                emittedLinks++
            }
        }

        // 3. Tier 2: WebView Sniffer với ?autoplay=true nếu Tier 1 chưa có luồng sống
        if (emittedLinks == 0) {
            val pathSuffix = if (ref.type == "tv") {
                val s = ref.season ?: 1
                val e = ref.episode ?: 1
                "/tv/${ref.tmdbId}/$s/$e"
            } else {
                "/movie/${ref.tmdbId}"
            }

            val targets = listOf(
                Triple("Primary-WV", "$VIDSPARK_HOST$pathSuffix?autoplay=true", "$VIDSPARK_HOST/"),
                Triple("CDN-WV", "$VIDSPARK_CDN_HOST$pathSuffix?autoplay=true", "$VIDSPARK_CDN_HOST/")
            )

            for ((serverTag, pageUrl, refUrl) in targets) {
                try {
                    ClipboardLogger.logAndCopy("WebView ($serverTag)", "Sniff: $pageUrl")
                    val seenReqs = mutableListOf<String>()
                    val sniffed = sniffStream(pageUrl, refUrl, emittedUrls, seenReqs)

                    if (sniffed == null) {
                        ClipboardLogger.logAndCopy(
                            "Miss ($serverTag)",
                            "Không bắt được luồng (${seenReqs.size} reqs)"
                        )
                        continue
                    }

                    for (subUrl in sniffed.subs) {
                        if (seenSubUrls.add(subUrl)) {
                            subtitleCallback.invoke(SubtitleFile("Vidspark • WebVTT", subUrl))
                            subCount++
                        }
                    }

                    val cookie = runCatching { CookieManager.getInstance().getCookie(sniffed.url) ?: "" }.getOrDefault("")
                    val baseHdrs = sniffed.reqHeaders.toMutableMap().apply {
                        if (cookie.isNotEmpty()) put("Cookie", cookie)
                    }
                    val originHint = if (pageUrl.startsWith(VIDSPARK_CDN_HOST)) VIDSPARK_CDN_HOST else VIDSPARK_HOST

                    if (emittedUrls.add(sniffed.url) &&
                        verifyAndEmitLink(serverTag, sniffed.url, baseHdrs, originHint, callback)
                    ) {
                        emittedLinks++
                        break
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("Err ($serverTag)", "${e.message}")
                }
            }
        }

        ClipboardLogger.logAndCopy(
            if (emittedLinks > 0) "Done" else "Failed",
            "Đã nạp $emittedLinks luồng phát & $subCount phụ đề."
        )
        return emittedLinks > 0
    }
}

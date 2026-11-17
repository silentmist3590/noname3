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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class VidcoreProvider : MainAPI() {
    override var mainUrl = "https://vidcore.org"
    override var name = "Vidcore"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        // Có thể dán sẵn API Key SubDL miễn phí (lấy tại https://subdl.com/panel/api)
        // Hoặc nhập trực tiếp trong mục Cài đặt Plugin (biểu tượng bánh răng) trên CloudStream
        private const val DEFAULT_SUBDL_API_KEY = ""
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

    // Chặn quảng cáo/popunder trên trang embed vidcore.org
    private val blockedWebViewDomains = listOf(
        "/ads/open-evasion.js", "/ads/bf/", "/ads/go.php", "/ads/tag.php",
        "benchform.org", "apiguinee.org", "static.nresystems.com",
        "anemonytahua.com", "yxnjqmjye2e7778pio1.rest", "3hxoppqji4etwm1r4.cfd",
        "secutorlunts.cfd", "googletagmanager.com", "google-analytics.com",
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
        val dataUrl = "vidcore:$type:$id"

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
        Regex("""vidcore:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(input)?.let { m ->
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

        val canonical = "vidcore:${ref.type}:$tmdbId"

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
                            newEpisode("vidcore:tv:$tmdbId:$sNum:$epNum") {
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
    // Subtitle List: OpenSubtitles & SubDL (Chỉ hiển thị danh sách, không tải sẵn)
    // =========================================================================

    private data class SubtitleCandidate(
        val source: String,
        val langLabel: String,
        val url: String,
        val releaseHint: String
    )

    private fun resolveAppContext(): Context? {
        VidcoreSubtitleStore.getAppContext()?.let { return it }
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
                it.getSharedPreferences(VidcoreSubtitleStore.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(VidcoreSubtitleStore.KEY_SUBDL_API, "")?.trim() ?: ""
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

        // Gọi song song OpenSubtitles REST + OpenSubtitles V3 + SubDL API
        val candidates = listOf(0, 1, 2).amap { idx ->
            when (idx) {
                0 -> queryOpenSubtitlesRest(ref, imdbId)
                1 -> queryOpenSubtitlesStremio(ref, imdbId)
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

        val srcInfo = if (subdlKey.isNotBlank()) "OpenSubtitles + SubDL" else "OpenSubtitles"
        ClipboardLogger.logAndCopy("Subtitles", "Đã nạp danh sách $count phụ đề ($srcInfo)")
        return count
    }

    // =========================================================================
    // WebView Sniffer chuyên biệt cho vidcore.org (?autoplay=true)
    // =========================================================================

    private data class ProbeOutcome(
        val linkType: ExtractorLinkType?,
        val statusCode: Int,
        val workingHeaders: Map<String, String>
    )

    private data class VerifiedSniffResult(
        val url: String,
        val linkType: ExtractorLinkType,
        val statusCode: Int,
        val workingHeaders: Map<String, String>,
        val subs: List<String>
    )

    private fun buildHeaderCandidates(capturedHeaders: Map<String, String>): List<Map<String, String>> {
        val withCaptured = capturedHeaders.toMutableMap().apply {
            put("User-Agent", ua)
            if (!containsKey("Accept") && !containsKey("accept")) put("Accept", "*/*")
        }
        val withVidcore = withCaptured.toMutableMap().apply {
            put("Referer", "$mainUrl/")
            put("Origin", mainUrl)
        }
        val noRef = withCaptured.toMutableMap().apply {
            remove("Referer")
            remove("referer")
            remove("Origin")
            remove("origin")
        }
        return listOf(withCaptured, withVidcore, noRef).distinct()
    }

    private fun probeStreamBlocking(
        rawUrl: String,
        headerCandidates: List<Map<String, String>>
    ): ProbeOutcome {
        val isMp4 = Regex("""\.mp4(\?|$|#)""", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)
        var lastCode = 0

        for (baseHdrs in headerCandidates) {
            val probeHdrs = if (isMp4) baseHdrs + ("Range" to "bytes=0-1023") else baseHdrs
            var conn: HttpURLConnection? = null
            try {
                var currentUrl = rawUrl
                for (hop in 0..3) {
                    conn?.disconnect()
                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        requestMethod = "GET"
                        probeHdrs.forEach { (k, v) -> setRequestProperty(k, v) }
                    }
                    val code = conn.responseCode
                    lastCode = code
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")?.trim()
                        if (!loc.isNullOrBlank()) {
                            currentUrl = if (loc.startsWith("http", ignoreCase = true)) loc else URL(URL(currentUrl), loc).toString()
                            continue
                        }
                    }
                    break
                }

                val activeConn = conn ?: continue
                if (lastCode !in 200..299) continue

                val buf = ByteArray(2048)
                val readLen = activeConn.inputStream.use { input ->
                    var total = 0
                    while (total < buf.size) {
                        val n = input.read(buf, total, buf.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
                val snippet = String(buf, 0, readLen, Charsets.UTF_8).trimStart()

                if (isMp4) {
                    if (snippet.startsWith("<!doctype", ignoreCase = true) ||
                        snippet.startsWith("<html", ignoreCase = true) ||
                        snippet.startsWith("{")
                    ) {
                        continue
                    }
                    return ProbeOutcome(ExtractorLinkType.VIDEO, lastCode, baseHdrs)
                } else {
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
                    if (detected != null) {
                        return ProbeOutcome(detected, lastCode, baseHdrs)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) { }
            }
        }
        return ProbeOutcome(null, lastCode, headerCandidates.firstOrNull() ?: emptyMap())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVidcoreStream(
        pageUrl: String,
        refererUrl: String,
        seenRequests: MutableList<String>
    ): VerifiedSniffResult? {
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
                    val finished = AtomicBoolean(false)
                    val capturedSubs = Collections.synchronizedSet(LinkedHashSet<String>())
                    val probedUrls = Collections.synchronizedSet(HashSet<String>())

                    fun finish(res: VerifiedSniffResult?) {
                        if (!finished.compareAndSet(false, true)) return
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
                                document.querySelectorAll('a[target="_blank"]').forEach(function(el){
                                    el.style.pointerEvents = 'none';
                                    el.remove();
                                });
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
                        if (finished.get()) return
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
                        if (taps < 20) handler.postDelayed({ tap() }, 1800)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (finished.get()) return null
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

                            if (streamRegex.containsMatchIn(u) &&
                                !segmentOrAssetRegex.containsMatchIn(u) &&
                                probedUrls.add(u)
                            ) {
                                val capturedHdrs = HashMap<String, String>()
                                request.requestHeaders?.forEach { (k, v) -> capturedHdrs[k] = v }
                                val cookie = runCatching { CookieManager.getInstance().getCookie(u) ?: "" }.getOrDefault("")
                                if (cookie.isNotEmpty()) capturedHdrs["Cookie"] = cookie

                                // Probe trên thread phụ để bỏ qua các link chết (403/404) mà không đóng WebView sớm
                                Thread {
                                    if (finished.get()) return@Thread
                                    val candidates = buildHeaderCandidates(capturedHdrs)
                                    val outcome = probeStreamBlocking(u, candidates)
                                    if (finished.get()) return@Thread

                                    val detectedType = outcome.linkType
                                    if (detectedType != null) {
                                        val subsSnapshot = synchronized(capturedSubs) { capturedSubs.toList() }
                                        val verified = VerifiedSniffResult(
                                            url = u,
                                            linkType = detectedType,
                                            statusCode = outcome.statusCode,
                                            workingHeaders = outcome.workingHeaders,
                                            subs = subsSnapshot
                                        )
                                        handler.post { finish(verified) }
                                    } else {
                                        ClipboardLogger.logAndCopy(
                                            "Skip Dead",
                                            "HTTP ${outcome.statusCode} -> ${u.take(120)}"
                                        )
                                    }
                                }.apply { isDaemon = true }.start()
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (blockedWebViewDomains.any { u.contains(it, ignoreCase = true) }) {
                                if (seenRequests.size < 100) seenRequests.add("BLOCKED AD NAV: ${u.take(150)}")
                                return true
                            }
                            if (request.isForMainFrame) {
                                val allowMain = u.contains("vidcore.org", ignoreCase = true) ||
                                    u.contains("challenges.cloudflare.com", ignoreCase = true)
                                if (!allowMain && seenRequests.size < 100) {
                                    seenRequests.add("BLOCKED TOP NAV: ${u.take(150)}")
                                }
                                return !allowMain
                            }
                            return false
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

                    handler.postDelayed({ finish(null) }, 40_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to refererUrl))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =========================================================================
    // loadLinks (Chỉ lấy luồng từ vidcore.org)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "Vidcore loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        // 1. Nạp danh sách phụ đề (OpenSubtitles + SubDL) - không tải sẵn
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

        // 2. Chỉ bắt link video từ https://vidcore.org/embed/...?autoplay=true
        val embedUrl = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "$mainUrl/embed/tv/${ref.tmdbId}/$s/$e?autoplay=true"
        } else {
            "$mainUrl/embed/movie/${ref.tmdbId}?autoplay=true"
        }

        var emittedLinks = 0
        try {
            ClipboardLogger.logAndCopy("Vidcore", "Sniff: $embedUrl")
            val seenReqs = mutableListOf<String>()
            val sniffed = sniffVidcoreStream(embedUrl, "$mainUrl/", seenReqs)

            if (sniffed == null) {
                ClipboardLogger.logAndCopy(
                    "Miss",
                    "Không tìm thấy luồng sống từ vidcore.org (${seenReqs.size} reqs)"
                )
            } else {
                for (subUrl in sniffed.subs) {
                    if (seenSubUrls.add(subUrl)) {
                        subtitleCallback.invoke(SubtitleFile("Vidcore • WebVTT", subUrl))
                        subCount++
                    }
                }

                val rawUrl = sniffed.url
                val detectedType = sniffed.linkType
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
                val usedReferer = sniffed.workingHeaders["Referer"]
                    ?: sniffed.workingHeaders["referer"]
                    ?: ""

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Vidcore • Auto ($formatLabel)",
                        url = finalUrl,
                        type = detectedType
                    ) {
                        this.referer = usedReferer
                        this.quality = Qualities.P1080.value
                        this.headers = sniffed.workingHeaders
                    }
                )
                ClipboardLogger.logAndCopy(
                    "Live (Auto)",
                    "HTTP ${sniffed.statusCode} ($formatLabel) -> ${finalUrl.take(120)}"
                )
                emittedLinks++
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Err", "${e.message}")
        }

        ClipboardLogger.logAndCopy(
            if (emittedLinks > 0) "Done" else "Failed",
            "Đã nạp $emittedLinks luồng phát & $subCount phụ đề."
        )
        return emittedLinks > 0
    }
}

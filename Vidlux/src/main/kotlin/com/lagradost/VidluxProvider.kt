package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class VidluxProvider : MainAPI() {
    override var mainUrl = "https://vidlux.xyz"
    override var name = "Vidlux"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val DEFAULT_SUBDL_API_KEY = ""
        private const val VIDLUX_AES_SECRET = "vidlux-stream-encryption-2026-secure-key"

        // Các domain embed chính thức được VidLux công bố trên vidlux.xyz
        private val VIDLUX_HOSTS = listOf(
            "https://vidlux.xyz",
            "https://vidlux.site",
            "https://vidlux.online",
            "https://vidlux.top"
        )

        // Thứ tự server chính thức của VidLux: ưu tiên server phản hồi nhanh (<2-8s) trước,
        // trong khi nhánh Spider (02pcembed) chạy song song ngầm ngay từ t=0 để không chặn các server khác
        private val VIDLUX_SERVERS = listOf(
            "robot" to "Robot",
            "titan" to "Titan",
            "dragon" to "Dragon",
            "vidfast" to "Flux",
            "vixsrc" to "Aura",
            "astra" to "Astra",
            "vidrock" to "Nova",
            "dubai" to "Dubai",
            "quasar" to "Quasar",
            "rocket" to "Aether",
            "quilox" to "Quilox",
            "spider" to "Spider",
            "star" to "Star",
            "bolt" to "Bolt",
            "magic" to "Magic"
        )
    }

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

    // Chặn quảng cáo / popunder khi dùng WebView fallback trên trang embed Vidlux
    private val blockedWebViewDomains = listOf(
        "dcbbwymp1bhlf.cloudfront.net",
        "googletagmanager.com", "google-analytics.com",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
        "adsterra.com", "adcash.com", "rtmark.net", "histats.com",
        "static.cloudflareinsights.com", "disable-devtool"
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
    // TMDB Catalog & Metadata (Tương thích CloudStream 4.5.2)
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

    private fun rethrowIfCancellation(t: Throwable) {
        var cur: Throwable? = t
        while (cur != null) {
            if (cur.javaClass.name.endsWith("CancellationException")) {
                throw t
            }
            cur = cur.cause
        }
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
                }.onFailure { rethrowIfCancellation(it) }.getOrNull()

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
            }.onFailure { rethrowIfCancellation(it) }.getOrNull()

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
        val dataUrl = "vidlux:$type:$id"

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
    // Load Details (Movie / TV Series) - Không dùng rating/score để chạy mượt 4.5.2
    // =========================================================================

    private data class MediaRef(val type: String, val tmdbId: Int, val season: Int?, val episode: Int?)

    private fun parseRef(input: String): MediaRef? {
        Regex("""vidlux:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(input)?.let { m ->
            val id = m.groupValues[2].toIntOrNull() ?: return null
            return MediaRef(
                type = m.groupValues[1],
                tmdbId = id,
                season = m.groupValues[3].toIntOrNull(),
                episode = m.groupValues[4].toIntOrNull()
            )
        }

        Regex("""/(movie|tv)/(\d+)(?:/(\d+)/(\d+))?""").find(input)?.let { m ->
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

        val canonical = "vidlux:${ref.type}:$tmdbId"

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
                            newEpisode("vidlux:tv:$tmdbId:$sNum:$epNum") {
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
    // Subtitle List: OpenSubtitles (REST + Stremio) & SubDL + Vidlux Captions
    // =========================================================================

    private data class SubtitleCandidate(
        val source: String,
        val langLabel: String,
        val url: String,
        val releaseHint: String
    )

    private fun resolveAppContext(): Context? {
        VidluxSubtitleStore.getAppContext()?.let { return it }
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
                it.getSharedPreferences(VidluxSubtitleStore.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(VidluxSubtitleStore.KEY_SUBDL_API, "")?.trim() ?: ""
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

        val buckets = listOf(0, 1, 2).amap { kind ->
            when (kind) {
                0 -> queryOpenSubtitlesRest(ref, imdbId)
                1 -> queryOpenSubtitlesStremio(ref, imdbId)
                else -> querySubdl(ref, imdbId, subdlKey)
            }
        }

        val restSubs = buckets[0]
        val stremioSubs = buckets[1]
        val subdlSubs = buckets[2]

        val openSubsMerged = (restSubs + stremioSubs).let { combined ->
            val viOnly = combined.filter { it.langLabel.contains("viet", ignoreCase = true) }.take(8)
            val enOnly = combined.filter { !it.langLabel.contains("viet", ignoreCase = true) }.take(8)
            viOnly + enOnly
        }

        val all = subdlSubs + openSubsMerged
        val seenUrls = HashSet<String>()
        var count = 0

        for (cand in all) {
            if (!seenUrls.add(cand.url)) continue
            val shortRel = cand.releaseHint.take(28).trim()
            val displayLabel = if (shortRel.isNotBlank()) {
                "${cand.langLabel} (${cand.source}: $shortRel)"
            } else {
                "${cand.langLabel} (${cand.source})"
            }
            subtitleCallback.invoke(SubtitleFile(displayLabel, cand.url))
            count++
        }

        ClipboardLogger.logAndCopy(
            "Subtitles",
            "Đã nạp danh sách $count phụ đề (OpenSubtitles + SubDL)"
        )
        return count
    }

    // =========================================================================
    // VidLux AES-GCM Decryptor, Relay Encoder & API Stream Extractor (Strict VidLux-Only)
    // =========================================================================

    private fun decryptVidluxPayload(b64: String): JSONObject? {
        return runCatching {
            val raw = Base64.decode(b64.trim(), Base64.DEFAULT)
            if (raw.size <= 28) return null
            val iv = raw.copyOfRange(0, 12)
            val ciphertextAndTag = raw.copyOfRange(12, raw.size)
            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(VIDLUX_AES_SECRET.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ciphertextAndTag)
            JSONObject(String(plain, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun encodeVidluxRelay(rawUrl: String): String {
        val key = intArrayOf(118, 108, 120, 183, 114, 108, 183, 107, 57) // "vlx·rl·k9"
        val bytes = rawUrl.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            val k = key[i % key.size]
            val v = (bytes[i].toInt() and 0xFF) xor (k and 0xFF) xor ((31 * i) and 0xFF)
            out[i] = v.toByte()
        }
        val b64 = Base64.encodeToString(out, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "/api/relay?u=$b64"
    }

    private fun absVidluxUrl(host: String, raw: String): String {
        val clean = raw.trim()
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return clean
        }
        if (clean.startsWith("//")) return "https:$clean"
        val h = host.trimEnd('/')
        return if (clean.startsWith("/")) "$h$clean" else "$h/$clean"
    }

    private fun normalizeVidluxCaptionUrl(host: String, rawFile: String): String? {
        val clean = rawFile.trim()
        if (clean.isBlank()) return null
        if (clean.contains("/api/subtitles/proxy?", ignoreCase = true)) {
            val inner = Regex("""[?&]url=([^&]+)""").find(clean)?.groupValues?.getOrNull(1)?.let {
                runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
            }
            if (!inner.isNullOrBlank()) {
                if (inner.contains("opensubtitles.org", ignoreCase = true)) {
                    val direct = inner
                        .replace("/filead/", "/subencoding-utf8/filead/")
                        .replace("/file/", "/subencoding-utf8/file/")
                        .removeSuffix(".gz")
                    return appendSubExtFragment(direct, "srt")
                }
                return appendSubExtFragment(inner, "vtt")
            }
        }
        return appendSubExtFragment(absVidluxUrl(host, clean), "vtt")
    }

    private fun isJunkStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("lizer123.site")) return true
        return Regex(
            """(?:^|[/_.-])(onlyfans|xnxx|xvideos|pornhub|xhamster|spankbang|redtube|youporn|hentai|rule34)(?:[/_.-]|$)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)
    }

    private data class UnwrappedProxy(
        val url: String,
        val headers: Map<String, String>
    )

    private fun unwrapVidluxProxyUrl(rawUrl: String, baseHeaders: Map<String, String> = emptyMap()): UnwrappedProxy {
        var current = rawUrl.trim()
        val hdrs = baseHeaders.toMutableMap()
        for (hop in 0 until 4) {
            if (current.contains("/api/pcembed-media?", ignoreCase = true) ||
                current.contains("/api/moviesnow-proxy?", ignoreCase = true) ||
                current.contains(".workers.dev/proxy", ignoreCase = true) ||
                current.contains("animanga.fun", ignoreCase = true) && current.contains("/proxy", ignoreCase = true)
            ) {
                val inner = Regex("""[?&]url=([^&]+)""").find(current)?.groupValues?.getOrNull(1)?.let {
                    runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull()
                }
                if (!inner.isNullOrBlank() && inner.startsWith("http", ignoreCase = true)) {
                    current = inner
                    continue
                }
            }
            if (current.contains("02pcembed.site/v1/proxy", ignoreCase = true)) {
                val rawData = Regex("""[?&]data=([^&]+)""").find(current)?.groupValues?.getOrNull(1)
                if (!rawData.isNullOrBlank()) {
                    val decodedCandidates = listOfNotNull(
                        runCatching { URLDecoder.decode(rawData, "UTF-8") }.getOrNull(),
                        rawData
                    )
                    var unwrapped = false
                    for (candStr in decodedCandidates) {
                        val json = runCatching { JSONObject(candStr) }.getOrNull() ?: continue
                        val innerUrl = json.optString("url", "").trim()
                        if (innerUrl.startsWith("http", ignoreCase = true)) {
                            json.optJSONObject("headers")?.let { hj ->
                                val keys = hj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val v = hj.optString(k, "").trim()
                                    if (k.isNotBlank() && v.isNotBlank()) hdrs[k] = v
                                }
                            }
                            current = innerUrl
                            unwrapped = true
                            break
                        }
                    }
                    if (unwrapped) continue
                }
            }
            break
        }
        return UnwrappedProxy(current, hdrs)
    }

    private fun inferUpstreamReferer(serverId: String, streamUrl: String, streamHeaders: Map<String, String>): String {
        streamHeaders["Referer"]?.takeIf { it.startsWith("http") }?.let { return it }
        streamHeaders["referer"]?.takeIf { it.startsWith("http") }?.let { return it }
        val lower = streamUrl.lowercase()
        return when {
            lower.contains("afrilix.com") || lower.contains("playmanifest") -> "https://www.afrilix.com/"
            lower.contains("vidrock.net") || lower.contains("1shows.app") || lower.contains("47qzoobg8k") ||
                lower.contains("vercetti.org") || lower.contains("slowcloud.live") || lower.contains("fastcloud.live") ||
                lower.contains("lucidmechanism") || lower.contains("fcxmb.com") || lower.contains("hscow.com") ||
                Regex("""\borion\d*\.""").containsMatchIn(lower) || serverId == "vidrock" -> "https://vidrock.net/"
            lower.contains("vidfast.vc") || serverId == "vidfast" -> "https://vidfast.vc/"
            lower.contains("vixsrc.to") || serverId == "vixsrc" -> "https://vixsrc.to/"
            lower.contains("vidlink.pro") -> "https://vidlink.pro/"
            lower.contains("jenks426set") || lower.contains("gemma416okl") -> "https://gemma416okl.com/"
            lower.contains("vidzee.wtf") || serverId == "astra" -> "https://player.vidzee.wtf/"
            lower.contains("kissasia.cam") || lower.contains("zudrama.top") || lower.contains("dramasub.top") -> "https://kissasia.cam/"
            lower.contains("hakunaymatata") || lower.contains("hakunamatata") -> "https://fmoviesunblocked.net/"
            lower.contains("vidnest.fun") || lower.contains("pwcloud.animanga.fun") ||
                lower.contains("packingmolters.cfd") || lower.contains("streamflixserver") -> "https://vidnest.fun/"
            lower.contains("animanga.fun") || lower.contains("tiktoks.") -> "https://tiktoks.animanga.fun/"
            else -> Regex("""^(https?://[^/]+)""").find(streamUrl)?.groupValues?.getOrNull(1)?.let { "$it/" }
                ?: "https://vidlux.xyz/"
        }
    }

    private fun qualityRank(quality: String, type: String): Int {
        val q = quality.lowercase()
        val typeBonus = if (type.equals("hls", ignoreCase = true)) 0 else 10
        val resRank = when {
            q.contains("2160") || q.contains("4k") -> 0
            q.contains("1080") -> 1
            q.contains("720") -> 2
            q.contains("auto") -> 3
            q.contains("480") -> 4
            q.contains("360") -> 5
            else -> 6
        }
        return typeBonus + resRank
    }

    private data class VidluxEmbedSession(
        val host: String,
        val embedUrl: String,
        val requestToken: String
    )

    private suspend fun fetchVidluxSession(ref: MediaRef): VidluxEmbedSession? {
        val path = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "/embed/tv/${ref.tmdbId}/$s/$e"
        } else {
            "/embed/movie/${ref.tmdbId}"
        }

        val tokenRegex = Regex("""\\?"requestToken\\?"\s*:\s*\\?"([^"\\]+)\\?"""")
        for (host in VIDLUX_HOSTS) {
            val embedUrl = "$host$path"
            val res = runCatching {
                app.get(
                    embedUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Referer" to "$host/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    ),
                    timeout = 10L
                )
            }.onFailure { rethrowIfCancellation(it) }.getOrNull() ?: continue

            if (res.code !in 200..299) continue
            val tok = tokenRegex.find(res.text)?.groupValues?.getOrNull(1)?.trim()
            if (!tok.isNullOrBlank()) {
                return VidluxEmbedSession(host, embedUrl, tok)
            }
        }
        return null
    }

    private data class VidluxStreamItem(
        val serverId: String,
        val serverName: String,
        val subLabel: String,
        val fileUrl: String,
        val streamType: String,
        val quality: String,
        val headers: Map<String, String>,
        val builtInCaps: List<Pair<String, String>>,
        val audioLabel: String = "",
        val audioPriority: Int = 0
    )

    private data class ServerFetchResult(
        val httpCode: Int,
        val rateLimited: Boolean,
        val items: List<VidluxStreamItem>,
        val errorMsg: String
    )

    private fun defaultTimeoutForServer(serverId: String): Long {
        return when (serverId) {
            "spider" -> 15L
            "rocket", "quilox" -> 12L
            "star", "bolt", "magic" -> 8L
            else -> 9L
        }
    }

    private suspend fun fetchVidluxServerStreams(
        session: VidluxEmbedSession,
        ref: MediaRef,
        serverId: String,
        serverName: String,
        title: String,
        year: Int?,
        timeoutSec: Long = defaultTimeoutForServer(serverId)
    ): ServerFetchResult {
        val q = StringBuilder("id=${ref.tmdbId}&type=${ref.type}&_t=${URLEncoder.encode(session.requestToken, "UTF-8")}")
        if (ref.type == "tv") {
            q.append("&season=${ref.season ?: 1}&episode=${ref.episode ?: 1}")
        }
        if (serverId == "magic") {
            if (title.isNotBlank()) q.append("&title=${URLEncoder.encode(title, "UTF-8")}")
            if (year != null) q.append("&year=$year")
        }

        val apiUrl = "${session.host}/api/extract/$serverId?$q"
        val resp = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to session.embedUrl,
                    "Origin" to session.host,
                    "Accept" to "application/json, text/plain, */*"
                ),
                timeout = timeoutSec
            )
        }.getOrElse { ex ->
            rethrowIfCancellation(ex)
            return ServerFetchResult(0, false, emptyList(), ex.message ?: "Request failed")
        }

        if (resp.code == 429) {
            return ServerFetchResult(429, true, emptyList(), "HTTP 429 Rate Limited")
        }

        var json = runCatching { JSONObject(resp.text) }.getOrNull()
            ?: return ServerFetchResult(resp.code, false, emptyList(), "Non-JSON HTTP ${resp.code}")

        if (json.optBoolean("encrypted", false) && json.has("data")) {
            val decrypted = decryptVidluxPayload(json.optString("data", ""))
            if (decrypted == null) {
                return ServerFetchResult(resp.code, false, emptyList(), "AES-GCM decrypt failed")
            }
            json = decrypted
        }

        val err = json.optString("error", "").trim()
        val rawStreams = json.optJSONArray("streams")
        if (rawStreams == null || rawStreams.length() == 0) {
            return ServerFetchResult(resp.code, false, emptyList(), err.ifBlank { "No streams (HTTP ${resp.code})" })
        }

        val caps = mutableListOf<Pair<String, String>>()
        val capArr = json.optJSONArray("captions")
        if (capArr != null) {
            for (i in 0 until capArr.length()) {
                val c = capArr.optJSONObject(i) ?: continue
                val f = c.optString("file", "").ifBlank { c.optString("url", "") }.trim()
                val label = c.optString("label", "").ifBlank { c.optString("language", "Subtitle") }.trim()
                val norm = normalizeVidluxCaptionUrl(session.host, f) ?: continue
                caps.add(label to norm)
            }
        }

        val out = mutableListOf<VidluxStreamItem>()
        for (i in 0 until rawStreams.length()) {
            val st = rawStreams.optJSONObject(i) ?: continue
            val file = st.optString("file", "").trim()
            if (file.isBlank()) continue
            val stType = st.optString("type", "").lowercase().trim()
            val stTitle = st.optString("title", "").trim()
            val stQual = st.optString("quality", "").trim()

            val hdrsMap = mutableMapOf<String, String>()
            val hdrsJson = st.optJSONObject("headers")
            if (hdrsJson != null) {
                val keys = hdrsJson.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = hdrsJson.optString(k, "").trim()
                    if (k.isNotBlank() && v.isNotBlank()) hdrsMap[k] = v
                }
            }

            out.add(
                VidluxStreamItem(
                    serverId = serverId,
                    serverName = serverName,
                    subLabel = stTitle,
                    fileUrl = file,
                    streamType = stType,
                    quality = stQual,
                    headers = hdrsMap,
                    builtInCaps = caps
                )
            )
        }

        val sorted = out.sortedBy { qualityRank(it.quality, it.streamType) }
        return ServerFetchResult(resp.code, false, sorted, err)
    }

    // Nhánh client-side Spider chính thức của VidLux (port đúng hàm eJ/eQ/eY trong player JS)
    // Không tốn quota rate-limit của vidlux.xyz
    private fun classifyAudioTrack(audioArr: JSONArray?, originalLang: String): Pair<String, Int> {
        if (audioArr == null || audioArr.length() == 0) return "" to 0
        val labels = mutableListOf<String>()
        var hasPreferred = false
        var hasDub = false
        val orig = originalLang.lowercase().trim()
        val dubRegex = Regex(
            """kannada|tamil|telugu|malayalam|hindi|bengali|marathi|punjabi|gujarati|odia|bhojpuri|spanish|french|german|italian|portuguese|russian|arabic|turkish""",
            RegexOption.IGNORE_CASE
        )

        for (i in 0 until audioArr.length()) {
            val a = audioArr.optJSONObject(i) ?: continue
            val lang = a.optString("language", "").lowercase().trim()
            val lbl = a.optString("label", "").trim()
            val disp = lbl.ifBlank { lang }
            if (disp.isNotBlank()) labels.add(disp)

            val isEnglish = lang == "eng" || lang == "en" || lbl.contains("english", ignoreCase = true)
            val matchesOrig = orig.isNotBlank() && (lang == orig || lang.startsWith(orig) || orig.startsWith(lang))
            if (isEnglish || matchesOrig) {
                hasPreferred = true
            } else if (dubRegex.containsMatchIn(lbl) || dubRegex.containsMatchIn(lang)) {
                hasDub = true
            }
        }
        val labelText = labels.distinct().take(2).joinToString(", ")
        val prio = when {
            hasPreferred -> 0
            hasDub -> 2
            else -> 1
        }
        return labelText to prio
    }

    private class SpiderBackgroundHandle(
        private val cancelled: AtomicBoolean,
        private val remainingWorkers: AtomicInteger,
        private val lock: Any = Any(),
        private val results: MutableList<VidluxStreamItem> = mutableListOf(),
        private val activeConns: MutableList<HttpURLConnection> = mutableListOf(),
        private var waiter: (() -> Unit)? = null
    ) {
        fun isCancelled(): Boolean = cancelled.get()

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                synchronized(lock) {
                    for (c in activeConns) {
                        runCatching { c.disconnect() }
                    }
                    activeConns.clear()
                    waiter = null
                }
            }
        }

        fun takeReadyItems(): List<VidluxStreamItem> {
            synchronized(lock) {
                if (results.isEmpty()) return emptyList()
                val copy = results.toList()
                results.clear()
                return copy
            }
        }

        fun isDone(): Boolean = remainingWorkers.get() <= 0

        fun addResult(items: List<VidluxStreamItem>) {
            var cb: (() -> Unit)? = null
            synchronized(lock) {
                if (!cancelled.get() && items.isNotEmpty()) {
                    results.addAll(items)
                }
                val rem = remainingWorkers.decrementAndGet()
                if ((items.isNotEmpty() && !cancelled.get()) || rem <= 0) {
                    cb = waiter
                    waiter = null
                }
            }
            cb?.invoke()
        }

        fun registerConn(conn: HttpURLConnection) {
            synchronized(lock) {
                if (cancelled.get()) {
                    runCatching { conn.disconnect() }
                } else {
                    activeConns.add(conn)
                }
            }
        }

        fun unregisterConn(conn: HttpURLConnection) {
            synchronized(lock) {
                activeConns.remove(conn)
            }
        }

        fun setWaiter(cb: (() -> Unit)?): Boolean {
            synchronized(lock) {
                if (cb == null) {
                    waiter = null
                    return false
                }
                if (results.isNotEmpty() || remainingWorkers.get() <= 0 || cancelled.get()) {
                    waiter = null
                    return false
                }
                waiter = cb
                return true
            }
        }
    }

    private fun parsePcembedSourcesJson(
        sessionHost: String,
        providerLabel: String,
        rawText: String,
        originalLang: String
    ): List<VidluxStreamItem> {
        val json = runCatching { JSONObject(rawText) }.getOrNull() ?: run {
            ClipboardLogger.logAndCopy("Spider ($providerLabel)", "JSON parse error")
            return emptyList()
        }
        val sources = json.optJSONArray("sources")
        if (sources == null || sources.length() == 0) {
            val diagMsg = json.optJSONArray("diagnostics")?.optJSONObject(0)?.optString("message", "")?.trim().orEmpty()
            ClipboardLogger.logAndCopy(
                "Spider ($providerLabel)",
                if (diagMsg.isNotBlank()) diagMsg.take(140) else "0 sources"
            )
            return emptyList()
        }

        val caps = mutableListOf<Pair<String, String>>()
        val subsArr = json.optJSONArray("subtitles")
        if (subsArr != null) {
            for (i in 0 until subsArr.length()) {
                val sub = subsArr.optJSONObject(i) ?: continue
                val rawSub = sub.optString("url", "").ifBlank { sub.optString("file", "") }.trim()
                val subLabel = sub.optString("label", "").ifBlank { sub.optString("language", "Subtitle") }.trim()
                val norm = normalizeVidluxCaptionUrl(sessionHost, rawSub) ?: continue
                caps.add(subLabel to norm)
            }
        }

        val items = mutableListOf<VidluxStreamItem>()
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val rawUrl = src.optString("url", "").trim()
            if (rawUrl.isBlank() || isJunkStreamUrl(rawUrl)) continue

            val unwrapped = unwrapVidluxProxyUrl(rawUrl)
            if (unwrapped.url.contains("02pcembed.site", ignoreCase = true) ||
                isJunkStreamUrl(unwrapped.url)
            ) {
                continue
            }

            val stType = src.optString("type", "").lowercase().trim()
            val isHls = stType == "hls" ||
                unwrapped.url.contains(".m3u8", ignoreCase = true) ||
                unwrapped.url.contains("playmanifest", ignoreCase = true) ||
                unwrapped.url.contains("/hls/", ignoreCase = true) ||
                unwrapped.url.contains("vercetti.org", ignoreCase = true) ||
                unwrapped.url.contains("slowcloud.live", ignoreCase = true) ||
                unwrapped.url.contains("fastcloud.live", ignoreCase = true) ||
                unwrapped.url.contains("lucidmechanism", ignoreCase = true) ||
                unwrapped.url.contains("fcxmb.com", ignoreCase = true) ||
                unwrapped.url.contains("hscow.com", ignoreCase = true) ||
                unwrapped.url.contains("1shows.app", ignoreCase = true)
            val isMp4 = !isHls && (stType == "mp4" || stType == "mkv" ||
                Regex("""\.(mp4|mkv|webm)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(unwrapped.url))

            val hdrs = unwrapped.headers.toMutableMap()
            if (providerLabel == "Vidrock") {
                if (!hdrs.containsKey("Referer") && !hdrs.containsKey("referer")) {
                    hdrs["Referer"] = "https://vidrock.net/"
                }
                if (!hdrs.containsKey("Origin") && !hdrs.containsKey("origin")) {
                    hdrs["Origin"] = "https://vidrock.net"
                }
            }

            val mirrorName = src.optJSONObject("provider")?.optString("name", "")?.trim()?.ifBlank { providerLabel }
                ?: providerLabel
            val (audioLbl, audioPrio) = classifyAudioTrack(src.optJSONArray("audioTracks"), originalLang)
            val displaySub = if (audioLbl.isNotBlank()) "$mirrorName • $audioLbl" else mirrorName
            val quality = src.optString("quality", "").ifBlank { "auto" }.trim()

            items.add(
                VidluxStreamItem(
                    serverId = if (providerLabel == "Vidrock") "vidrock" else "spider",
                    serverName = "Spider",
                    subLabel = displaySub,
                    fileUrl = unwrapped.url,
                    streamType = if (isMp4) "mp4" else if (isHls) "hls" else stType,
                    quality = quality,
                    headers = hdrs,
                    builtInCaps = caps,
                    audioLabel = audioLbl,
                    audioPriority = audioPrio
                )
            )
        }
        return items
    }

    private fun rankAndFilterSpiderItems(items: List<VidluxStreamItem>): List<VidluxStreamItem> {
        val unique = items.distinctBy { it.fileUrl }
        val hasPrimaryLang = unique.any { it.audioPriority == 0 }
        val filtered = if (hasPrimaryLang) unique.filter { it.audioPriority <= 1 } else unique

        return filtered
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<VidluxStreamItem>>(
                    { it.value.audioPriority },
                    {
                        val q = it.value.quality
                        if (q.equals("auto", ignoreCase = true) || q.isBlank()) 1 else qualityRank(q, it.value.streamType)
                    },
                    { it.index }
                )
            )
            .map { it.value }
    }

    private fun startSpiderBackground(
        sessionHost: String,
        ref: MediaRef,
        originalLang: String
    ): SpiderBackgroundHandle {
        val endpoints = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            listOf(
                "KissAsia" to "https://02pcembed.site/v1/kissasia/tv/${ref.tmdbId}/seasons/$s/episodes/$e",
                "Vidrock" to "https://02pcembed.site/v1/vidrock/tv/${ref.tmdbId}/seasons/$s/episodes/$e"
            )
        } else {
            listOf(
                "Afrilix" to "https://02pcembed.site/v1/afrilix/movies/${ref.tmdbId}",
                "Vidrock" to "https://02pcembed.site/v1/vidrock/movies/${ref.tmdbId}"
            )
        }

        val handle = SpiderBackgroundHandle(
            cancelled = AtomicBoolean(false),
            remainingWorkers = AtomicInteger(endpoints.size)
        )

        for ((providerLabel, url) in endpoints) {
            Thread {
                var conn: HttpURLConnection? = null
                var parsed = emptyList<VidluxStreamItem>()
                try {
                    if (!handle.isCancelled()) {
                        val c = (URL(url).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = true
                            connectTimeout = 12_000
                            readTimeout = 60_000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", ua)
                            setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
                            setRequestProperty("Referer", "$sessionHost/")
                            setRequestProperty("Origin", sessionHost)
                        }
                        conn = c
                        handle.registerConn(c)
                        val code = c.responseCode
                        val body = runCatching {
                            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
                            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                        }.getOrDefault("")

                        if (!handle.isCancelled()) {
                            if (code in 200..299 && body.trimStart().startsWith("{")) {
                                parsed = parsePcembedSourcesJson(sessionHost, providerLabel, body, originalLang)
                            } else {
                                ClipboardLogger.logAndCopy("Spider ($providerLabel)", "HTTP $code: ${body.take(120)}")
                            }
                        }
                    }
                } catch (ex: Exception) {
                    if (!handle.isCancelled()) {
                        ClipboardLogger.logAndCopy("Spider ($providerLabel)", ex.message ?: "Request failed")
                    }
                } finally {
                    conn?.let {
                        handle.unregisterConn(it)
                        runCatching { it.disconnect() }
                    }
                    handle.addResult(parsed)
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        return handle
    }

    private suspend fun awaitSpiderBatch(handle: SpiderBackgroundHandle, maxWaitMs: Long) {
        if (maxWaitMs <= 0L) return
        suspendCoroutine<Unit> { cont ->
            val resumed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                handle.setWaiter(null)
                if (resumed.compareAndSet(false, true)) {
                    cont.resume(Unit)
                }
            }
            val needWait = handle.setWaiter {
                handler.removeCallbacks(timeoutRunnable)
                if (resumed.compareAndSet(false, true)) {
                    cont.resume(Unit)
                }
            }
            if (!needWait) {
                if (resumed.compareAndSet(false, true)) {
                    cont.resume(Unit)
                }
            } else {
                handler.postDelayed(timeoutRunnable, maxWaitMs)
            }
        }
    }

    // =========================================================================
    // WebView Fallback Sniffer (chỉ dùng khi Direct API Vidlux fail)
    // =========================================================================

    private data class SniffResult(
        val url: String,
        val reqHeaders: Map<String, String>,
        val subs: List<String>
    )

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffVidluxWebView(
        pageUrl: String,
        refererUrl: String,
        tmdbJsonText: String?,
        seenRequests: MutableList<String>,
        timeoutMs: Long = 45_000L
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val act = CommonActivity.activity
                val ctx = resolveAppContext()
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("WebView", "Không có Context để tạo WebView")
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
                        """\.m3u8|\.mpd|master\.txt|playManifest|/pl/[A-Za-z0-9_-]{30,}|/pli/[A-Za-z0-9_-]{30,}|/plc/[A-Za-z0-9_-]{30,}|/api/(?:vixsrc-proxy|robot-proxy|dragon-proxy|moviesnow-proxy|moviebox-proxy|moviebox-play|dubai-proxy|cinemaos-proxy|proxy|relay|pcembed-media)\b|02pcembed\.site/v1/proxy""",
                        RegexOption.IGNORE_CASE
                    )
                    val mp4Regex = Regex("""\.mp4(\?|$|#)|\.mkv(\?|$|#)""", RegexOption.IGNORE_CASE)
                    val subRegex = Regex("""\.(vtt|srt)(\?|$)""", RegexOption.IGNORE_CASE)
                    val assetIgnoreRegex = Regex(
                        """\.(css|js|png|jpe?g|gif|svg|ico|woff2?|ttf|ts|m4s)(\?|$)""",
                        RegexOption.IGNORE_CASE
                    )

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
                            try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}};};}catch(e){}
                            var v=document.querySelector('video');
                            if(v){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}}
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
                        if (taps < 16) handler.postDelayed({ tap() }, 1800)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http", ignoreCase = true)) return null

                            // VidLux chờ api.themoviedb.org trả về mới bật cờ gọi extract; ở VN bị chặn DNS nên trả luôn JSON từ TMDB Proxy
                            if (u.contains("api.themoviedb.org/3/", ignoreCase = true) && !tmdbJsonText.isNullOrBlank()) {
                                val bytes = tmdbJsonText.toByteArray(Charsets.UTF_8)
                                return WebResourceResponse(
                                    "application/json",
                                    "utf-8",
                                    200,
                                    "OK",
                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                    ByteArrayInputStream(bytes)
                                )
                            }

                            if (blockedWebViewDomains.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!assetIgnoreRegex.containsMatchIn(u) && seenRequests.size < 100) {
                                seenRequests.add(u.take(200))
                            }

                            if (subRegex.containsMatchIn(u) && !u.contains("thumbnails.vtt", ignoreCase = true)) {
                                capturedSubs.add(u)
                            }

                            val isApiMeta = u.contains("/api/extract/", ignoreCase = true) ||
                                u.contains("/api/subtitles", ignoreCase = true) ||
                                u.contains("/cdn-cgi/", ignoreCase = true)

                            if (!isApiMeta &&
                                !assetIgnoreRegex.containsMatchIn(u) &&
                                (streamRegex.containsMatchIn(u) || mp4Regex.containsMatchIn(u))
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
                            val allow = u.contains("vidlux.", ignoreCase = true) ||
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
                            handler.postDelayed({ tap() }, 2000)
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

                    handler.postDelayed({ finish(null) }, timeoutMs)
                    wv.loadUrl(pageUrl, mapOf("Referer" to refererUrl))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("WebView Err", e.stackTraceToString().take(600))
                    cont.resume(null)
                }
            }
        }
    }

    // =========================================================================
    // Pre-Flight Stream Probe (Direct + VidLux Proxy Candidates)
    // =========================================================================

    private data class ProbeCandidate(
        val url: String,
        val headers: Map<String, String>
    )

    private data class ProbeOutcome(
        val linkType: ExtractorLinkType?,
        val statusCode: Int,
        val finalUrl: String,
        val workingHeaders: Map<String, String>
    )

    private fun buildProbeCandidates(
        vidluxHost: String,
        embedUrl: String,
        serverId: String,
        rawUrl: String,
        typeHint: String,
        streamHeaders: Map<String, String>
    ): List<ProbeCandidate> {
        val candidates = mutableListOf<ProbeCandidate>()
        val unwrapped = unwrapVidluxProxyUrl(absVidluxUrl(vidluxHost, rawUrl), streamHeaders)
        val resolved = unwrapped.url
        val effHeaders = unwrapped.headers
        val isVidluxProxy = resolved.startsWith("$vidluxHost/api/", ignoreCase = true) ||
            resolved.contains("/api/vixsrc-proxy", ignoreCase = true) ||
            resolved.contains("/api/robot-proxy", ignoreCase = true) ||
            resolved.contains("/api/dragon-proxy", ignoreCase = true) ||
            resolved.contains("/api/moviesnow-proxy", ignoreCase = true) ||
            resolved.contains("/api/moviebox-proxy", ignoreCase = true) ||
            resolved.contains("/api/moviebox-play", ignoreCase = true) ||
            resolved.contains("/api/dubai-proxy", ignoreCase = true) ||
            resolved.contains("/api/cinemaos-proxy", ignoreCase = true) ||
            resolved.contains("/api/proxy?", ignoreCase = true) ||
            resolved.contains("/api/relay", ignoreCase = true) ||
            resolved.contains("/api/pcembed-media", ignoreCase = true)

        if (isVidluxProxy) {
            val vlxHeaders = mapOf(
                "User-Agent" to ua,
                "Referer" to embedUrl,
                "Origin" to vidluxHost
            )
            candidates.add(ProbeCandidate(resolved, vlxHeaders))
            return candidates
        }

        // 1. Direct stream URL với header trả về từ server / suy luận từ upstream
        val upstreamRef = inferUpstreamReferer(serverId, resolved, effHeaders)
        val upstreamOrigin = upstreamRef.trimEnd('/')
        val directHeaders = effHeaders.toMutableMap().apply {
            put("User-Agent", ua)
            put("Referer", upstreamRef)
            put("Origin", upstreamOrigin)
        }
        candidates.add(ProbeCandidate(resolved, directHeaders))

        // 2. Direct stream URL không kèm Referer/Origin (một số CDN MP4 chặn Referer lạ)
        val noRefHeaders = effHeaders.toMutableMap().apply {
            put("User-Agent", ua)
            remove("Referer")
            remove("referer")
            remove("Origin")
            remove("origin")
        }
        candidates.add(ProbeCandidate(resolved, noRefHeaders))

        // 3. Nếu direct bị 403/timeout, thử đi qua chính proxy của VidLux (/api/vixsrc-proxy hoặc /api/dubai-proxy hoặc /api/relay)
        val vlxHeaders = mapOf(
            "User-Agent" to ua,
            "Referer" to embedUrl,
            "Origin" to vidluxHost
        )
        if (serverId == "dubai") {
            val dubaiProxy = "$vidluxHost/api/dubai-proxy?url=${URLEncoder.encode(resolved, "UTF-8")}"
            candidates.add(ProbeCandidate(dubaiProxy, vlxHeaders))
        } else if (serverId == "quasar" || serverId == "vidstuck" || typeHint == "dash") {
            val relayProxy = "$vidluxHost${encodeVidluxRelay(resolved)}"
            candidates.add(ProbeCandidate(relayProxy, vlxHeaders))
        } else {
            val vixProxy = "$vidluxHost/api/vixsrc-proxy?referer=${URLEncoder.encode(upstreamRef, "UTF-8")}&url=${URLEncoder.encode(resolved, "UTF-8")}"
            candidates.add(ProbeCandidate(vixProxy, vlxHeaders))
        }

        if (rawUrl != resolved && rawUrl.contains("02pcembed.site/v1/proxy", ignoreCase = true)) {
            candidates.add(ProbeCandidate(absVidluxUrl(vidluxHost, rawUrl), noRefHeaders))
        }

        return candidates.distinctBy { it.url to it.headers }
    }

    private suspend fun probeStreamCandidates(
        candidates: List<ProbeCandidate>,
        typeHint: String
    ): ProbeOutcome {
        var lastCode = 0
        for (cand in candidates) {
            val isMp4 = typeHint == "mp4" || typeHint == "mkv" ||
                Regex("""\.(mp4|mkv|webm)(\?|$|#)""", RegexOption.IGNORE_CASE).containsMatchIn(cand.url)
            val probeHdrs = if (isMp4) cand.headers + ("Range" to "bytes=0-1023") else cand.headers

            val outcome = runCatching {
                val resp = app.get(cand.url, headers = probeHdrs, timeout = 10L)
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
                        ProbeOutcome(ExtractorLinkType.VIDEO, resp.code, cand.url, cand.headers)
                    }
                } else {
                    val snippet = resp.text.take(1500).trimStart()
                    val detected = when {
                        snippet.contains("#EXTM3U") -> ExtractorLinkType.M3U8
                        snippet.contains("<MPD", ignoreCase = true) -> ExtractorLinkType.DASH
                        snippet.startsWith("<!doctype", ignoreCase = true) ||
                            snippet.startsWith("<html", ignoreCase = true) ||
                            snippet.startsWith("{") -> null
                        cand.url.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        cand.url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        else -> null
                    }
                    detected?.let { ProbeOutcome(it, resp.code, cand.url, cand.headers) }
                }
            }.onFailure { rethrowIfCancellation(it) }.getOrNull()

            if (outcome != null) return outcome
        }
        val first = candidates.firstOrNull()
        return ProbeOutcome(null, lastCode, first?.url ?: "", first?.headers ?: emptyMap())
    }

    private suspend fun verifyAndEmitLink(
        vidluxHost: String,
        embedUrl: String,
        item: VidluxStreamItem,
        emittedUrls: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val candidates = buildProbeCandidates(
            vidluxHost = vidluxHost,
            embedUrl = embedUrl,
            serverId = item.serverId,
            rawUrl = item.fileUrl,
            typeHint = item.streamType,
            streamHeaders = item.headers
        )
        val probe = probeStreamCandidates(candidates, item.streamType)
        val detectedType = probe.linkType
        if (detectedType == null) {
            ClipboardLogger.logAndCopy(
                "Dead (${item.serverName})",
                "HTTP ${probe.statusCode} -> ${probe.finalUrl.take(120)}"
            )
            return false
        }

        if (!emittedUrls.add(probe.finalUrl)) return false

        val finalUrl = when (detectedType) {
            ExtractorLinkType.M3U8 ->
                if (probe.finalUrl.contains(".m3u8", ignoreCase = true)) probe.finalUrl else "${probe.finalUrl}#file.m3u8"
            ExtractorLinkType.DASH ->
                if (probe.finalUrl.contains(".mpd", ignoreCase = true)) probe.finalUrl else "${probe.finalUrl}#file.mpd"
            else -> probe.finalUrl
        }

        val formatLabel = when (detectedType) {
            ExtractorLinkType.DASH -> "DASH"
            ExtractorLinkType.M3U8 -> "HLS"
            else -> "MP4"
        }
        val qualValue = when {
            item.quality.contains("2160") || item.quality.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            item.quality.contains("1080") -> Qualities.P1080.value
            item.quality.contains("720") -> Qualities.P720.value
            item.quality.contains("480") -> Qualities.P480.value
            item.quality.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
        val subTag = buildString {
            append(item.serverName)
            if (item.subLabel.isNotBlank() && !item.subLabel.equals(item.serverName, ignoreCase = true)) {
                append(" / ${item.subLabel}")
            }
            if (item.quality.isNotBlank() && !item.quality.equals("auto", ignoreCase = true)) {
                append(" ${item.quality}")
            }
        }
        val usedReferer = probe.workingHeaders["Referer"] ?: probe.workingHeaders["referer"] ?: "$vidluxHost/"

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "Vidlux • $subTag ($formatLabel)",
                url = finalUrl,
                type = detectedType
            ) {
                this.referer = usedReferer
                this.quality = qualValue
                this.headers = probe.workingHeaders
            }
        )
        ClipboardLogger.logAndCopy(
            "Live ($subTag)",
            "HTTP ${probe.statusCode} ($formatLabel) -> ${finalUrl.take(120)}"
        )
        return true
    }

    // =========================================================================
    // loadLinks (Chỉ lấy server từ Vidlux, không dùng server backup bên ngoài)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadStartMs = SystemClock.elapsedRealtime()
        val tier1DeadlineMs = loadStartMs + 68_000L

        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "Vidlux loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        // 1. Lấy metadata TMDB (IMDB ID + Title/Year cho Magic server) & nạp danh sách phụ đề
        var imdbId = imdbCache[ref.tmdbId] ?: ""
        var mediaTitle = ""
        var mediaYear: Int? = null
        var origLang = ""
        val details = tmdbGet("/${ref.type}/${ref.tmdbId}?append_to_response=external_ids")
        if (details != null) {
            mediaTitle = details.optString(if (ref.type == "tv") "name" else "title", "").trim()
            mediaYear = parseYear(details.optString(if (ref.type == "tv") "first_air_date" else "release_date", ""))
            origLang = details.optString("original_language", "").trim()
            if (imdbId.isBlank()) {
                imdbId = details.optString("imdb_id", "").ifBlank {
                    details.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
                }
                if (imdbId.startsWith("tt")) {
                    imdbCache[ref.tmdbId] = imdbId
                }
            }
        }

        var subCount = fetchSubtitleList(ref, imdbId, subtitleCallback)
        val seenSubUrls = HashSet<String>()

        // 2. Tier 1: Chạy song song nhánh Spider (02pcembed) ngầm ngay từ đầu + gọi các server Vidlux nhanh trước
        // Khống chế toàn bộ Tier 1 <= 68s để chừa ~45s cho WebView Fallback, không chạm ngưỡng 120s của CloudStream
        var emittedLinks = 0
        val emittedUrls = HashSet<String>()

        suspend fun emitStreamBatch(host: String, embedUrl: String, items: List<VidluxStreamItem>): Int {
            var live = 0
            for (item in items) {
                for ((capLabel, capUrl) in item.builtInCaps) {
                    if (seenSubUrls.add(capUrl)) {
                        subtitleCallback.invoke(SubtitleFile("Vidlux • $capLabel", capUrl))
                        subCount++
                    }
                }
                if (live >= 3 || emittedLinks >= 3) break
                if (verifyAndEmitLink(host, embedUrl, item, emittedUrls, callback)) {
                    live++
                    emittedLinks++
                }
            }
            return live
        }

        val session = fetchVidluxSession(ref)
        if (session == null) {
            ClipboardLogger.logAndCopy("Session", "Không lấy được requestToken từ các domain Vidlux")
        } else {
            ClipboardLogger.logAndCopy("Session", "Host: ${session.host} | Embed: ${session.embedUrl}")

            val spiderHandle = startSpiderBackground(session.host, ref, origLang)
            var workingServers = 0

            try {
                for ((serverId, serverName) in VIDLUX_SERVERS) {
                    // Kiểm tra ngay nếu nhánh Spider ngầm (02pcembed) đã trả về luồng hợp lệ
                    val readyBg = rankAndFilterSpiderItems(spiderHandle.takeReadyItems())
                    if (readyBg.isNotEmpty()) {
                        ClipboardLogger.logAndCopy("Spider (Browser)", "Tìm thấy ${readyBg.size} nguồn từ 02pcembed")
                        if (emitStreamBatch(session.host, session.embedUrl, readyBg) > 0) {
                            workingServers++
                        }
                    }

                    if (emittedLinks >= 3 || workingServers >= 2) break

                    val remSec = (tier1DeadlineMs - SystemClock.elapsedRealtime()) / 1000L
                    if (remSec < 3L) {
                        ClipboardLogger.logAndCopy("Tier1", "Hết ngân sách 68s, chuyển sang kiểm tra WebView")
                        break
                    }

                    val timeoutCap = minOf(defaultTimeoutForServer(serverId), remSec)
                    val res = fetchVidluxServerStreams(
                        session = session,
                        ref = ref,
                        serverId = serverId,
                        serverName = serverName,
                        title = mediaTitle,
                        year = mediaYear,
                        timeoutSec = timeoutCap
                    )

                    if (res.rateLimited && res.items.isEmpty()) {
                        ClipboardLogger.logAndCopy("RateLimit ($serverName)", res.errorMsg)
                        break
                    }

                    if (res.items.isEmpty()) {
                        ClipboardLogger.logAndCopy("Skip ($serverName)", res.errorMsg)
                        continue
                    }

                    val serverLive = emitStreamBatch(session.host, session.embedUrl, res.items)
                    if (serverLive > 0) {
                        workingServers++
                    }
                    if (res.rateLimited) break
                }

                // Nếu vẫn chưa có link nào và nhánh Spider ngầm vẫn đang chạy, chờ tối đa tới hết ngưỡng 68s
                if (emittedLinks == 0) {
                    val alreadyReady = rankAndFilterSpiderItems(spiderHandle.takeReadyItems())
                    if (alreadyReady.isNotEmpty()) {
                        ClipboardLogger.logAndCopy("Spider (Browser)", "Tìm thấy ${alreadyReady.size} nguồn từ 02pcembed")
                        emitStreamBatch(session.host, session.embedUrl, alreadyReady)
                    }
                }

                if (emittedLinks == 0 && !spiderHandle.isDone()) {
                    val waitMs = (tier1DeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    if (waitMs > 500L) {
                        awaitSpiderBatch(spiderHandle, waitMs)
                        val lateReady = rankAndFilterSpiderItems(spiderHandle.takeReadyItems())
                        if (lateReady.isNotEmpty()) {
                            ClipboardLogger.logAndCopy("Spider (Browser)", "Tìm thấy ${lateReady.size} nguồn từ 02pcembed")
                            emitStreamBatch(session.host, session.embedUrl, lateReady)
                        }
                    }
                }
            } finally {
                spiderHandle.cancel()
            }
        }

        // 3. Tier 2: WebView Sniffer trực tiếp trên trang embed của Vidlux (giới hạn trong quỹ 112s tổng thể)
        if (emittedLinks == 0) {
            val activeHost = session?.host ?: VIDLUX_HOSTS.first()
            val pathSuffix = if (ref.type == "tv") {
                "/embed/tv/${ref.tmdbId}/${ref.season ?: 1}/${ref.episode ?: 1}"
            } else {
                "/embed/movie/${ref.tmdbId}"
            }
            val wvUrl = "$activeHost$pathSuffix?server=spider&autoplay=true&preview=1"
            val wvTimeoutMs = (112_000L - (SystemClock.elapsedRealtime() - loadStartMs)).coerceIn(15_000L, 45_000L)
            ClipboardLogger.logAndCopy("WebView", "Sniff Vidlux (${wvTimeoutMs / 1000}s): $wvUrl")
            val seenReqs = mutableListOf<String>()
            val sniffed = sniffVidluxWebView(wvUrl, "$activeHost/", details?.toString(), seenReqs, wvTimeoutMs)

            if (sniffed == null) {
                val sample = seenReqs.take(15).joinToString(" | ")
                ClipboardLogger.logAndCopy("WebView Miss", "Không bắt được luồng (${seenReqs.size} reqs): $sample")
            } else {
                for (subUrl in sniffed.subs) {
                    if (seenSubUrls.add(subUrl)) {
                        subtitleCallback.invoke(SubtitleFile("Vidlux • WebVTT", subUrl))
                        subCount++
                    }
                }

                val cookie = runCatching { CookieManager.getInstance().getCookie(sniffed.url) ?: "" }.getOrDefault("")
                val wvHeaders = sniffed.reqHeaders.toMutableMap().apply {
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }
                val item = VidluxStreamItem(
                    serverId = "webview",
                    serverName = "Player",
                    subLabel = "Auto",
                    fileUrl = sniffed.url,
                    streamType = "",
                    quality = "1080p",
                    headers = wvHeaders,
                    builtInCaps = emptyList()
                )
                if (verifyAndEmitLink(activeHost, wvUrl, item, emittedUrls, callback)) {
                    emittedLinks++
                }
            }
        }

        ClipboardLogger.logAndCopy(
            if (emittedLinks > 0) "Done" else "Failed",
            "Đã nạp $emittedLinks luồng phát & $subCount phụ đề từ Vidlux."
        )
        return emittedLinks > 0
    }
}

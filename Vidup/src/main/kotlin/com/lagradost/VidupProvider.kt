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
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class VidupProvider : MainAPI() {
    override var mainUrl = "https://vidup.to"
    override var name = "Vidup"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // User-Agent Chrome chuẩn (tránh Cloudflare block Headless và vượt check Chrome của player)
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

    // Domain quảng cáo, tracking & anti-devtool cần chặn trong WebView (giữ nguyên stream/CDN & Cloudflare)
    private val blockedWebViewDomains = listOf(
        "umami.vidup.to", "googletagmanager.com", "google-analytics.com",
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
        val dataUrl = "vidup:$type:$id"

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
        Regex("""vidup:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(input)?.let { m ->
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

        val canonical = "vidup:${ref.type}:$tmdbId"

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
                            newEpisode("vidup:tv:$tmdbId:$sNum:$epNum") {
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
    // OpenSubtitles Helper (UTF-8 Uncompressed)
    // =========================================================================

    private suspend fun fetchOpenSubtitles(
        ref: MediaRef,
        imdbId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {
        if (imdbId.isBlank()) return 0
        val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId.removePrefix("tt") }
        val headers = mapOf(
            "User-Agent" to "trailers.to-UA",
            "X-User-Agent" to "trailers.to-UA"
        )

        val fetched = listOf("vie" to "Vietnamese", "eng" to "English").amap { (langCode, langFallback) ->
            val out = mutableListOf<Pair<String, String>>()
            runCatching {
                val url = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                    "https://rest.opensubtitles.org/search/episode-${ref.episode}/imdbid-$cleanId/season-${ref.season}/sublanguageid-$langCode"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$langCode"
                }
                val res = app.get(url, headers = headers, timeout = 8L).text.trim()
                if (!res.startsWith("[")) return@runCatching
                val arr = JSONArray(res)

                // Ưu tiên định dạng srt/vtt trước ass/ssa
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedBy { obj ->
                        when (obj.optString("SubFormat", "").lowercase()) {
                            "srt", "vtt" -> 0
                            else -> 1
                        }
                    }

                for (item in items.take(5)) {
                    val rawLink = item.optString("SubDownloadLink", "").trim()
                    if (!rawLink.startsWith("http")) continue
                    val directLink = rawLink
                        .replace("/file/", "/subencoding-utf8/file/")
                        .removeSuffix(".gz")
                    val langName = item.optString("LanguageName", langFallback).trim()
                    out.add(langName to directLink)
                }
            }
            out
        }.flatten()

        val seen = HashSet<String>()
        var count = 0
        for ((label, subUrl) in fetched) {
            if (seen.add(subUrl)) {
                subtitleCallback.invoke(SubtitleFile(label, subUrl))
                count++
            }
        }
        return count
    }

    // =========================================================================
    // WebView Sniffer (Tự động bật ?autoPlay=true để Vidup player tự phát & nạp luồng)
    // =========================================================================

    private data class SniffResult(
        val url: String,
        val reqHeaders: Map<String, String>,
        val subs: List<String>
    )

    private fun resolveAppContext(): Context? {
        CommonActivity.activity?.let { return it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            method.invoke(null) as? Context
        }.getOrNull()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
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

                    // Bắt HLS (.m3u8), DASH (.mpd), MP4 trực tiếp, master.txt & playlist/manifest
                    val streamRegex = Regex("""\.(m3u8|mpd|mp4)(\?|$|#)|master\.txt|/playlist(\?|$)|/manifest(\?|$)""", RegexOption.IGNORE_CASE)
                    val segmentOrAssetRegex = Regex("""\.(ts|m4s|aac|mp3|js|css|png|jpe?g|webp|gif|svg|ico|woff2?|ttf)(\?|$)|/_next/|/blank\.mp4""", RegexOption.IGNORE_CASE)
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

                    // Vô hiệu hóa popunder inline script (`shown_at` + `window.open`) & kích hoạt phát video nếu cần
                    val antiPopAndPlayJs = """
                        (function(){
                            try {
                                localStorage.setItem('shown_at', String(Date.now()));
                                localStorage.setItem('volume', '0.8');
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
                        if (taps < 22) handler.postDelayed({ tap() }, 1800)
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

                            if (subRegex.containsMatchIn(u) &&
                                !u.contains("thumbnails.vtt", ignoreCase = true) &&
                                !u.contains("/wyzie/", ignoreCase = true)
                            ) {
                                capturedSubs.add(u)
                            }

                            if (streamRegex.containsMatchIn(u) && !segmentOrAssetRegex.containsMatchIn(u)) {
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
                            val allow = u.contains("vidup.to") ||
                                u.contains("ythd.org") ||
                                u.contains("challenges.cloudflare.com")
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

                    handler.postDelayed({ finish(null) }, 45_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to "$mainUrl/"))
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

    private suspend fun probeStreamWithFallbacks(
        rawUrl: String,
        primaryHeaders: Map<String, String>,
        noRefHeaders: Map<String, String>
    ): ProbeOutcome {
        val isMp4 = Regex("""\.mp4(\?|$|#)""", RegexOption.IGNORE_CASE).containsMatchIn(rawUrl)
        val headerCandidates = listOf(primaryHeaders, noRefHeaders)

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
                        rawUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                        rawUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                        else -> null
                    }
                    detected?.let { ProbeOutcome(it, resp.code, baseHdrs) }
                }
            }.getOrNull()

            if (outcome != null) return outcome
        }
        return ProbeOutcome(null, lastCode, primaryHeaders)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "Vidup loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        // 1. Lấy IMDB ID nếu chưa có để nạp phụ đề tiếng Việt & tiếng Anh từ OpenSubtitles
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
        val osSubCount = fetchOpenSubtitles(ref, imdbId, subtitleCallback)

        // 2. Tạo danh sách URL embed với autoPlay=true (mặc định + server dự phòng)
        val baseEmbedUrl = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "$mainUrl/tv/${ref.tmdbId}/$s/$e?autoPlay=true&hideServer=false"
        } else {
            "$mainUrl/movie/${ref.tmdbId}?autoPlay=true&hideServer=false"
        }

        val targetUrls = listOf(
            "Auto" to baseEmbedUrl,
            "Tango" to "$baseEmbedUrl&server=Tango",
            "Delta" to "$baseEmbedUrl&server=Delta"
        )

        var emittedLinks = 0
        var sniffSubCount = 0
        val seenSniffSubs = HashSet<String>()

        for ((serverTag, embedUrl) in targetUrls) {
            try {
                ClipboardLogger.logAndCopy("WebView ($serverTag)", "Sniff: $embedUrl")
                val seenReqs = mutableListOf<String>()
                val sniffed = sniffStream(embedUrl, seenReqs)

                if (sniffed == null) {
                    ClipboardLogger.logAndCopy(
                        "Miss ($serverTag)",
                        "Không bắt được luồng (${seenReqs.size} reqs)"
                    )
                    continue
                }

                for (subUrl in sniffed.subs) {
                    if (seenSniffSubs.add(subUrl)) {
                        subtitleCallback.invoke(SubtitleFile("WebVTT", subUrl))
                        sniffSubCount++
                    }
                }

                val cookie = runCatching { CookieManager.getInstance().getCookie(sniffed.url) ?: "" }.getOrDefault("")
                val withRefHeaders = sniffed.reqHeaders.toMutableMap().apply {
                    put("User-Agent", ua)
                    if (!containsKey("Referer") && !containsKey("referer")) put("Referer", "$mainUrl/")
                    if (!containsKey("Origin") && !containsKey("origin")) put("Origin", mainUrl)
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }
                val noRefHeaders = sniffed.reqHeaders.toMutableMap().apply {
                    remove("Referer")
                    remove("referer")
                    remove("Origin")
                    remove("origin")
                    put("User-Agent", ua)
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }

                // Pre-flight probe xác nhận luồng sống (hỗ trợ cả luồng có Referer và noReferrer)
                val probe = probeStreamWithFallbacks(sniffed.url, withRefHeaders, noRefHeaders)
                val detectedType = probe.linkType
                if (detectedType == null) {
                    ClipboardLogger.logAndCopy(
                        "Dead ($serverTag)",
                        "HTTP ${probe.statusCode} -> ${sniffed.url.take(120)}"
                    )
                    continue
                }

                val finalUrl = when (detectedType) {
                    ExtractorLinkType.M3U8 ->
                        if (sniffed.url.contains(".m3u8", ignoreCase = true)) sniffed.url
                        else "${sniffed.url}#file.m3u8"
                    ExtractorLinkType.DASH ->
                        if (sniffed.url.contains(".mpd", ignoreCase = true)) sniffed.url
                        else "${sniffed.url}#file.mpd"
                    else -> sniffed.url
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
                        name = "Vidup • $serverTag ($formatLabel)",
                        url = finalUrl,
                        type = detectedType
                    ) {
                        this.referer = usedReferer
                        this.quality = Qualities.P1080.value
                        this.headers = probe.workingHeaders
                    }
                )
                emittedLinks++
                ClipboardLogger.logAndCopy(
                    "Live ($serverTag)",
                    "HTTP ${probe.statusCode} ($formatLabel) -> ${finalUrl.take(120)}"
                )
                break
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Err ($serverTag)", "${e.message}")
            }
        }

        ClipboardLogger.logAndCopy(
            if (emittedLinks > 0) "Done" else "Failed",
            "Đã nạp $emittedLinks luồng phát & ${osSubCount + sniffSubCount} phụ đề."
        )
        return emittedLinks > 0
    }
}

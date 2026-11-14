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

class VidSrcFyiProvider : MainAPI() {
    override var mainUrl = "https://vidsrc.fyi"
    override var name = "VidSrc.fyi"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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

    // Domain quảng cáo & anti-devtool cần chặn trong WebView (KHÔNG chặn Turnstile / WASM / stream API)
    private val blockedWebViewDomains = listOf(
        "createlouisville.org", "tagivi.com", "histats.com", "acscdn.com",
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
        "adsterra.com", "adcash.com", "rtmark.net", "disable-devtool"
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
        val dataUrl = "vidsrcfyi:$type:$id"

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
        Regex("""vidsrcfyi:(movie|tv):(\d+)(?::(\d+):(\d+))?""").find(input)?.let { m ->
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

        val canonical = "vidsrcfyi:${ref.type}:$tmdbId"

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
                            newEpisode("vidsrcfyi:tv:$tmdbId:$sNum:$epNum") {
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
    // WebView Sniffer (Vượt Turnstile + ChaCha20 WASM trên vidsrc.sh / stellarconductornexus)
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
        referer: String,
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
                    val pageHost = Regex("""^https?://([^/]+)""").find(pageUrl)?.groupValues?.getOrNull(1) ?: ""

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

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt""", RegexOption.IGNORE_CASE)
                    val subRegex = Regex("""\.(vtt|srt)(\?|$)""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

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

                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play]'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();"

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(fakePopup + clickJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        } catch (_: Exception) { }
                        if (taps < 28) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null

                            if (blockedWebViewDomains.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!junkRegex.containsMatchIn(u) && seenRequests.size < 100) {
                                seenRequests.add(u.take(200))
                            }

                            if (subRegex.containsMatchIn(u) && !u.contains("thumbnails.vtt", ignoreCase = true)) {
                                capturedSubs.add(u)
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
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
                            val allow = (pageHost.isNotBlank() && u.contains(pageHost)) ||
                                u.contains("vidsrc.sh") ||
                                u.contains("vidsrc.fyi") ||
                                u.contains("challenges.cloudflare.com")
                            if (!allow && seenRequests.size < 100) {
                                seenRequests.add("BLOCKED NAV: ${u.take(150)}")
                            }
                            return !allow
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ tap() }, 800)
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
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // =========================================================================
    // Link Extraction + Pre-Flight Probe
    // =========================================================================

    private fun cleanJsUrl(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private fun absUrl(base: String, u: String): String {
        if (u.startsWith("http", ignoreCase = true)) return u
        if (u.startsWith("//")) return "https:$u"
        val host = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.getOrNull(1) ?: return u
        return if (u.startsWith("/")) host + u else "$host/$u"
    }

    private suspend fun probeStreamType(
        rawUrl: String,
        headers: Map<String, String>
    ): Pair<ExtractorLinkType?, Int> {
        return runCatching {
            val resp = app.get(rawUrl, headers = headers, timeout = 8L)
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
                rawUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                rawUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                else -> null
            }
            Pair(detected, resp.code)
        }.getOrElse { Pair(null, 0) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "VidSrc.fyi loadLinks: $data")

        val ref = parseRef(data) ?: run {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        // 1. Lấy IMDB ID nếu chưa có trong cache để tải phụ đề OpenSubtitles
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

        // 2. Phân giải chuỗi embed: vidsrc.fyi -> vidsrc.sh -> /vs_src.php -> player
        val fyiEmbedUrl = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "$mainUrl/embed/tv/${ref.tmdbId}/$s/$e"
        } else {
            "$mainUrl/embed/movie/${ref.tmdbId}"
        }

        val fallbackShEmbed = if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "https://vidsrc.sh/embed/tv/${ref.tmdbId}/$s-$e"
        } else {
            "https://vidsrc.sh/embed/movie/${ref.tmdbId}/"
        }

        val shEmbedUrl = runCatching {
            val fyiHtml = app.get(
                fyiEmbedUrl,
                headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"),
                timeout = 10L
            ).text
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(fyiHtml)?.groupValues?.getOrNull(1)?.let { absUrl(fyiEmbedUrl, cleanJsUrl(it)) }
        }.getOrNull() ?: fallbackShEmbed

        val shOrigin = Regex("""^(https?://[^/]+)""").find(shEmbedUrl)?.groupValues?.getOrNull(1)
            ?: "https://vidsrc.sh"

        val srcApi = runCatching {
            val shHtml = app.get(
                shEmbedUrl,
                headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"),
                timeout = 10L
            ).text
            Regex("""data-api=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(shHtml)?.groupValues?.getOrNull(1)?.let { absUrl(shOrigin, cleanJsUrl(it)) }
        }.getOrNull() ?: if (ref.type == "tv") {
            val s = ref.season ?: 1
            val e = ref.episode ?: 1
            "$shOrigin/vs_src.php?type=tv&id=${ref.tmdbId}&season=$s&episode=$e"
        } else {
            "$shOrigin/vs_src.php?type=movie&id=${ref.tmdbId}"
        }

        var emittedLinks = 0
        var sniffSubCount = 0
        val seenSniffSubs = HashSet<String>()

        for (attempt in 1..2) {
            try {
                val r2 = app.get(
                    srcApi,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Accept" to "application/json",
                        "Referer" to shEmbedUrl,
                        "Origin" to shOrigin
                    ),
                    timeout = 10L
                )
                val innerEmbed = Regex(""""src"\s*:\s*"([^"]+)"""")
                    .find(r2.text)?.groupValues?.getOrNull(1)?.let { cleanJsUrl(it) }

                if (innerEmbed.isNullOrBlank()) {
                    ClipboardLogger.logAndCopy("Attempt $attempt", "vs_src HTTP ${r2.code}: ${r2.text.take(180)}")
                    continue
                }

                val r3 = app.get(
                    innerEmbed,
                    headers = mapOf("User-Agent" to ua, "Referer" to "$shOrigin/"),
                    timeout = 10L
                )
                val playerPath = Regex(""""playerUrl"\s*:\s*"([^"]+)"""")
                    .find(r3.text)?.groupValues?.getOrNull(1)
                val targetUrl = playerPath?.let { absUrl(innerEmbed, cleanJsUrl(it)) } ?: innerEmbed

                ClipboardLogger.logAndCopy("Attempt $attempt", "WebView sniff: ${targetUrl.take(120)}")
                val seenReqs = mutableListOf<String>()
                val sniffed = sniffStream(targetUrl, innerEmbed, seenReqs)

                if (sniffed == null) {
                    ClipboardLogger.logAndCopy(
                        "Attempt $attempt Miss",
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

                val origin = Regex("""^(https?://[^/]+)""").find(innerEmbed)?.groupValues?.getOrNull(1)
                    ?: "https://stellarconductornexus.com"
                val cookie = runCatching { CookieManager.getInstance().getCookie(sniffed.url) ?: "" }.getOrDefault("")
                val playHeaders = sniffed.reqHeaders.toMutableMap().apply {
                    put("User-Agent", ua)
                    put("Referer", "$origin/")
                    put("Origin", origin)
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }

                // Pre-flight probe xác nhận luồng sống (tránh lỗi 2004 trên 4.5.2)
                val (detectedType, probeCode) = probeStreamType(sniffed.url, playHeaders)
                if (detectedType == null) {
                    ClipboardLogger.logAndCopy("Attempt $attempt Dead", "HTTP $probeCode -> ${sniffed.url.take(120)}")
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

                val formatLabel = if (detectedType == ExtractorLinkType.DASH) "DASH" else "HLS"
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "VidSrc.fyi ($formatLabel)",
                        url = finalUrl,
                        type = detectedType
                    ) {
                        this.referer = "$origin/"
                        this.quality = Qualities.P1080.value
                        this.headers = playHeaders
                    }
                )
                emittedLinks++
                ClipboardLogger.logAndCopy("Live", "HTTP $probeCode ($formatLabel) -> ${finalUrl.take(120)}")
                break
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                ClipboardLogger.logAndCopy("Attempt $attempt Err", "${e.message}")
            }
        }

        ClipboardLogger.logAndCopy(
            if (emittedLinks > 0) "Done" else "Failed",
            "Đã nạp $emittedLinks luồng phát & ${osSubCount + sniffSubCount} phụ đề."
        )
        return emittedLinks > 0
    }
}

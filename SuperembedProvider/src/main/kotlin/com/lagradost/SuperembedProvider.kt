package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

// =====================================================================================
// 1. CẤU HÌNH
// =====================================================================================
private const val BASE_URL = "https://multiembed.mov"
private const val TMDB_BASE_URL = "https://tmdb-proxy.silentmist3590.workers.dev/3"
private const val TMDB_API_KEY = "c3d997893bf55c01f39f821b53781547"
private const val IMG_PROXY = "https://wsrv.nl/?url="

private val ID_REGEX = Regex("""(movie|tv):(\d+)(?::(\d+):(\d+))?""")
private class Ref(val type: String, val id: Int, val season: Int?, val episode: Int?)

private fun parseRef(s: String): Ref? {
    val m = ID_REGEX.find(s) ?: return null
    val id = m.groupValues[2].toIntOrNull() ?: return null
    return Ref(m.groupValues[1], id, m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
}

private data class SniffResult(val url: String, val headers: Map<String, String>)

// ⭐ BỔ SUNG CÁC DOMAIN QUẢNG CÁO MỚI TỪ LOG
private val AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
    "adsterra.com", "adcash.com", "rapidadexchange.com", "youradexchange.com",
    "adexchangerapid.com", "googletagmanager.com", "googletagservices.com",
    "crwdcntrl.net", "tynt.com", "mrktmtrcs.net", "dtscout.com", "onaudience.com",
    "histats.com", "luugy.com", "llvpn.com", "rtmark.net", "dvzcfqpdoaxtr.website",
    "adsco.re", "ntwkbc21.com", "ntwkbc42.com", "usrpubtrck.com", "cdn4ads.com",
    // ⭐ CÁC DOMAIN MỚI TỪ LOG CỦA BẠN
    "bkkunbeurvocc.website", "enveulpwblred.space", "my.rtmark.net",
    "bdcdmyzacrjkh.space", "acscdn.com", "aclib.net", "createlouisville.org", "workdeadlinededicate.com", "protrafficinspector.com", "push-sdk.com", "portalfluently.com"
)

// =====================================================================================
// 2. API SERVICES
// =====================================================================================
object ApiServices {
    suspend fun getTmdbDetails(type: String, tmdbId: Int): JSONObject? {
        return try {
            val url = "$TMDB_BASE_URL/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=external_ids"
            JSONObject(app.get(url).text)
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("TMDB Error", e.message ?: "Unknown")
            null
        }
    }

    suspend fun fetchSubtitles(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (imdbId.isEmpty()) return
        try {
            val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId }
            val headers = mapOf("User-Agent" to "trailers.to-UA", "X-User-Agent" to "trailers.to-UA")
            val languages = listOf("vie", "eng")
            for (lang in languages) {
                try {
                    val url = "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$lang"
                    val res = app.get(url, headers = headers).text
                    if (!res.trim().startsWith("[")) continue
                    val arr = org.json.JSONArray(res)
                    for (i in 0 until minOf(arr.length(), 5)) {
                        val item = arr.getJSONObject(i)
                        val subUrl = item.optString("SubDownloadLink")
                        val subLang = item.optString("SubLanguageID", lang)
                        if (subUrl.isNotEmpty()) subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("Subtitles Error", e.message ?: "")
        }
    }
}

// =====================================================================================
// 3. LỚP PROVIDER CHÍNH
// =====================================================================================
class SuperembedProvider : MainAPI() {
    override var mainUrl = BASE_URL
    override var name = "MultiEmbed"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$TMDB_BASE_URL/trending/all/day?api_key=$TMDB_API_KEY" to "Trending Today",
        "$TMDB_BASE_URL/movie/popular?api_key=$TMDB_API_KEY" to "Popular Movies",
        "$TMDB_BASE_URL/tv/popular?api_key=$TMDB_API_KEY" to "Popular TV Shows"
    )

    private val imdbCache = mutableMapOf<Int, String>()
    @Volatile private var wvUa: String = ""

    private fun poster(path: String?, size: String = "w500"): String? {
        if (path.isNullOrEmpty() || path == "null") return null
        val originalUrl = "https://image.tmdb.org/t/p/$size$path"
        return "$IMG_PROXY${URLEncoder.encode(originalUrl, "UTF-8")}"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val response = app.get("${request.data}&page=$page").text
            val json = JSONObject(response)
            val results = json.optJSONArray("results") ?: throw Exception("No results")
            val home = mutableListOf<SearchResponse>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type", if (request.name.contains("TV")) "tv" else "movie")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                home.add(newMovieSearchResponse(title, "superembed:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            newHomePageResponse(request.name, home, hasNext = page < json.optInt("total_pages", 1))
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "$TMDB_BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=$q"
            val json = JSONObject(app.get(url).text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                val title = item.optString("title", item.optString("name"))
                out.add(newMovieSearchResponse(title, "superembed:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            out
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val tmdbId = ref.id
        val mediaType = ref.type

        val json = ApiServices.getTmdbDetails(mediaType, tmdbId) ?: return null

        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val posterUrl = poster(json.optString("poster_path"))
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()

        var imdbId = json.optString("imdb_id")
        if (imdbId.isEmpty()) {
            imdbId = json.optJSONObject("external_ids")?.optString("imdb_id") ?: ""
        }
        if (imdbId.isNotEmpty()) imdbCache[tmdbId] = imdbId

        val canonical = "superembed:$mediaType:$tmdbId"

        if (mediaType == "tv") {
            val episodes = mutableListOf<Episode>()
            val seasonsCount = json.optInt("number_of_seasons", 1)
            for (s in 1..seasonsCount) {
                try {
                    val seasonUrl = "$TMDB_BASE_URL/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY"
                    val seasonJson = JSONObject(app.get(seasonUrl).text)
                    val eps = seasonJson.optJSONArray("episodes") ?: continue
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        val sn = ep.optInt("season_number", s)
                        val en = ep.optInt("episode_number", e + 1)
                        episodes.add(newEpisode("superembed:tv:$tmdbId:$sn:$en") {
                            this.name = ep.optString("name")
                            this.episode = en
                            this.season = sn
                            this.posterUrl = poster(ep.optString("still_path"), "w300")
                        })
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                }
            }
            return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.plot = description; this.year = year
            }
        } else {
            return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                this.posterUrl = posterUrl; this.plot = description; this.year = year
            }
        }
    }

    // =====================================================================================
    // WEBVIEW SNIFFER (ĐÃ FIX: GIẢM CLICK, CHẶN THÊM AD DOMAIN, XỬ LÝ POPUP HỢP LÝ)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        referer: String,
        ua: String,
        seen: MutableList<String>,
        collected: MutableList<String> = mutableListOf()
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff Error", "Không có Context")
                    cont.resume(null)
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    var isChallengePassed = false
                    var popupCount = 0

                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {}
                        cont.resume(res)
                    }

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt""", RegexOption.IGNORE_CASE)
                    val junkRegex = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        // KHÔNG đặt userAgentString: UA giả lệch với client-hints làm Cloudflare Turnstile từ chối
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wvUa = wv.settings.userAgentString ?: ""
                    // Tạo popup thật nhưng chỉ cho mở tối đa 10 cái
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message?
                        ): Boolean {
                            try {
                                popupCount++
                                if (popupCount > 10) {
                                    // Đã đủ popup, không mở thêm để tránh bùng nổ
                                    return false
                                }
                                ClipboardLogger.logAndCopy("Popup #$popupCount", "Đã mở popup")
                                
                                val popup = WebView(ctx)
                                popup.settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    userAgentString = ua
                                }
                                
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                                    }
                                }
                                
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                transport?.webView = popup
                                resultMsg?.sendToTarget()
                                
                                handler.postDelayed({
                                    try { popup.stopLoading(); popup.destroy() } catch (_: Exception) {}
                                }, 2000)
                                
                                return true
                            } catch (e: Exception) {
                                return false
                            }
                        }
                    }

                    // Tắt giả mạo navigator/location: Turnstile phát hiện được và trả "Verification failed"
                    val antiBotJs = ""

                    // Script click đơn giản hơn - chỉ click video và nút play
                    val clickJs = """
                        (function() {
                            function fire(sel) {
                                var els = document.querySelectorAll(sel);
                                for (var i = 0; i < els.length; i++) {
                                    try { els[i].click(); } catch(e) {}
                                }
                            }
                            
                            // Xóa overlay quảng cáo che nút Play
                            var overlays = document.querySelectorAll('div[style*="z-index: 999999"], div[style*="z-index: 2147483647"], iframe[id*="ad"], iframe[src*="bkkunbeur"], iframe[src*="enveulpwb"]');
                            for (var i = 0; i < overlays.length; i++) {
                                try { overlays[i].remove(); } catch(e) {}
                            }
                            
                            // Xóa ad iframes
                            var iframes = document.querySelectorAll('iframe');
                            for (var i = 0; i < iframes.length; i++) {
                                var src = iframes[i].src || '';
                                if (src.indexOf('bkkunbeur') !== -1 || 
                                    src.indexOf('enveulpwb') !== -1 ||
                                    src.indexOf('ad') !== -1 ||
                                    iframes[i].getAttribute('data-ad') !== null) {
                                    try { iframes[i].remove(); } catch(e) {}
                                }
                            }
                            
                            // Trang streamingnow.mov: nút Play (.play-button) bật Turnstile rồi submit form
                            fire('.play-button');
                            fire('#welcome-play-form .play-button, #play');
                            fire('li[data-id], li[data-server], .server-item, .sources-button');
                            fire('#bigPlay');
                            fire('.jw-icon-display, .jw-display-icon-container, .jw-bigplay');
                            fire('.vjs-big-play-button, .play-btn, .play-button');
                            fire('button[aria-label="Play"], [aria-label="Play"]');

                            var v = document.querySelector('video');
                            if (v) {
                                v.muted = true;
                                var p = v.play();
                                if (p && p.catch) p.catch(function(){});
                            }
                        })();
                    """.trimIndent()

                    var taps = 0
                    var tapLoops = 0
                    fun tap() {
                        if (finished) return
                        if (isChallengePassed) {
                            taps++
                            try {
                                wv.evaluateJavascript(antiBotJs + clickJs, null)
                                // Chỉ click 1 vị trí mỗi lần để tránh spam popup
                                sendTouch(wv, w / 2f, h / 2f)
                            } catch (_: Exception) {}
                            
                            // Click tối đa 40 lần, mỗi 2 giây = 80 giây
                            if (taps < 40) {
                                handler.postDelayed({ tap() }, 2000)
                            } else {
                                ClipboardLogger.logAndCopy("Tap", "Đã click 40 lần, chờ thêm 10 giây cuối...")
                                handler.postDelayed({ finish(null) }, 10_000L)
                            }
                        } else {
                            handler.postDelayed({ tap() }, 1000)
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            wv.evaluateJavascript(antiBotJs, null)
                            if (url?.contains("challenges.cloudflare.com") == true || 
                                url?.contains("turnstile") == true) {
                                isChallengePassed = false
                            }
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            val reqHeaders = request?.requestHeaders ?: emptyMap()

                            // ⭐ CHẶN CỨNG CÁC DOMAIN QUẢNG CÁO MỚI
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                seen.add("BLOCKED AD: " + u.take(100))
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }

                            if (u.contains("playvideo.php")) {
                                var first = false
                                synchronized(collected) {
                                    if (!collected.contains(u)) { collected.add(u); first = collected.size == 1 }
                                }
                                // Trang đã tải danh sách server: chờ vài giây cho các request còn lại rồi dừng WebView
                                if (first) {
                                    // Ghi lại DOM của trang player (danh sách server, iframe) để biết cách chọn server
                                    handler.postDelayed({
                                        try {
                                            wv.evaluateJavascript(
                                                "(function(){var c=document.documentElement.cloneNode(true);c.querySelectorAll('script,style,svg,noscript').forEach(function(e){e.remove()});var f=[];document.querySelectorAll('iframe').forEach(function(i){f.push(i.src)});return c.outerHTML.replace(/\\s+/g,' ').slice(0,7000)+'\\n@@IFRAMES@@\\n'+f.join('\\n');})()"
                                            ) { raw ->
                                                val html = try { org.json.JSONArray("[" + (raw ?: "\"\"") + "]").optString(0) } catch (_: Exception) { raw ?: "" }
                                                ClipboardLogger.logAndCopy("PlayerDOM", html)
                                            }
                                        } catch (_: Exception) {}
                                    }, 4500L)
                                    handler.postDelayed({ finish(null) }, 8500L)
                                }
                            }
                            if (!junkRegex.containsMatchIn(u) && seen.size < 200) {
                                seen.add(u.take(220))
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                val hit = SniffResult(u, reqHeaders)
                                ClipboardLogger.logAndCopy("Sniff HIT", "URL: $u")
                                handler.post { finish(hit) }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            
                            // Block ads
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                return true
                            }
                            
                            // Block scheme độc hại
                            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                                return true
                            }
                            
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            wv.evaluateJavascript(antiBotJs, null)
                            seen.add("PAGE: " + (url ?: "").take(160))
                            
                            if (url?.contains("challenges.cloudflare.com") == true || 
                                url?.contains("turnstile") == true) {
                                isChallengePassed = false
                                sendTouch(wv, w / 2f, h / 2f)
                            } else {
                                if (!isChallengePassed) {
                                    ClipboardLogger.logAndCopy("Cloudflare", "Đã vượt qua! Bắt đầu...")
                                    isChallengePassed = true
                                }
                                if (tapLoops == 0) {
                                    tapLoops++
                                    handler.postDelayed({ tap() }, 1500)
                                }
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                ClipboardLogger.logAndCopy(
                                    "WebView Error",
                                    "${request.url.host}: ${error?.errorCode} ${error?.description}"
                                )
                            }
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try {
                        CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) {}

                    // Tổng timeout 120 giây (giảm từ 180s vì click ít hơn)
                    handler.postDelayed({ finish(null) }, 120_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))

                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    private fun sendTouch(wv: WebView, x: Float, y: Float) {
        try {
            val t = SystemClock.uptimeMillis()
            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
            wv.dispatchTouchEvent(d)
            wv.dispatchTouchEvent(u)
            d.recycle(); u.recycle()
        } catch (_: Exception) {}
    }

    // =====================================================================================
    // Cloudflare chặn app.get tới playvideo.php (403) nên mở từng link bằng WebView (đã qua captcha, có cookie)
    // và ghi lại: trang chuyển hướng tới đâu (REDIRECT) hoặc nội dung HTML (HTML).
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveServers(urls: List<String>, referer: String, results: MutableList<Triple<String, String, String>>) {
        suspendCoroutine<Unit> { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Resolve Error", "Không có Context")
                    cont.resume(Unit)
                    return@post
                }
                try {
                    val wv = WebView(ctx)
                    var finished = false
                    var idx = -1
                    var got = false
                    var cur = ""
                    var armed = false
                    val ext = mutableListOf<String>()
                    fun end() {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {}
                        cont.resume(Unit)
                    }
                    fun next() {
                        if (finished) return
                        idx++
                        got = false
                        armed = false
                        ext.clear()
                        if (idx >= urls.size) { end(); return }
                        cur = urls[idx]
                        val my = idx
                        wv.loadUrl(cur, mapOf("Referer" to referer))
                        handler.postDelayed({
                            if (!finished && idx == my && !got) {
                                got = true
                                results.add(Triple(urls[my], "TIMEOUT", ""))
                                next()
                            }
                        }, 9000L)
                    }
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (!u.contains("streamingnow.mov") && !got && cur.isNotEmpty()) {
                                got = true
                                results.add(Triple(cur, "REDIRECT", u))
                                handler.post { next() }
                                return true
                            }
                            return false
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (!got && !u.contains("streamingnow.mov") && !u.contains("cloudflare") && !u.contains("gstatic") &&
                                !u.contains("googleapis") && AD_DOMAINS.none { u.contains(it, true) } && ext.size < 40) ext.add(u)
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (got || armed || url == null || !url.contains("playvideo.php")) return
                            armed = true
                            val my = idx
                            // Trang này lại có nút Play: bấm, đợi rồi thu HTML + mọi iframe/video/link
                            handler.postDelayed({
                                if (!finished && idx == my && !got) view?.evaluateJavascript(
                                    "(function(){var b=document.querySelector('.play-button');if(b)b.click();var v=document.querySelector('video');if(v){v.muted=true;try{v.play();}catch(e){}}})()", null
                                )
                            }, 1200L)
                            handler.postDelayed({
                                if (finished || idx != my || got) return@postDelayed
                                view?.evaluateJavascript(
                                    "(function(){var r=[];document.querySelectorAll('iframe,video,source,a').forEach(function(e){var s=e.src||e.href||'';if(s)r.push(s)});return document.documentElement.outerHTML+'\\n@@LINKS@@\\n'+r.join('\\n');})()"
                                ) { raw ->
                                    if (got || idx != my) return@evaluateJavascript
                                    got = true
                                    val html = try { org.json.JSONArray("[" + (raw ?: "\"\"") + "]").optString(0) } catch (_: Exception) { raw ?: "" }
                                    results.add(Triple(urls[my], "HTML", html + "\n@@EXT@@\n" + ext.joinToString("\n")))
                                    next()
                                }
                            }, 6500L)
                        }
                    }
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 1080, 1920)
                    wv.translationX = -4000f
                    try {
                        CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(1080, 1920))
                    } catch (_: Exception) {}
                    handler.postDelayed({ end() }, 110_000L)
                    next()
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Resolve Exception", e.stackTraceToString().take(600))
                    cont.resume(Unit)
                }
            }
        }
    }

    // =====================================================================================
    // Lấy link từ danh sách server (playvideo.php?video_id=..&server_id=..&token=..)
    // =====================================================================================
    private suspend fun extractFromServers(
        urls: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerReferer = "https://streamingnow.mov/"
        var count = 0
        val counting: (ExtractorLink) -> Unit = { count++; callback(it) }
        val skipHosts = listOf("streamingnow.mov", "multiembed.mov", "cloudflare", "histats", "google", "cloudfront", "facebook", "twitter", "w3.org")
        val done = HashSet<String>()
        val list = urls.distinct().take(8)
        ClipboardLogger.logAndCopy("Servers", "Tìm thấy ${urls.size} server (playvideo.php), mở ${list.size} bằng WebView")
        val results = mutableListOf<Triple<String, String, String>>()
        resolveServers(list, playerReferer, results)
        for ((u, kind, payload) in results) {
            val sid = Regex("server_id=(\\d+)").find(u)?.groupValues?.get(1) ?: "?"
            val stripped = payload.replace(Regex("(?s)<style.*?</style>"), "").replace(Regex("(?s)<svg.*?</svg>"), "<svg/>").replace(Regex("\\s+"), " ")
            ClipboardLogger.logAndCopy("Server $sid", "$kind ${if (results.firstOrNull()?.first == u) stripped.take(3500) else stripped.takeLast(700)}")
            val cands = mutableListOf<String>()
            if (kind == "REDIRECT") cands.add(payload)
            if (kind == "HTML") {
                Regex("""(?:src|href|file|url|location(?:\.href)?)\s*[=:]\s*["'](https?:[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .findAll(payload).forEach { cands.add(it.groupValues[1].replace("\\/", "/")) }
                Regex("""https?:\\?/\\?/[^\s"'<>\\]+""").findAll(payload).forEach { cands.add(it.value.replace("\\/", "/")) }
            }
            for (c in cands.distinct()) {
                if (skipHosts.any { c.contains(it, true) } || AD_DOMAINS.any { c.contains(it, true) }) continue
                if (!done.add(c)) continue
                try {
                    if (c.contains(".m3u8", true)) {
                        counting(newExtractorLink(this.name, "MultiEmbed $sid", c, ExtractorLinkType.M3U8) {
                            this.referer = playerReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("Referer" to playerReferer)
                        })
                    } else {
                        val before = count
                        loadExtractor(c, playerReferer, subtitleCallback, counting)
                        ClipboardLogger.logAndCopy("Extractor $sid", "$c -> ${count - before} link")
                    }
                } catch (e: Exception) {
                    if (e is java.util.concurrent.CancellationException) throw e
                    ClipboardLogger.logAndCopy("Extractor Error $sid", "$c: ${e.message}")
                }
            }
        }
        return count > 0
    }

    // =====================================================================================
    // LOAD LINKS
    // =====================================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LoadLinks", "Data: $data")

        val ref = parseRef(data) ?: return false
        val type = ref.type
        val tmdbId = ref.id
        val season = ref.season
        val episode = ref.episode

        if (type == "tv" && (season == null || episode == null)) {
            ClipboardLogger.logAndCopy("Error", "Thiếu season/episode")
            return false
        }

        val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val fakeReferer = "https://multiembed.mov/"

        val imdbId = imdbCache[tmdbId] ?: ""
        if (imdbId.isNotEmpty()) {
            ApiServices.fetchSubtitles(imdbId, subtitleCallback)
        }

        try {
            val embedUrl = if (type == "movie") {
                "$BASE_URL/?video_id=$tmdbId&tmdb=1"
            } else {
                "$BASE_URL/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
            }

            ClipboardLogger.logAndCopy("WebView", "Tải: $embedUrl")
            val seen = mutableListOf<String>()
            val playUrls = mutableListOf<String>()
            val result = sniffStream(embedUrl, fakeReferer, ua, seen, playUrls)

            if (result == null && playUrls.isNotEmpty()) {
                ClipboardLogger.logAndCopy("Servers", "Tìm thấy ${playUrls.size} server (playvideo.php)")
                val hosts = Regex("mixdrop|streamtape|doodstream|dood\\.|voe\\.|filemoon|vidoza|streamwish|upstream|mp4upload|embedsito|lulustream", RegexOption.IGNORE_CASE)
                val embeds = seen.filter { it.startsWith("http") && hosts.containsMatchIn(it) }.distinct()
                ClipboardLogger.logAndCopy("Embeds", embeds.joinToString("\n").ifEmpty { "(không có)" })
                var n = 0
                for (e in embeds.take(6)) {
                    try {
                        loadExtractor(e, "https://streamingnow.mov/", subtitleCallback) { n++; callback(it) }
                    } catch (ex: Exception) {
                        if (ex is java.util.concurrent.CancellationException) throw ex
                    }
                }
                ClipboardLogger.logAndCopy("Extractor", "Số link: $n")
                return n > 0
            }

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link stream.\nSeen cuối:\n${seen.filter { !it.startsWith("BLOCKED") }.takeLast(30).joinToString("\n")}")
                return false
            }

            val playerReferer = "https://streamingnow.mov/"
            val cookie = CookieManager.getInstance().getCookie(result.url) ?: ""
            
            val finalHeaders = result.headers.toMutableMap().apply {
                put("User-Agent", ua)
                put("Referer", playerReferer)
                put("Origin", "https://streamingnow.mov")
                if (cookie.isNotEmpty()) put("Cookie", cookie)
            }

            val qualities = M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = result.url,
                referer = playerReferer,
                headers = finalHeaders
            )

            if (qualities.isNotEmpty()) {
                qualities.forEach { callback.invoke(it) }
            } else {
                callback.invoke(newExtractorLink(this.name, "MultiEmbed", result.url, ExtractorLinkType.M3U8) {
                    this.referer = playerReferer
                    this.quality = Qualities.P1080.value
                    this.headers = finalHeaders
                })
            }
            return true

        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString().take(1500)}")
            return false
        }
    }
}

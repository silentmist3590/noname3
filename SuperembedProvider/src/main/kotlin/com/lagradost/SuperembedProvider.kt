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
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
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

private val AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "popads.net", "onclickads.net", "propellerads.com", "popcash.net",
    "adsterra.com", "adcash.com", "rapidadexchange.com", "youradexchange.com",
    "adexchangerapid.com", "googletagmanager.com", "googletagservices.com",
    "crwdcntrl.net", "tynt.com", "mrktmtrcs.net", "dtscout.com", "onaudience.com",
    "histats.com", "luugy.com", "llvpn.com", "rtmark.net", "dvzcfqpdoaxtr.website",
    "adsco.re", "ntwkbc21.com", "ntwkbc42.com", "usrpubtrck.com", "cdn4ads.com",
    "bkkunbeurvocc.website", "enveulpwblred.space", "my.rtmark.net",
    "bdcdmyzacrjkh.space", "acscdn.com", "aclib.net", "createlouisville.org",
    "workdeadlinededicate.com", "protrafficinspector.com", "push-sdk.com", "portalfluently.com",
    "5gvci.com", "sw.js", "dd.js", "jwpltx.com", "ping.gif"
)

// Chỉ chặn vipstream-A vì nó bị lỗi 2004
private val BROKEN_SERVERS = listOf("vipstream-A")

// =====================================================================================
// 2. API SERVICES
// =====================================================================================
object ApiServices {
    suspend fun getTmdbDetails(type: String, tmdbId: Int): JSONObject? {
        return try {
            val url = "$TMDB_BASE_URL/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=external_ids"
            JSONObject(app.get(url).text)
        } catch (e: Exception) { null }
    }

    suspend fun fetchSubtitles(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (imdbId.isEmpty()) return
        try {
            val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId }
            val headers = mapOf("User-Agent" to "trailers.to-UA", "X-User-Agent" to "trailers.to-UA")
            for (lang in listOf("vie", "eng")) {
                try {
                    val url = "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$lang"
                    val res = app.get(url, headers = headers).text
                    if (!res.trim().startsWith("[")) continue
                    val arr = org.json.JSONArray(res)
                    for (i in 0 until minOf(arr.length(), 5)) {
                        val item = arr.getJSONObject(i)
                        val subUrl = item.optString("SubDownloadLink")
                        if (subUrl.isNotEmpty()) {
                            subtitleCallback.invoke(SubtitleFile(item.optString("SubLanguageID", lang), subUrl))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
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

    private fun poster(path: String?, size: String = "w500"): String? {
        if (path.isNullOrEmpty() || path == "null") return null
        return "$IMG_PROXY${URLEncoder.encode("https://image.tmdb.org/t/p/$size$path", "UTF-8")}"
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
                home.add(newMovieSearchResponse(item.optString("title", item.optString("name")), "superembed:$mediaType:$id",
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
            val json = JSONObject(app.get("$TMDB_BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=$q").text)
            val results = json.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val mediaType = item.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val id = item.optInt("id", 0)
                if (id == 0) continue
                out.add(newMovieSearchResponse(item.optString("title", item.optString("name")), "superembed:$mediaType:$id",
                    if (mediaType == "tv") TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = poster(item.optString("poster_path"))
                })
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseRef(url) ?: return null
        val json = ApiServices.getTmdbDetails(ref.type, ref.id) ?: return null
        val title = json.optString("title", json.optString("name"))
        val description = json.optString("overview")
        val posterUrl = poster(json.optString("poster_path"))
        val year = json.optString("release_date", json.optString("first_air_date")).take(4).toIntOrNull()

        var imdbId = json.optString("imdb_id")
        if (imdbId.isEmpty()) imdbId = json.optJSONObject("external_ids")?.optString("imdb_id") ?: ""
        if (imdbId.isNotEmpty()) imdbCache[ref.id] = imdbId

        val canonical = "superembed:${ref.type}:${ref.id}"
        if (ref.type == "tv") {
            val episodes = mutableListOf<Episode>()
            for (s in 1..json.optInt("number_of_seasons", 1)) {
                try {
                    val seasonJson = JSONObject(app.get("$TMDB_BASE_URL/tv/${ref.id}/season/$s?api_key=$TMDB_API_KEY").text)
                    val eps = seasonJson.optJSONArray("episodes") ?: continue
                    for (e in 0 until eps.length()) {
                        val ep = eps.getJSONObject(e)
                        val sn = ep.optInt("season_number", s)
                        val en = ep.optInt("episode_number", e + 1)
                        episodes.add(newEpisode("superembed:tv:${ref.id}:$sn:$en") {
                            this.name = ep.optString("name")
                            this.episode = en
                            this.season = sn
                            this.posterUrl = poster(ep.optString("still_path"), "w300")
                        })
                    }
                } catch (_: Exception) {}
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
    // WEBVIEW SNIFFER (BẮT TRỌN BỘ HEADER CHO VOE)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String, referer: String, seen: MutableList<String>
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) { cont.resume(null); return@post }

                try {
                    val container = FrameLayout(ctx)
                    val wv = WebView(ctx)
                    val cursor = TextView(ctx)

                    var finished = false
                    var isCaptchaActive = false
                    var retryCount = 0
                    var autoClickEnabled = true
                    val originalOrientation = ctx.resources.configuration.orientation
                    var firstStream: SniffResult? = null

                    var cx = 500f
                    var cy = 500f

                    fun finish() {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            container.visibility = View.GONE
                            wv.stopLoading()
                            wv.loadUrl("about:blank")
                            wv.clearHistory()
                            wv.clearCache(true)
                            wv.destroy()
                            
                            (container.parent as? ViewGroup)?.removeView(container)
                            val root = CommonActivity.activity?.window?.decorView as? ViewGroup
                            root?.removeView(container)
                            
                            if (ctx.resources.configuration.orientation != originalOrientation) {
                                CommonActivity.activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        } catch (_: Exception) {}
                        cont.resume(firstStream)
                    }

                    fun processFoundUrl(url: String, requestHeaders: Map<String, String>) {
                        if (firstStream != null || finished) return
                        
                        // ⭐ CHÌA KHÓA VƯỢT LỖI 3003 CỦA VOE:
                        // Lấy chính xác 100% Cookie và User-Agent từ trình duyệt
                        val cookie = CookieManager.getInstance().getCookie(url) ?: CookieManager.getInstance().getCookie("https://voe.sx/") ?: ""
                        
                        val finalHeaders = requestHeaders.toMutableMap().apply {
                            if (cookie.isNotEmpty()) put("Cookie", cookie)
                            if (!containsKey("Referer")) put("Referer", "https://voe.sx/")
                            if (!containsKey("Origin")) put("Origin", "https://voe.sx")
                        }
                        
                        firstStream = SniffResult(url, finalHeaders)
                        ClipboardLogger.logAndCopy("Sniff HIT", "Bắt link VOE (kèm full Header): $url")
                        
                        handler.postDelayed({
                            if (!finished) finish()
                        }, 200L)
                    }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    wv.setBackgroundColor(Color.BLACK)

                    cursor.text = "➕"
                    cursor.textSize = 40f
                    cursor.setTextColor(Color.RED)
                    cursor.x = cx
                    cursor.y = cy

                    wv.isFocusable = true
                    wv.requestFocus()
                    wv.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            autoClickEnabled = false
                            
                            when (keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP -> { cy -= 40f; cursor.y = cy; true }
                                KeyEvent.KEYCODE_DPAD_DOWN -> { cy += 40f; cursor.y = cy; true }
                                KeyEvent.KEYCODE_DPAD_LEFT -> { cx -= 40f; cursor.x = cx; true }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> { cx += 40f; cursor.x = cx; true }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                    sendRealMotionEvent(wv, cx + 20f, cy + 20f, handler)
                                    true
                                }
                                KeyEvent.KEYCODE_BACK -> {
                                    finish()
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                            val popup = WebView(ctx)
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                                }
                            }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = popup
                            resultMsg?.sendToTarget()
                            handler.postDelayed({ try { popup.destroy() } catch (_: Exception) {} }, 1000)
                            return true
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return super.onConsoleMessage(consoleMessage)
                            if (msg == "CAPTCHA_ON") {
                                isCaptchaActive = true
                            } else if (msg == "CAPTCHA_OFF") {
                                isCaptchaActive = false
                            } else if (msg.startsWith("HOOK_VIDEO_URL:")) {
                                val url = msg.removePrefix("HOOK_VIDEO_URL:").trim()
                                if (url.startsWith("http") && !AD_DOMAINS.any { url.contains(it, true) }) {
                                    val cookie = CookieManager.getInstance().getCookie("https://voe.sx/") ?: ""
                                    processFoundUrl(url, mapOf("Cookie" to cookie))
                                }
                            }
                            return true
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            view?.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null)
                            
                            val js = """
                                (function() {
                                    var checkVideo = setInterval(function() {
                                        var v = document.querySelector('video');
                                        if (v && v.duration > 0) {
                                            if (v.src && v.src.startsWith('http')) {
                                                console.log("HOOK_VIDEO_URL:" + v.src);
                                                clearInterval(checkVideo);
                                            } else if (v.currentSrc && v.currentSrc.startsWith('http')) {
                                                console.log("HOOK_VIDEO_URL:" + v.currentSrc);
                                                clearInterval(checkVideo);
                                            }
                                        }
                                    }, 1000);
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }

                            if (seen.size < 100) seen.add(u.take(150))

                            val uri = Uri.parse(u)
                            val path = uri.path ?: ""
                            val isRealStream = path.endsWith(".m3u8", true) || 
                                               path.endsWith(".mp4", true) || 
                                               path.endsWith(".mpd", true) || 
                                               path.endsWith(".ts", true)

                            if (isRealStream && !u.contains("/embed/iframe_player/")) {
                                val headers = request?.requestHeaders ?: emptyMap()
                                
                                handler.post {
                                    wv.evaluateJavascript(
                                        "(function(){" +
                                        "var items = document.querySelectorAll('ul.sources-list li');" +
                                        "for (var i = 0; i < items.length; i++) {" +
                                        "  if (items[i].className.includes('active') || items[i].className.includes('current')) {" +
                                        "    return items[i].innerText;" +
                                        "  }" +
                                        "}" +
                                        "return '';" +
                                        "})()"
                                    ) { activeServer ->
                                        if (activeServer != null && BROKEN_SERVERS.any { activeServer.contains(it, true) }) {
                                            ClipboardLogger.logAndCopy("Skip Link", "Bỏ qua link lỗi: $activeServer")
                                        } else {
                                            processFoundUrl(u, headers)
                                        }
                                    }
                                }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) return true
                            if (!u.startsWith("http")) return true
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {}

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                val desc = error?.description?.toString() ?: ""
                                if (retryCount < 3 && (desc.contains("QUIC", true) || error?.errorCode == -1 || error?.errorCode == -6)) {
                                    retryCount++
                                    handler.postDelayed({ wv.loadUrl(pageUrl) }, 1500)
                                }
                            }
                        }
                    }

                    container.addView(wv)
                    container.addView(cursor)

                    val params = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    try {
                        CommonActivity.activity?.addContentView(container, params)
                    } catch (_: Exception) {}

                    val clickRunnable = object : Runnable {
                        override fun run() {
                            if (finished || firstStream != null) return
                            
                            if (autoClickEnabled && !isCaptchaActive) {
                                try {
                                    wv.evaluateJavascript(
                                        "(function(){" +
                                        "function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                                        "var items = document.querySelectorAll('ul.sources-list li');" +
                                        "var clicked = false;" +
                                        "for (var i = 0; i < items.length; i++) {" +
                                        "  var txt = items[i].innerText;" +
                                        "  // Ưu tiên click vào VOE, GDrive, Streamwish" +
                                        "  if (txt.includes('voe') || txt.includes('GDrive') || txt.includes('streamwish')) {" +
                                        "    fire(items[i]); clicked = true; break;" +
                                        "  }" +
                                        "}" +
                                        "if (clicked) {" +
                                        "  setTimeout(function() {" +
                                        "    fire(document.getElementById('bigPlay'));" +
                                        "    fire(document.querySelector('.jw-icon-display, .jw-display-icon-container, .jw-bigplay'));" +
                                        "    var v = document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
                                        "  }, 1000);" +
                                        "} else {" +
                                        "  fire(document.getElementById('bigPlay'));" +
                                        "  var v = document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}" +
                                        "}" +
                                        "})()", null
                                    )
                                } catch (_: Exception) {}
                            }
                            handler.postDelayed(this, 2000)
                        }
                    }
                    handler.postDelayed(clickRunnable, 2000)

                    handler.postDelayed({ if (!finished) finish() }, 180_000L)
                    
                    wv.loadUrl(pageUrl)
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }

    private fun sendRealMotionEvent(wv: WebView, x: Float, y: Float, handler: Handler) {
        try {
            val downTime = SystemClock.uptimeMillis()
            
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            wv.dispatchTouchEvent(down)
            down.recycle()

            handler.postDelayed({
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                wv.dispatchTouchEvent(up)
                up.recycle()
            }, 60)
        } catch (_: Exception) {}
    }

    // =====================================================================================
    // LOAD LINKS
    // =====================================================================================
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
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

        if (type == "tv" && (season == null || episode == null)) return false

        val fakeReferer = "https://multiembed.mov/"

        val imdbId = imdbCache[tmdbId] ?: ""
        if (imdbId.isNotEmpty()) ApiServices.fetchSubtitles(imdbId, subtitleCallback)

        try {
            val embedUrl = if (type == "movie") {
                "$BASE_URL/?video_id=$tmdbId&tmdb=1"
            } else {
                "$BASE_URL/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
            }

            val seen = mutableListOf<String>()
            val result = sniffStream(embedUrl, fakeReferer, seen)

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "Không bắt được link chính.")
                return false
            }

            val playerReferer = "https://voe.sx/"
            val finalHeaders = result.headers.toMutableMap()

            if (result.url.contains(".m3u8")) {
                val qualities = M3u8Helper.generateM3u8(
                    source = this.name, 
                    streamUrl = result.url, 
                    referer = playerReferer, 
                    headers = finalHeaders
                )
                if (qualities.isNotEmpty()) {
                    qualities.forEach { callback.invoke(it) }
                } else {
                    callback(newExtractorLink(this.name, "MultiEmbed (VOE)", result.url, ExtractorLinkType.M3U8) {
                        this.referer = playerReferer
                        this.quality = Qualities.P1080.value
                        this.headers = finalHeaders
                    })
                }
            } else {
                callback(newExtractorLink(this.name, "MultiEmbed (VOE)", result.url, ExtractorLinkType.VIDEO) {
                    this.referer = playerReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = finalHeaders
                })
            }

            return true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}")
            return false
        }
    }
}

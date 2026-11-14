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
private data class ServerInfo(val id: String, val name: String)

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
    "5gvci.com", "sw.js", "dd.js"
)

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
    // WEBVIEW SNIFFER (BẮT LINK TỪ NETWORK + JS VIDEO.SRC + HEADER ĐẦY ĐỦ)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String, referer: String, seen: MutableList<String>
    ): Pair<SniffResult?, List<ServerInfo>> {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) { cont.resume(Pair(null, emptyList())); return@post }

                try {
                    val container = FrameLayout(ctx)
                    val wv = WebView(ctx)
                    val cursor = TextView(ctx)

                    var finished = false
                    var isCaptchaActive = false
                    var retryCount = 0
                    val originalOrientation = ctx.resources.configuration.orientation
                    var firstStream: SniffResult? = null
                    var serverList: List<ServerInfo> = emptyList()
                    var hasExtractedServers = false

                    var cx = 500f
                    var cy = 500f

                    val realUa = wv.settings.userAgentString

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
                        cont.resume(Pair(firstStream, serverList))
                    }

                    fun processFoundUrl(url: String, headers: Map<String, String>) {
                        if (firstStream != null || finished) return
                        
                        // Lấy Cookie từ WebView để đính kèm
                        val cookie = CookieManager.getInstance().getCookie(url) ?: ""
                        val finalHeaders = headers.toMutableMap().apply {
                            if (cookie.isNotEmpty()) put("Cookie", cookie)
                        }
                        
                        firstStream = SniffResult(url, finalHeaders)
                        ClipboardLogger.logAndCopy("Sniff HIT", "Đã lấy được link: $url")
                        
                        handler.post {
                            if (finished) return@post
                            
                            if (!hasExtractedServers) {
                                hasExtractedServers = true
                                try {
                                    wv.evaluateJavascript(
                                        "(function(){" +
                                        "var arr=[];" +
                                        "document.querySelectorAll('ul.sources-list li[data-server]').forEach(function(l){" +
                                        "  var n=l.querySelector('.server-name');" +
                                        "  arr.push({id:l.getAttribute('data-server'), name: n ? n.textContent.trim() : l.textContent.trim()});" +
                                        "});" +
                                        "return JSON.stringify(arr);" +
                                        "})()"
                                    ) { raw ->
                                        serverList = parseServerList(raw)
                                        ClipboardLogger.logAndCopy("ServerList", "Lấy được ${serverList.size} server phụ.")
                                    }
                                } catch (_: Exception) {}
                            }
                            
                            // Đóng WebView sau 300ms để kịp lấy server list
                            handler.postDelayed({
                                if (!finished) finish()
                            }, 300L)
                        }
                    }

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt|\.mp4""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        // Không set UA giả để tránh Cloudflare phát hiện
                    }
                    wv.setBackgroundColor(Color.BLACK)

                    // Chuột ảo TV
                    cursor.text = "➕"
                    cursor.textSize = 40f
                    cursor.setTextColor(Color.RED)
                    cursor.x = cx
                    cursor.y = cy

                    wv.isFocusable = true
                    wv.requestFocus()
                    wv.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
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
                                ClipboardLogger.logAndCopy("Captcha", "Đang chờ bạn giải Captcha...")
                            } else if (msg == "CAPTCHA_OFF") {
                                isCaptchaActive = false
                                ClipboardLogger.logAndCopy("Captcha", "Đã giải xong.")
                            } else if (msg.startsWith("HOOK_VIDEO_URL:")) {
                                // Bắt link từ video.src (dành cho blob/iframe)
                                val url = msg.removePrefix("HOOK_VIDEO_URL:").trim()
                                if (url.startsWith("http") && !AD_DOMAINS.any { url.contains(it, true) }) {
                                    ClipboardLogger.logAndCopy("JS Video Src", "Phát hiện link từ thẻ video: $url")
                                    val headers = mapOf(
                                        "Referer" to "https://streamingnow.mov/",
                                        "Origin" to "https://streamingnow.mov",
                                        "User-Agent" to realUa
                                    )
                                    processFoundUrl(url, headers)
                                }
                            }
                            return true
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            val js = """
                                (function() {
                                    if (window.fetch) {
                                        var origFetch = window.fetch;
                                        window.fetch = function() {
                                            var args = arguments;
                                            return origFetch.apply(this, args).then(function(resp) {
                                                var url = typeof args[0] === 'string' ? args[0] : args[0].url;
                                                if (url && (url.includes('.m3u8') || url.includes('.mp4'))) {
                                                    console.log("HOOK_VIDEO_URL:" + new URL(url, window.location.href).href);
                                                } else {
                                                    resp.clone().text().then(function(txt) {
                                                        if (txt && txt.startsWith('#EXTM3U')) {
                                                            console.log("HOOK_VIDEO_URL:" + new URL(url, window.location.href).href);
                                                        }
                                                    }).catch(function(){});
                                                }
                                                return resp;
                                            });
                                        };
                                    }

                                    var origOpen = XMLHttpRequest.prototype.open;
                                    XMLHttpRequest.prototype.open = function(method, url) {
                                        this.addEventListener('load', function() {
                                            if (url && (url.includes('.m3u8') || url.includes('.mp4'))) {
                                                console.log("HOOK_VIDEO_URL:" + new URL(url, window.location.href).href);
                                            } else if (this.responseText && this.responseText.startsWith('#EXTM3U')) {
                                                console.log("HOOK_VIDEO_URL:" + new URL(url, window.location.href).href);
                                            }
                                        });
                                        origOpen.apply(this, arguments);
                                    };

                                    // Theo dõi thẻ video để bắt link (kể cả blob)
                                    var checkVideo = setInterval(function() {
                                        var v = document.querySelector('video');
                                        if (v && v.duration > 0) {
                                            if (v.src && v.src.startsWith('http')) {
                                                console.log("HOOK_VIDEO_URL:" + v.src);
                                                clearInterval(checkVideo);
                                            } else if (v.src && v.src.startsWith('blob:')) {
                                                // Nếu là blob, thử lấy từ currentSrc
                                                if (v.currentSrc && v.currentSrc.startsWith('http')) {
                                                    console.log("HOOK_VIDEO_URL:" + v.currentSrc);
                                                    clearInterval(checkVideo);
                                                }
                                            }
                                        }
                                    }, 1000);

                                    setInterval(function() {
                                        var isCaptcha = document.body.innerText.includes('Select all images') || 
                                                        document.body.innerText.includes('Verification') ||
                                                        document.querySelector('.cf-turnstile') !== null ||
                                                        document.querySelector('.captcha-holder') !== null;
                                        if (isCaptcha) {
                                            if (!window.__isCap) { window.__isCap = true; console.log("CAPTCHA_ON"); }
                                        } else {
                                            if (window.__isCap) { window.__isCap = false; console.log("CAPTCHA_OFF"); }
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

                            if (!u.contains(".png") && !u.contains(".jpg") && !u.contains(".css") && !u.contains(".js") && seen.size < 100) {
                                seen.add(u.take(150))
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                val headers = request?.requestHeaders?.toMutableMap() ?: mutableMapOf()
                                headers["Referer"] = "https://streamingnow.mov/"
                                headers["Origin"] = "https://streamingnow.mov"
                                processFoundUrl(u, headers)
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
                            if (!isCaptchaActive) {
                                try {
                                    wv.evaluateJavascript(
                                        "(function(){" +
                                        "var els=document.querySelectorAll('button,div,span,a,video');" +
                                        "for(var i=0;i<els.length;i++){" +
                                        "  var el=els[i];var txt=el.innerText||'';var cls=typeof el.className==='string'?el.className:'';" +
                                        "  if(txt.indexOf('Play')!==-1||cls.indexOf('play')!==-1||cls.indexOf('jw-icon')!==-1){" +
                                        "    try{el.click();}catch(e){}" +
                                        "  }" +
                                        "}" +
                                        "})()", null
                                    )
                                    sendRealMotionEvent(wv, 540f, 960f, handler)
                                } catch (_: Exception) {}
                            }
                            handler.postDelayed(this, 2500)
                        }
                    }
                    handler.postDelayed(clickRunnable, 2000)

                    handler.postDelayed({ if (!finished) finish() }, 180_000L)
                    
                    wv.loadUrl(pageUrl)
                } catch (e: Exception) {
                    cont.resume(Pair(null, emptyList()))
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

    private fun parseServerList(raw: String?): List<ServerInfo> {
        if (raw == null) return emptyList()
        return try {
            var cleaned = raw.trim()
            if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) cleaned = cleaned.substring(1, cleaned.length - 1)
            cleaned = cleaned.replace("\\\"", "\"")
            if (cleaned == "[]" || cleaned.isEmpty()) return emptyList()
            val arr = org.json.JSONArray(cleaned)
            val list = mutableListOf<ServerInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ServerInfo(obj.optString("id"), obj.optString("name")))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    // =====================================================================================
    // LẤY LINK TỪ CÁC SERVER PHỤ (VỚI HEADER ĐẦY ĐỦ)
    // =====================================================================================
    private suspend fun extractSpecificServers(
        servers: List<ServerInfo>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val playerReferer = "https://streamingnow.mov/"
        val cookie = CookieManager.getInstance().getCookie(playerReferer) ?: ""
        
        val targetNames = listOf("streamwish", "mixdrop")
        val filteredServers = servers.filter { s -> targetNames.any { s.name.contains(it, true) } }
        
        if (filteredServers.isEmpty()) return
        
        ClipboardLogger.logAndCopy("ExtraServers", "Đang thử lấy thêm từ: ${filteredServers.joinToString { it.name }}")

        for (server in filteredServers) {
            val serverUrl = "https://streamingnow.mov/playvideo.php?server_id=${server.id}"
            try {
                val res = app.get(serverUrl, headers = mapOf(
                    "Referer" to playerReferer,
                    "Origin" to playerReferer,
                    "Cookie" to cookie
                )).text
                
                val m3u8Regex = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""", RegexOption.IGNORE_CASE)
                val m3u8 = m3u8Regex.find(res)?.value?.replace("\\/", "/")
                
                if (m3u8 != null) {
                    callback(newExtractorLink(this.name, "MultiEmbed ${server.name}", m3u8, ExtractorLinkType.M3U8) {
                        this.referer = playerReferer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to playerReferer, 
                            "Origin" to playerReferer,
                            "Cookie" to cookie
                        )
                    })
                    ClipboardLogger.logAndCopy("ExtraServer Success", "${server.name}: $m3u8")
                } else {
                    val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    val iframeUrl = iframeRegex.find(res)?.groupValues?.get(1)
                    if (iframeUrl != null) {
                        loadExtractor(iframeUrl, playerReferer, subtitleCallback, callback)
                        ClipboardLogger.logAndCopy("ExtraServer Extractor", "${server.name}: $iframeUrl")
                    }
                }
            } catch (e: Exception) {
                ClipboardLogger.logAndCopy("ExtraServer Error", "${server.name}: ${e.message}")
            }
        }
    }

    // =====================================================================================
    // LOAD LINKS (XỬ LÝ LỖI 2004 BẰNG M3U8HELPER + HEADER)
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
            val (result, servers) = sniffStream(embedUrl, fakeReferer, seen)

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "Không bắt được link chính.")
                return false
            }

            val playerReferer = "https://streamingnow.mov/"
            val cookie = CookieManager.getInstance().getCookie(result.url) ?: ""
            
            // ⭐ BỘ HEADER ĐẦY ĐỦ ĐỂ VƯỢT 403
            val finalHeaders = result.headers.toMutableMap().apply {
                put("Referer", playerReferer)
                put("Origin", "https://streamingnow.mov")
                if (cookie.isNotEmpty()) put("Cookie", cookie)
                // Thêm sec-ch-ua để vượt Cloudflare nếu cần
                put("sec-ch-ua", "\"Not-A.Brand\";v=\"99\", \"Google Chrome\";v=\"157\", \"Chromium\";v=\"157\"")
                put("sec-ch-ua-mobile", "?0")
                put("sec-ch-ua-platform", "\"Windows\"")
            }

            if (result.url.contains(".m3u8")) {
                // ⭐ DÙNG M3U8HELPER ĐỂ TẢI LẠI MANIFEST VỚI HEADER ĐẦY ĐỦ
                val qualities = M3u8Helper.generateM3u8(
                    source = this.name, 
                    streamUrl = result.url, 
                    referer = playerReferer, 
                    headers = finalHeaders
                )
                if (qualities.isNotEmpty()) {
                    qualities.forEach { callback.invoke(it) }
                } else {
                    // Nếu vẫn fail, phát link thô với header
                    callback(newExtractorLink(this.name, "MultiEmbed (Primary)", result.url, ExtractorLinkType.M3U8) {
                        this.referer = playerReferer
                        this.quality = Qualities.P1080.value
                        this.headers = finalHeaders
                    })
                }
            } else {
                callback(newExtractorLink(this.name, "MultiEmbed (Primary)", result.url, ExtractorLinkType.VIDEO) {
                    this.referer = playerReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = finalHeaders
                })
            }

            if (servers.isNotEmpty()) {
                extractSpecificServers(servers, subtitleCallback, callback)
            }

            return true
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}")
            return false
        }
    }
}

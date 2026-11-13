package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
                        if (subUrl.isNotEmpty()) subtitleCallback.invoke(SubtitleFile(item.optString("SubLanguageID", lang), subUrl))
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}

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
    // HIỂN THỊ WEBVIEW CHO NGƯỜI DÙNG GIẢI CAPTCHA VÀ TỰ ĐỘNG BẮT LINK M3U8
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String, referer: String, ua: String, seen: MutableList<String>
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) { cont.resume(null); return@post }

                try {
                    val wv = WebView(ctx)
                    var finished = false
                    var isCaptchaActive = false

                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                        cont.resume(res)
                    }

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt|\.mp4""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // WebView nền đen, hiển thị rõ ràng
                    wv.setBackgroundColor(Color.BLACK)

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
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            // Chỉ theo dõi DOM để biết khi nào có captcha, không can thiệp sâu
                            val js = """
                                (function() {
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

                            if (!u.contains(".png") && !u.contains(".jpg") && !u.contains(".css") && !u.contains(".js") && seen.size < 150) {
                                seen.add(u.take(150))
                            }

                            // BẮT LINK VIDEO
                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                val reqHeaders = request?.requestHeaders ?: emptyMap()
                                ClipboardLogger.logAndCopy("Sniff HIT", "Bắt được: $u")
                                handler.post { finish(SniffResult(u, reqHeaders)) }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) return true
                            if (!u.startsWith("http")) return true
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            ClipboardLogger.logAndCopy("Loaded", "$url")
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                ClipboardLogger.logAndCopy("WebView Error", "${request.url.host}: ${error?.errorCode} ${error?.description}")
                            }
                        }
                    }

                    // Toàn màn hình, không giấu WebView đi
                    val params = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    try {
                        CommonActivity.activity?.addContentView(wv, params)
                    } catch (_: Exception) {}

                    // Nếu là captcha, nhường quyền cho user. Nếu không, tự động click để phát video.
                    val clickRunnable = object : Runnable {
                        override fun run() {
                            if (finished) return
                            
                            // Nếu không có captcha, tự động click để kích hoạt server
                            if (!isCaptchaActive) {
                                try {
                                    wv.evaluateJavascript(
                                        "(function(){" +
                                        "var els=document.querySelectorAll('button,div,span,a,video');" +
                                        "for(var i=0;i<els.length;i++){" +
                                        "  var el=els[i];var txt=el.innerText||'';var cls=typeof el.className==='string'?el.className:'';" +
                                        // Click nút Play
                                        "  if(txt.indexOf('Play')!==-1||cls.indexOf('play')!==-1||cls.indexOf('jw-icon')!==-1){" +
                                        "    try{el.click();}catch(e){}" +
                                        "  }" +
                                        "}" +
                                        "var v=document.querySelector('video');if(v){v.muted=true;v.play().catch(function(){});}" +
                                        "})()", null
                                    )
                                    // Click vật lý (nếu cần)
                                    sendTouch(wv, 540f, 960f)
                                } catch (_: Exception) {}
                            }
                            
                            handler.postDelayed(this, 2500)
                        }
                    }
                    handler.postDelayed(clickRunnable, 2000)

                    // Timeout tổng 3 phút
                    handler.postDelayed({ 
                        if (!finished) finish(null) 
                    }, 180_000L)

                    // Load trang
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
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

        val ua = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
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
            val result = sniffStream(embedUrl, fakeReferer, ua, seen)

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "Không bắt được link.\nSeen:\n${seen.takeLast(10).joinToString("\n")}")
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

            if (result.url.contains(".m3u8")) {
                val qualities = M3u8Helper.generateM3u8(
                    source = this.name, streamUrl = result.url, referer = playerReferer, headers = finalHeaders
                )
                if (qualities.isNotEmpty()) {
                    qualities.forEach { callback.invoke(it) }
                } else {
                    callback(newExtractorLink(this.name, "MultiEmbed", result.url, ExtractorLinkType.M3U8) {
                        this.referer = playerReferer; this.quality = Qualities.P1080.value; this.headers = finalHeaders
                    })
                }
            } else {
                callback(newExtractorLink(this.name, "MultiEmbed", result.url, ExtractorLinkType.VIDEO) {
                    this.referer = playerReferer; this.quality = Qualities.Unknown.value; this.headers = finalHeaders
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

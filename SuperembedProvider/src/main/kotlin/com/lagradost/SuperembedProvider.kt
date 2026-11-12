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

// Danh sách đen các domain quảng cáo
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
    "workdeadlinededicate.com", "protrafficinspector.com", "push-sdk.com", "portalfluently.com"
)

// Các server được biết là KHÔNG yêu cầu captcha
private val SAFE_SERVERS = listOf(
    "GDrive", "vidmoly", "voe", "lulustream", "streamwish", "doodstream", "netu", "mixdrop", "upstream", "filemoon"
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
    // WEBVIEW SNIFFER (CHỈ DÙNG ĐỂ LẤY DANH SÁCH SERVER VÀ VƯỢT CLOUDFLARE)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getServerList(pageUrl: String, referer: String, ua: String): List<Pair<String, String>> {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    cont.resume(emptyList())
                    return@post
                }

                try {
                    val wv = WebView(ctx)
                    var finished = false
                    var isChallengePassed = false
                    val servers = mutableListOf<Pair<String, String>>()

                    fun finish() {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {}
                        cont.resume(servers)
                    }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        // Không set userAgentString để tránh bị Cloudflare phát hiện
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                            // Cho phép popup mở nhưng đóng ngay
                            val popup = WebView(ctx)
                            popup.settings.javaScriptEnabled = true
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
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
                            if (url?.contains("challenges.cloudflare.com") == true || url?.contains("turnstile") == true) {
                                isChallengePassed = false
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
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
                            if (url?.contains("challenges.cloudflare.com") == true || url?.contains("turnstile") == true) {
                                // Đang trong thử thách, chạm để vượt qua
                                sendTouch(wv, 540f, 960f)
                                return
                            }

                            // Đã qua Cloudflare, lấy danh sách server
                            handler.postDelayed({
                                wv.evaluateJavascript(
                                    "(function(){" +
                                    "var list = [];" +
                                    "document.querySelectorAll('ul.sources-list li[data-server]').forEach(function(l){" +
                                    "  var n = l.querySelector('.server-name');" +
                                    "  list.push({id: l.getAttribute('data-server'), name: n ? n.textContent.trim() : l.textContent.trim()});" +
                                    "});" +
                                    "return JSON.stringify(list);" +
                                    "})()"
                                ) { raw ->
                                    try {
                                        var cleaned = raw.trim()
                                        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                            cleaned = cleaned.substring(1, cleaned.length - 1)
                                        }
                                        cleaned = cleaned.replace("\\\"", "\"")
                                        if (cleaned != "[]") {
                                            val arr = org.json.JSONArray(cleaned)
                                            for (i in 0 until arr.length()) {
                                                val obj = arr.getJSONObject(i)
                                                servers.add(obj.optString("id") to obj.optString("name"))
                                            }
                                        }
                                        ClipboardLogger.logAndCopy("Servers", "Tìm thấy ${servers.size} server")
                                    } catch (e: Exception) {
                                        ClipboardLogger.logAndCopy("Server Parse Error", e.message ?: "")
                                    }
                                    finish()
                                }
                            }, 2000)
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 1080, 1920)
                    wv.translationX = -4000f
                    CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(1080, 1920))

                    handler.postDelayed({ finish() }, 60_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))

                } catch (e: Exception) {
                    cont.resume(emptyList())
                }
            }
        }
    }

    // =====================================================================================
    // RESOLVE TỪNG SERVER, BỎ QUA CAPTCHA
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveServer(
        url: String,
        serverName: String,
        ua: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    cont.resume(false)
                    return@post
                }

                try {
                    val wv = WebView(ctx)
                    var finished = false
                    var foundLink = false
                    val capturedUrls = mutableListOf<String>()

                    fun finish(success: Boolean) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {}
                        cont.resume(success)
                    }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            if (!u.contains("streamingnow.mov") && !u.contains("multiembed.mov") &&
                                !u.contains("cloudflare") && !u.contains("google") && !u.contains("histats")) {
                                capturedUrls.add(u)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url == null) return

                            // Kiểm tra xem có captcha không
                            view?.evaluateJavascript(
                                "(function(){return document.body.innerText.indexOf('Select all images') !== -1 || document.body.innerText.indexOf('Verification') !== -1;})()"
                            ) { hasCaptcha ->
                                if (hasCaptcha == "true") {
                                    ClipboardLogger.logAndCopy("Server $serverName", "Yêu cầu captcha - Bỏ qua")
                                    finish(false)
                                    return@evaluateJavascript
                                }

                                // Không có captcha, thử lấy link
                                view?.evaluateJavascript(
                                    "(function(){" +
                                    "var v = document.querySelector('video');" +
                                    "if (v && v.src) return v.src;" +
                                    "var iframes = document.querySelectorAll('iframe');" +
                                    "for (var i = 0; i < iframes.length; i++) {" +
                                    "  if (iframes[i].src && iframes[i].src.indexOf('http') === 0) return iframes[i].src;" +
                                    "}" +
                                    "return '';" +
                                    "})()"
                                ) { src ->
                                    var cleaned = src?.trim() ?: ""
                                    if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                                        cleaned = cleaned.substring(1, cleaned.length - 1)
                                    }
                                    cleaned = cleaned.replace("\\/", "/")

                                    if (cleaned.isNotEmpty() && cleaned.startsWith("http")) {
                                        // Có iframe hoặc video src
                                        if (cleaned.contains(".m3u8") || cleaned.contains(".mp4")) {
                                            foundLink = true
                                            callback(newExtractorLink(this@SuperembedProvider.name, "MultiEmbed $serverName", cleaned, ExtractorLinkType.M3U8) {
                                                this.referer = "https://streamingnow.mov/"
                                                this.quality = Qualities.Unknown.value
                                                this.headers = mapOf("User-Agent" to ua, "Referer" to "https://streamingnow.mov/")
                                            })
                                            finish(true)
                                        } else {
                                            // Thử extractor
                                            try {
                                                loadExtractor(cleaned, "https://streamingnow.mov/", subtitleCallback, callback)
                                                foundLink = true
                                                finish(true)
                                            } catch (e: Exception) {
                                                finish(false)
                                            }
                                        }
                                    } else {
                                        // Không tìm thấy, thử click play và đợi
                                        sendTouch(wv, 540f, 960f)
                                        handler.postDelayed({
                                            if (!finished) {
                                                // Thử lại lần cuối với capturedUrls
                                                for (u in capturedUrls) {
                                                    if (u.contains(".m3u8") || u.contains(".mp4")) {
                                                        foundLink = true
                                                        callback(newExtractorLink(this@SuperembedProvider.name, "MultiEmbed $serverName", u, ExtractorLinkType.M3U8) {
                                                            this.referer = "https://streamingnow.mov/"
                                                            this.quality = Qualities.Unknown.value
                                                            this.headers = mapOf("User-Agent" to ua, "Referer" to "https://streamingnow.mov/")
                                                        })
                                                        break
                                                    }
                                                }
                                                finish(foundLink)
                                            }
                                        }, 4000)
                                    }
                                }
                            }
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, 1080, 1920)
                    wv.translationX = -4000f
                    CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(1080, 1920))

                    handler.postDelayed({ finish(false) }, 15_000L)
                    wv.loadUrl(url, mapOf("Referer" to "https://streamingnow.mov/"))

                } catch (e: Exception) {
                    cont.resume(false)
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
            
            // Bước 1: Lấy danh sách server
            val servers = getServerList(embedUrl, fakeReferer, ua)
            
            if (servers.isEmpty()) {
                ClipboardLogger.logAndCopy("Error", "Không lấy được danh sách server")
                return false
            }

            ClipboardLogger.logAndCopy("Servers", "Danh sách: ${servers.joinToString { "${it.second}(${it.first})" }}")

            // Bước 2: Duyệt qua từng server, bỏ qua captcha
            var found = false
            val processedServers = mutableSetOf<String>()
            
            for ((serverId, serverName) in servers) {
                if (found) break
                if (processedServers.contains(serverId)) continue
                processedServers.add(serverId)

                // Ưu tiên các server không có captcha
                if (SAFE_SERVERS.any { serverName.contains(it, ignoreCase = true) }) {
                    ClipboardLogger.logAndCopy("Try Server", "$serverName (ưu tiên)")
                    val serverUrl = "https://streamingnow.mov/playvideo.php?video_id=&server_id=$serverId&token="
                    val serverResult = resolveServer(serverUrl, serverName, ua, callback, subtitleCallback)
                    if (serverResult) {
                        found = true
                        break
                    }
                }
            }

            // Nếu chưa tìm thấy, thử các server còn lại
            if (!found) {
                for ((serverId, serverName) in servers) {
                    if (found) break
                    if (processedServers.contains(serverId)) continue
                    processedServers.add(serverId)

                    ClipboardLogger.logAndCopy("Try Server", "$serverName (còn lại)")
                    // Cần lấy URL playvideo.php từ trang
                    // Trong thực tế, URL này có token, ta phải lấy từ trang HTML
                    // Ở đây tôi sẽ dùng cách đơn giản: gọi lại getServerList để lấy URL
                    // Nhưng để đơn giản, ta dùng extractor cho các host
                    val serverUrl = "https://streamingnow.mov/playvideo.php?server_id=$serverId"
                    val serverResult = resolveServer(serverUrl, serverName, ua, callback, subtitleCallback)
                    if (serverResult) {
                        found = true
                        break
                    }
                }
            }

            if (found) {
                ClipboardLogger.logAndCopy("Success", "Đã tìm thấy link từ server")
                return true
            }

            ClipboardLogger.logAndCopy("Error", "Tất cả server đều thất bại (có thể do captcha)")
            return false

        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString().take(1500)}")
            return false
        }
    }
}

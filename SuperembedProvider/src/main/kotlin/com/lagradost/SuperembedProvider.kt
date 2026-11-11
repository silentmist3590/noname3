package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
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
    "adsco.re", "ntwkbc21.com", "ntwkbc42.com", "usrpubtrck.com", "cdn4ads.com"
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
    // PHƯƠNG THỨC CLICK CHUỘT THẬT (REAL MOUSE CLICK)
    // =====================================================================================
    private fun realMouseClick(wv: WebView, x: Float, y: Float, handler: Handler) {
    val downTime = SystemClock.uptimeMillis()

    // 1. HOVER_MOVE (di chuyển chuột đến tọa độ)
    val hover = MotionEvent.obtain(
        downTime, downTime,
        MotionEvent.ACTION_HOVER_MOVE, x, y, 0
    )
    // Gán source qua reflection hoặc dùng obtain có source
    // Cách đơn giản nhất: Dùng obtain với đầy đủ tham số (API 29+)
    // Nếu không có, hãy dùng reflection (xem bên dưới)
    wv.dispatchGenericMotionEvent(hover)
    hover.recycle()

    // 2. ACTION_DOWN (nhấn chuột trái)
    handler.postDelayed({
        val down = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN, x, y, 0
        )
        // Dùng reflection để set source và buttonState
        try {
            val setSource = MotionEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)
            setSource.invoke(down, InputDevice.SOURCE_MOUSE)
            val setButtonState = MotionEvent::class.java.getMethod("setButtonState", Int::class.javaPrimitiveType)
            setButtonState.invoke(down, MotionEvent.BUTTON_PRIMARY)
        } catch (_: Exception) {}
        
        wv.dispatchTouchEvent(down)
        down.recycle()
    }, 50)

    // 3. ACTION_UP (nhả chuột trái)
    handler.postDelayed({
        val up = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP, x, y, 0
        )
        try {
            val setSource = MotionEvent::class.java.getMethod("setSource", Int::class.javaPrimitiveType)
            setSource.invoke(up, InputDevice.SOURCE_MOUSE)
            val setActionButton = MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
            setActionButton.invoke(up, MotionEvent.BUTTON_PRIMARY)
        } catch (_: Exception) {}
        
        wv.dispatchTouchEvent(up)
        up.recycle()
    }, 120)
}

    /**
     * Tìm tọa độ nút Play bằng JS, sau đó gửi click chuột thật vào tọa độ đó.
     */
    private fun performRealMouseClick(wv: WebView, handler: Handler) {
        val jsFindPlayButton = """
            (function() {
                var selectors = [
                    '#bigPlay',
                    '.jw-icon-display', '.jw-display-icon-container', '.jw-bigplay',
                    '.vjs-big-play-button', '.play-btn',
                    'media-play-button', '[data-media-button]',
                    'button[aria-label="Play"]', '[aria-label="Play"]',
                    '.play-button', '.btn-play'
                ];
                
                var button = null;
                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (el) { button = el; break; }
                }
                
                // Nếu không thấy nút Play, tìm video và chọn tâm video
                if (!button) {
                    var v = document.querySelector('video');
                    if (v) {
                        var r = v.getBoundingClientRect();
                        return JSON.stringify({x: r.left + r.width/2, y: r.top + r.height/2});
                    }
                    return null;
                }
                
                // Xóa overlay quảng cáo che nút
                var overlays = document.querySelectorAll('div[style*="z-index"], iframe');
                for (var i = 0; i < overlays.length; i++) {
                    var ov = overlays[i];
                    try {
                        var rect = ov.getBoundingClientRect();
                        var btnRect = button.getBoundingClientRect();
                        if (rect.left < btnRect.right && rect.right > btnRect.left &&
                            rect.top < btnRect.bottom && rect.bottom > btnRect.top) {
                            if (ov.id !== button.id && !ov.contains(button)) {
                                ov.remove();
                            }
                        }
                    } catch(e) {}
                }
                
                var rect = button.getBoundingClientRect();
                return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2});
            })();
        """.trimIndent()

        wv.evaluateJavascript(jsFindPlayButton) { rawResult ->
            if (rawResult.isNullOrEmpty() || rawResult == "null") {
                ClipboardLogger.logAndCopy("MouseClick", "Không tìm thấy nút Play, click giữa màn hình")
                realMouseClick(wv, 540f, 960f, handler)
                return@evaluateJavascript
            }

            try {
                var cleaned = rawResult.trim()
                if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
                    cleaned = cleaned.substring(1, cleaned.length - 1)
                }
                cleaned = cleaned.replace("\\\"", "\"").replace("\\\\", "\\")

                val json = JSONObject(cleaned)
                val x = json.optDouble("x", 540.0).toFloat()
                val y = json.optDouble("y", 960.0).toFloat()

                ClipboardLogger.logAndCopy("MouseClick", "Tọa độ nút Play: x=$x, y=$y")
                realMouseClick(wv, x, y, handler)
            } catch (e: Exception) {
                ClipboardLogger.logAndCopy("MouseClick Error", "Lỗi parse: ${e.message}, raw=$rawResult")
                realMouseClick(wv, 540f, 960f, handler)
            }
        }
    }

    // =====================================================================================
    // WEBVIEW SNIFFER
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(pageUrl: String, referer: String, ua: String, seen: MutableList<String>): SniffResult? {
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
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Chặn popup quảng cáo
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    }

                    // Script chống bot
                    val antiBotJs = """
                        (function() {
                            Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                            Object.defineProperty(navigator, 'languages', {get: () => ['vi-VN', 'vi', 'en']});
                            Object.defineProperty(navigator, 'plugins', {get: () => [1,2,3,4,5]});
                            window.chrome = { runtime: {} };
                            window.open = function() { return { closed: false, focus: function(){}, close: function(){}, blur: function(){} }; };
                        })();
                    """.trimIndent()

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(antiBotJs, null)
                            // Gọi phương thức Click Chuột Thật
                            performRealMouseClick(wv, handler)
                        } catch (_: Exception) {}
                        
                        if (taps < 60) handler.postDelayed({ tap() }, 2000)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            wv.evaluateJavascript(antiBotJs, null)
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            val reqHeaders = request?.requestHeaders ?: emptyMap()

                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                seen.add("BLOCKED AD: " + u.take(100))
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }

                            if (!junkRegex.containsMatchIn(u) && seen.size < 150) {
                                seen.add(u.take(220))
                            }

                            if (streamRegex.containsMatchIn(u) && !u.contains("/embed/iframe_player/")) {
                                val hit = SniffResult(u, reqHeaders)
                                ClipboardLogger.logAndCopy("Sniff HIT", "URL: $u")
                                handler.post { finish(hit) }
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val u = request?.url?.toString() ?: return false
                            return AD_DOMAINS.any { u.contains(it, ignoreCase = true) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            wv.evaluateJavascript(antiBotJs, null)
                            handler.postDelayed({ tap() }, 1000)
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

                    handler.postDelayed({ finish(null) }, 180_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
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
            val directUrl = if (type == "movie") {
                "$BASE_URL/directstream.php?video_id=$tmdbId&tmdb=1"
            } else {
                "$BASE_URL/directstream.php?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
            }

            ClipboardLogger.logAndCopy("Direct API", "Thử: $directUrl")
            val directRes = app.get(directUrl, headers = mapOf("User-Agent" to ua, "Referer" to fakeReferer)).text
            val directM3u8 = Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""", RegexOption.IGNORE_CASE)
                .find(directRes)?.value

            if (directM3u8 != null) {
                ClipboardLogger.logAndCopy("Direct Success", "Tìm thấy M3U8: $directM3u8")
                val m3u8Headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "https://streamingnow.mov/",
                    "Origin" to "https://streamingnow.mov"
                )
                val qualities = M3u8Helper.generateM3u8(
                    source = this.name,
                    streamUrl = directM3u8,
                    referer = "https://streamingnow.mov/",
                    headers = m3u8Headers
                )
                if (qualities.isNotEmpty()) {
                    qualities.forEach { callback.invoke(it) }
                } else {
                    callback.invoke(newExtractorLink(this.name, "MultiEmbed Direct", directM3u8, ExtractorLinkType.M3U8) {
                        this.referer = "https://streamingnow.mov/"; this.quality = Qualities.P1080.value; this.headers = m3u8Headers
                    })
                }
                return true
            }

            val embedUrl = if (type == "movie") {
                "$BASE_URL/?video_id=$tmdbId&tmdb=1"
            } else {
                "$BASE_URL/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
            }

            ClipboardLogger.logAndCopy("Fallback WebView", "Tải WebView: $embedUrl")
            val seen = mutableListOf<String>()
            val result = sniffStream(embedUrl, fakeReferer, ua, seen)

            if (result == null) {
                ClipboardLogger.logAndCopy("Error", "WebView không bắt được link stream.")
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
            
            if (cookie.isNotEmpty()) {
                ClipboardLogger.logAndCopy("Cookie Found", "Đã đính kèm Cookie: ${cookie.take(50)}...")
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
                    this.referer = playerReferer; this.quality = Qualities.P1080.value; this.headers = finalHeaders
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

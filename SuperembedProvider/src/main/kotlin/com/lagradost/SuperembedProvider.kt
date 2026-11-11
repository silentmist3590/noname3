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
    // WEBVIEW SNIFFER (Cơ chế Vidscmov + Cookie)
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

                    // Script Vidscmov: Fake popup và ép video phát
                    val fakePopup = "try{window.open=function(){return {closed:false,focus:function(){},close:function(){},blur:function(){}}};}catch(e){}"
                    val clickJs = "(function(){function fire(el){if(!el)return;try{el.click();}catch(e){}}" +
                        "fire(document.getElementById('bigPlay'));" +
                        "fire(document.querySelector('.jw-icon-display,.jw-display-icon-container,.jw-bigplay,[aria-label=Play],.vjs-big-play-button,.play-btn'));" +
                        "var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}})();"

                    var taps = 0
                    fun tap() {
                        if (finished) return
                        taps++
                        try {
                            wv.evaluateJavascript(fakePopup + clickJs, null)
                            // Chạm vào giữa màn hình để đánh lừa iframe
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Exception) {}
                        // Lặp lại 30 lần (khoảng 45 giây)
                        if (taps < 30) handler.postDelayed({ tap() }, 1500)
                    }

                    wv.webViewClient = object : WebViewClient() {
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
                        CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    } catch (_: Exception) {}

                    handler.postDelayed({ finish(null) }, 120_000L)
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
            // 1. THỬ DIRECT API
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

            // 2. DÙNG WEBVIEW (CƠ CHẾ VIDSCMOV)
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

            // ⭐ LẤY COOKIE VÀ GỘP HEADERS
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

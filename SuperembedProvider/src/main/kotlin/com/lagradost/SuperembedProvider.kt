package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
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

private data class SniffResult(val url: String, val headers: Map<String, String>, val serverName: String)

// Danh sách đen các domain quảng cáo (từ log thực tế)
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
    // WEBVIEW SNIFFER (POPUP 5S + CLICK CHUỘT THẬT)
    // =====================================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String,
        referer: String,
        ua: String,
        seen: MutableList<String>
    ): List<SniffResult> {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    ClipboardLogger.logAndCopy("Sniff Error", "Không có Context")
                    cont.resume(emptyList())
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    var challengePassed = false
                    var clickCount = 0
                    var serverScanStarted = false
                    var serverIndex = 0
                    var servers = org.json.JSONArray()
                    val currentServer = java.util.concurrent.atomic.AtomicReference("Default")
                    val hits = LinkedHashMap<String, SniffResult>()

                    fun finish() {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        val found = synchronized(hits) { hits.values.toList() }
                        try {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Exception) {}
                        ClipboardLogger.logAndCopy(
                            "Sniff Complete",
                            "Có ${found.size} link: ${found.map { it.serverName }.distinct().joinToString()}"
                        )
                        cont.resume(found)
                    }

                    fun capture(url: String, headers: Map<String, String>) {
                        val valid = Regex(
                            """(\.m3u8|\.mpd|master\.txt)(?:[?#]|$)""",
                            RegexOption.IGNORE_CASE
                        ).containsMatchIn(url)
                        if (!valid || (!url.startsWith("http://") && !url.startsWith("https://"))) return
                        if (url.contains("/embed/iframe_player/", ignoreCase = true)) return
                        synchronized(hits) {
                            if (!hits.containsKey(url)) {
                                val label = currentServer.get().ifBlank { "Default" }
                                hits[url] = SniffResult(url, headers.toMap(), label)
                                ClipboardLogger.logAndCopy("Sniff HIT [$label]", url.take(350))
                            }
                        }
                    }

                    val streamRegex = Regex(
                        """(\.m3u8|\.mpd|master\.txt)(?:[?#]|$)""",
                        RegexOption.IGNORE_CASE
                    )
                    val junkRegex = Regex(
                        """\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf)(\?|$)""",
                        RegexOption.IGNORE_CASE
                    )

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    // Giữ nguyên cách xử lý popup hiện tại để không làm thay đổi luồng phát đã chạy được.
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean,
                            isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean {
                            return try {
                                val popup = WebView(ctx)
                                popup.settings.javaScriptEnabled = true
                                popup.settings.domStorageEnabled = true
                                popup.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                                    override fun shouldInterceptRequest(
                                        view: WebView?, request: WebResourceRequest?
                                    ): WebResourceResponse {
                                        return WebResourceResponse(
                                            "text/plain", "utf-8",
                                            ByteArrayInputStream(ByteArray(0))
                                        )
                                    }
                                }
                                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = popup
                                resultMsg?.sendToTarget()
                                handler.postDelayed({
                                    try {
                                        popup.stopLoading()
                                        (popup.parent as? ViewGroup)?.removeView(popup)
                                        popup.destroy()
                                    } catch (_: Exception) {}
                                }, 5000)
                                true
                            } catch (_: Exception) {
                                false
                            }
                        }
                    }

                    // Giữ nguyên JavaScript và thao tác click chuột thật đã phát video thành công.
                    val clickJs = """
                        (function() {
                            function fire(sel) {
                                var els = document.querySelectorAll(sel);
                                for (var i = 0; i < els.length; i++) {
                                    try { els[i].click(); } catch(e) {}
                                }
                            }
                            var overlays = document.querySelectorAll('div[style*="z-index: 999999"], div[style*="z-index: 2147483647"]');
                            for (var i = 0; i < overlays.length; i++) {
                                try { overlays[i].remove(); } catch(e) {}
                            }
                            fire('#bigPlay');
                            fire('.jw-icon-display, .jw-display-icon-container, .jw-bigplay');
                            fire('.vjs-big-play-button, .play-btn, .play-button');
                            fire('media-player, media-play-button, [data-media-button]');
                            fire('button[aria-label="Play"], [aria-label="Play"]');
                            var v = document.querySelector('video');
                            if (v) {
                                v.muted = true;
                                var p = v.play();
                                if (p && p.catch) p.catch(function(){});
                            }
                        })();
                    """.trimIndent()

                    // Selector dựa trên cấu trúc danh sách server đã quan sát: li[data-server].
                    val listServersJs = """
                        (function() {
                            var result = [], used = {};
                            var nodes = document.querySelectorAll('ul.sources-list li[data-server], .sources-list li[data-server], li[data-server]');
                            for (var i = 0; i < nodes.length; i++) {
                                var id = nodes[i].getAttribute('data-server');
                                var name = (nodes[i].innerText || nodes[i].textContent || '').trim().replace(/\s+/g, ' ');
                                if (id && !used[id]) {
                                    used[id] = true;
                                    result.push({id:id, name:name || ('Server ' + (result.length + 1))});
                                }
                            }
                            return JSON.stringify(result);
                        })();
                    """.trimIndent()

                    val selectServerPrefix = """
                        (function(id) {
                            var nodes = document.querySelectorAll('ul.sources-list li[data-server], .sources-list li[data-server], li[data-server]');
                            for (var i = 0; i < nodes.length; i++) {
                                if (nodes[i].getAttribute('data-server') === id) {
                                    try { nodes[i].click(); } catch(e) {}
                                    return true;
                                }
                            }
                            return false;
                        })(
                    """.trimIndent()

                    fun clickPlayer() {
                        if (finished) return
                        try {
                            wv.evaluateJavascript(clickJs, null)
                            sendRealMouseClick(wv, w / 2f, h / 2f)
                        } catch (_: Exception) {}
                    }

                    fun scanNextServer() {
                        if (finished) return
                        if (serverIndex >= servers.length()) {
                            ClipboardLogger.logAndCopy(
                                "Server scan",
                                "Đã thử ${servers.length()} server; đợi thêm 8 giây."
                            )
                            handler.postDelayed({ finish() }, 8000)
                            return
                        }

                        val server = servers.optJSONObject(serverIndex)
                        serverIndex++
                        if (server == null) {
                            handler.postDelayed({ scanNextServer() }, 500)
                            return
                        }

                        val id = server.optString("id")
                        val label = server.optString("name").ifBlank { "Server $serverIndex" }
                        currentServer.set(label)
                        ClipboardLogger.logAndCopy(
                            "Server $serverIndex/${servers.length()}",
                            "Đang thử $label"
                        )
                        wv.evaluateJavascript("$selectServerPrefix${org.json.JSONObject.quote(id)})", null)

                        // Giữ khoảng chờ 5,5 giây trước click; tiếp tục chờ 5,5 giây
                        // để request playlist của server có thời gian xuất hiện.
                        handler.postDelayed({
                            clickPlayer()
                            handler.postDelayed({ scanNextServer() }, 5500)
                        }, 5500)
                    }

                    fun beginServerScan() {
                        if (finished || serverScanStarted) return
                        serverScanStarted = true
                        wv.evaluateJavascript(listServersJs) { raw ->
                            if (!finished) {
                                try {
                                    val decoded = if (raw.isNullOrBlank() || raw == "null") "[]" else
                                        org.json.JSONObject("{\"v\":$raw}").optString("v", "[]")
                                    servers = org.json.JSONArray(decoded)
                                    ClipboardLogger.logAndCopy("Server scan", "Tìm thấy ${servers.length()} server.")
                                    if (servers.length() == 0) {
                                        handler.postDelayed({ finish() }, 8000)
                                    } else {
                                        scanNextServer()
                                    }
                                } catch (e: Exception) {
                                    ClipboardLogger.logAndCopy("Server parse", e.message ?: "Parse lỗi")
                                    handler.postDelayed({ finish() }, 8000)
                                }
                            }
                        }
                    }

                    fun scheduleClick() {
                        if (finished) return
                        if (!challengePassed) {
                            handler.postDelayed({ scheduleClick() }, 1000)
                            return
                        }
                        clickCount++
                        clickPlayer()
                        ClipboardLogger.logAndCopy("Click #$clickCount", "Mouse click, chờ 5.5s")
                        if (clickCount < 6) {
                            handler.postDelayed({ scheduleClick() }, 5500)
                        } else {
                            ClipboardLogger.logAndCopy(
                                "Click",
                                "Đã click 6 lần, chờ thêm 8s trước khi quét server..."
                            )
                            handler.postDelayed({ beginServerScan() }, 8000)
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?, url: String?, favicon: android.graphics.Bitmap?
                        ) {
                            if (url?.contains("challenges.cloudflare.com") == true ||
                                url?.contains("turnstile") == true
                            ) challengePassed = false
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return null
                            val headers = request.requestHeaders ?: emptyMap()
                            if (AD_DOMAINS.any { url.contains(it, ignoreCase = true) }) {
                                synchronized(seen) {
                                    if (seen.size < 200) seen.add("BLOCKED AD: " + url.take(100))
                                }
                                return WebResourceResponse(
                                    "text/plain", "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            if (!junkRegex.containsMatchIn(url)) {
                                synchronized(seen) {
                                    if (seen.size < 200) seen.add(url.take(220))
                                }
                            }
                            if (streamRegex.containsMatchIn(url)) capture(url, headers)
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (AD_DOMAINS.any { url.contains(it, ignoreCase = true) }) return true
                            return !url.startsWith("http://") && !url.startsWith("https://")
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url?.contains("challenges.cloudflare.com") == true ||
                                url?.contains("turnstile") == true
                            ) {
                                challengePassed = false
                                sendRealMouseClick(wv, w / 2f, h / 2f)
                            } else if (!challengePassed) {
                                ClipboardLogger.logAndCopy("Cloudflare", "Trang tải xong; bắt đầu click...")
                                challengePassed = true
                                handler.postDelayed({ scheduleClick() }, 500)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?
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

                    // Timeout đủ cho lượt click gốc và thử tuần tự các server.
                    handler.postDelayed({ finish() }, 180_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Sniff Exception", e.stackTraceToString().take(800))
                    cont.resume(emptyList())
                }
            }
        }
    }

    // ⭐ CLICK CHUỘT THẬT (SOURCE_MOUSE + BUTTON_PRIMARY)
    private fun sendRealMouseClick(wv: WebView, x: Float, y: Float) {
        try {
            val downTime = SystemClock.uptimeMillis()

            // 1. HOVER_MOVE - di chuyển chuột
            try {
                val hover = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
                try {
                    val f = MotionEvent::class.java.getDeclaredField("mSource")
                    f.isAccessible = true
                    f.setInt(hover, InputDevice.SOURCE_MOUSE)
                } catch (_: Exception) {}
                wv.dispatchGenericMotionEvent(hover)
                hover.recycle()
            } catch (_: Exception) {}

            // 2. ACTION_DOWN
            try {
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
                try {
                    val f = MotionEvent::class.java.getDeclaredField("mSource")
                    f.isAccessible = true
                    f.setInt(down, InputDevice.SOURCE_MOUSE)
                } catch (_: Exception) {}
                try {
                    val f = MotionEvent::class.java.getDeclaredField("mButtonState")
                    f.isAccessible = true
                    f.setInt(down, MotionEvent.BUTTON_PRIMARY)
                } catch (_: Exception) {}
                wv.dispatchTouchEvent(down)
                down.recycle()
            } catch (_: Exception) {}

            // 3. ACTION_UP
            try {
                val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                try {
                    val f = MotionEvent::class.java.getDeclaredField("mSource")
                    f.isAccessible = true
                    f.setInt(up, InputDevice.SOURCE_MOUSE)
                } catch (_: Exception) {}
                try {
                    val f = MotionEvent::class.java.getDeclaredField("mButtonState")
                    f.isAccessible = true
                    f.setInt(up, 0)
                } catch (_: Exception) {}
                try {
                    val f = MotionEvent::class.java.getDeclaredField("mActionButton")
                    f.isAccessible = true
                    f.setInt(up, MotionEvent.BUTTON_PRIMARY)
                } catch (_: Exception) {}
                wv.dispatchTouchEvent(up)
                up.recycle()
            } catch (_: Exception) {}
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

        if (type == "tv" && (season == null || episode == null)) {
            ClipboardLogger.logAndCopy("Error", "Thiếu season/episode")
            return false
        }

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

            ClipboardLogger.logAndCopy("WebView", "Tải: $embedUrl")
            val seen = mutableListOf<String>()
            val results = sniffStream(embedUrl, fakeReferer, ua, seen)

            if (results.isEmpty()) {
                ClipboardLogger.logAndCopy(
                    "Error",
                    "Không bắt được link video hoạt động.\nSeen cuối:\n${seen.takeLast(10).joinToString("\n")}"
                )
                return false
            }

            val playerReferer = "https://streamingnow.mov/"
            val emittedUrls = mutableSetOf<String>()
            var emittedCount = 0

            for (result in results) {
                if (!emittedUrls.add(result.url)) continue

                val cookie = CookieManager.getInstance().getCookie(result.url) ?: ""
                val finalHeaders = result.headers.toMutableMap().apply {
                    put("User-Agent", ua)
                    put("Referer", playerReferer)
                    put("Origin", "https://streamingnow.mov")
                    if (cookie.isNotEmpty()) put("Cookie", cookie)
                }

                val serverLabel = result.serverName.ifBlank { "Server" }
                try {
                    if (result.url.contains(".m3u8", ignoreCase = true)) {
                        callback(newExtractorLink(this.name, serverLabel, result.url, ExtractorLinkType.M3U8) {
                            this.referer = playerReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = finalHeaders
                        })
                        emittedCount++
                    } else {
                        loadExtractor(result.url, playerReferer, subtitleCallback) { link ->
                            callback(link)
                            emittedCount++
                        }
                    }
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy(
                        "Server skipped [$serverLabel]",
                        "Không xử lý được link: ${e.message ?: "unknown"}"
                    )
                }
            }

            ClipboardLogger.logAndCopy(
                "LoadLinks result",
                "Bắt được ${results.size} URL; đã gửi $emittedCount link cho CloudStream."
            )
            return emittedCount > 0
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            ClipboardLogger.logAndCopy("Exception", "Error: ${e.message}\nStackTrace: ${e.stackTraceToString().take(1500)}")
            return false
        }
    }
}

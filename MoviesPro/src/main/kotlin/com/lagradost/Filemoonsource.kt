package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Nguồn "Filemoon" = player.php (streamingnow.mov). Player này chỉ chạy đúng khi NHÚNG trong iframe của moviespro.top.
 * Giai đoạn 1: nhúng player trong trang giả (base = moviespro.top), chạm giữa khung, chờ quảng cáo 5s rồi đóng, chạm lại
 *              -> player nạp các URL playvideo.php (mỗi server một URL).
 * Giai đoạn 2: với từng server cần dùng, nhúng playvideo.php trong trang giả (base = streamingnow.mov, cùng origin)
 *              -> đọc được nội dung iframe: captcha / iframe con / video.
 */
object FilemoonSource {
    class Result(val servers: List<Pair<String, List<String>>>, val captcha: List<String>)

    // server_id -> tên (id lấy từ log ServerList)
    val WANT = linkedMapOf(
        "88" to "vipstream-Y", "90" to "vipstream-F", "91" to "vipstream-R",
        "34" to "GDrive", "29" to "voe", "45" to "streamwish", "12" to "mixdrop"
    )
    private const val AD_WAIT_MS = 5000L

    private val AD_DOMAINS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "popads.net", "onclickads.net",
        "propellerads.com", "popcash.net", "adsterra.com", "adcash.com", "rapidadexchange.com", "youradexchange.com",
        "adexchangerapid.com", "googletagmanager.com", "googletagservices.com", "crwdcntrl.net", "tynt.com",
        "mrktmtrcs.net", "dtscout.com", "onaudience.com", "histats.com", "luugy.com", "llvpn.com", "rtmark.net",
        "dvzcfqpdoaxtr.website", "adsco.re", "ntwkbc21.com", "ntwkbc42.com", "usrpubtrck.com", "cdn4ads.com",
        "bkkunbeurvocc.website", "enveulpwblred.space", "bdcdmyzacrjkh.space", "acscdn.com", "aclib.net",
        "createlouisville.org", "workdeadlinededicate.com", "protrafficinspector.com", "push-sdk.com", "portalfluently.com",
        "highrevenueformat.com"
    )
    private val SKIP_HOSTS = listOf("streamingnow.mov", "multiembed.mov", "ghostplayer.store", "moviespro.top", "cloudflare", "gstatic", "googleapis", "google.com", "histats", "cloudfront", "facebook")
    private val JUNK = Regex("""\.(css|png|jpe?g|gif|svg|ico|woff2?|ttf|js)(\?|$)""", RegexOption.IGNORE_CASE)
    private val STREAM = Regex("""\.m3u8|\.mpd|\.mp4(\?|$)|master\.txt""", RegexOption.IGNORE_CASE)

    private fun wrapper(src: String) =
        "<!doctype html><html><body style=\"margin:0;background:#000\">" +
            "<iframe id=\"f\" src=\"" + src.replace("\"", "&quot;") + "\" allow=\"autoplay; fullscreen; encrypted-media\" allowfullscreen " +
            "style=\"position:fixed;left:0;top:0;width:100vw;height:100vh;border:0\"></iframe></body></html>"

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(playerUrl: String, ua: String?): Result? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) {
                    MoviesProClipboardLogger.stage("Filemoon", "Không có Context")
                    cont.resume(null); return@post
                }
                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    var finished = false
                    var stage = 1
                    var stage2Started = false
                    val collected = ArrayList<String>()
                    val capture = ArrayList<String>()
                    val popups = ArrayList<WebView>()
                    val servers = ArrayList<Pair<String, List<String>>>()
                    val captcha = ArrayList<String>()
                    var streamHit = false

                    fun finish(res: Result?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            popups.toList().forEach { p -> try { p.destroy() } catch (_: Throwable) {} }
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        } catch (_: Throwable) {}
                        cont.resume(res)
                    }

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    // Không đổi UA: UA giả lệch client-hints làm Turnstile từ chối

                    fun sendTouch(x: Float, y: Float) {
                        try {
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
                            val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
                            wv.dispatchTouchEvent(d); wv.dispatchTouchEvent(u)
                            d.recycle(); u.recycle()
                        } catch (_: Throwable) {}
                    }

                    // ===== Quảng cáo: mở popup thật, giữ 5s cho tải xong rồi đóng =====
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                            if (finished || popups.size >= 4) return false
                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                            return try {
                                val pw = WebView(ctx)
                                pw.settings.javaScriptEnabled = true
                                pw.settings.domStorageEnabled = true
                                pw.webViewClient = WebViewClient()
                                pw.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                                pw.layout(0, 0, w, h)
                                transport.webView = pw
                                resultMsg.sendToTarget()
                                popups.add(pw)
                                MoviesProClipboardLogger.stage("Popup", "mở quảng cáo, đóng sau ${AD_WAIT_MS / 1000}s")
                                handler.postDelayed({
                                    try { pw.stopLoading(); pw.destroy() } catch (_: Throwable) {}
                                    popups.remove(pw)
                                    MoviesProClipboardLogger.stage("Popup", "đã đóng quảng cáo")
                                }, AD_WAIT_MS)
                                true
                            } catch (_: Throwable) { false }
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            // trang cha không bao giờ được điều hướng đi (quảng cáo tự chuyển trang)
                            return request?.isForMainFrame == true
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (AD_DOMAINS.any { u.contains(it, true) }) {
                                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            if (u.contains("playvideo.php") && stage == 1) {
                                var first = false
                                synchronized(collected) { if (!collected.contains(u)) { collected.add(u); first = collected.size == 1 } }
                                if (first && !stage2Started) {
                                    stage2Started = true
                                    handler.postDelayed({ startStage2() }, 4000L)
                                }
                            }
                            if (!JUNK.containsMatchIn(u) || STREAM.containsMatchIn(u)) {
                                synchronized(capture) { if (capture.size < 800) capture.add(u.take(400)) }
                            }
                            return null
                        }
                    }

                    // ===== Giai đoạn 2 =====
                    val queue = ArrayList<Pair<String, String>>() // (tên, url)
                    var qi = 0
                    var rl = 0
                    fun inspect(cb: (String) -> Unit) {
                        wv.evaluateJavascript(
                            "(function(){var f=document.getElementById('f');if(!f)return 'noframe';try{var d=f.contentDocument;var h=d.documentElement.innerHTML;" +
                                "var cap=/captcha-holder|captcha_answer|Select all images|Verification/i.test(h);var r=[];" +
                                "d.querySelectorAll('iframe,video,source').forEach(function(e){if(e.src)r.push(e.src)});" +
                                "var rl=/Error 1015|rate limited/i.test(h);return (rl?'RATELIMIT':(cap?'CAPTCHA':'OK'))+'|'+r.join(' ')}catch(e){return 'ERR '+e}})()"
                        ) { raw ->
                            cb(try { org.json.JSONArray("[" + (raw ?: "\"\"") + "]").optString(0) } catch (_: Throwable) { raw ?: "" })
                        }
                    }

                    fun step() {
                        if (finished) return
                        if (qi >= queue.size) {
                            if (captcha.isNotEmpty()) {
                                val msg = "Server cần captcha (bỏ qua): " + captcha.joinToString(", ")
                                MoviesProClipboardLogger.stage("Captcha", msg)
                                try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
                            }
                            finish(Result(servers.toList(), captcha.toList()))
                            return
                        }
                        val (nm, url) = queue[qi]
                        val mark = synchronized(capture) { capture.size }
                        wv.loadDataWithBaseURL("https://streamingnow.mov/", wrapper(url), "text/html", "utf-8", null)
                        handler.postDelayed({
                            val got = synchronized(capture) { capture.drop(mark).toList() }
                            val useful = got.filter { x -> SKIP_HOSTS.none { x.contains(it, true) } && AD_DOMAINS.none { x.contains(it, true) } && (!JUNK.containsMatchIn(x) || STREAM.containsMatchIn(x)) }.distinct()
                            inspect { t ->
                                val head = t.substringBefore("|")
                                val inner = t.substringAfter("|", "").split(" ").filter { it.startsWith("http") && AD_DOMAINS.none { a -> it.contains(a, true) } }
                                val all = (inner + useful).distinct()
                                when {
                                    head == "RATELIMIT" && rl < 3 -> {
                                        rl++
                                        MoviesProClipboardLogger.stage("Server $nm", "RATE LIMIT 1015, chờ 30s thử lại ($rl/3)")
                                        handler.postDelayed({ step() }, 30_000L)
                                        return@inspect
                                    }
                                    head == "RATELIMIT" -> {
                                        rl = 0
                                        MoviesProClipboardLogger.stage("Server $nm", "RATE LIMIT - bỏ qua")
                                    }
                                    head == "CAPTCHA" -> {
                                        captcha.add(nm)
                                        MoviesProClipboardLogger.stage("Server $nm", "CAPTCHA - bỏ qua")
                                    }
                                    else -> {
                                        servers.add(nm to all)
                                        MoviesProClipboardLogger.stage("Server $nm", "[$head] " + all.take(8).joinToString("\n").ifEmpty { "(không có link)" })
                                        if (all.isEmpty()) MoviesProClipboardLogger.stage("Req $nm", got.takeLast(12).joinToString("\n") { it.take(180) })
                                    }
                                }
                                qi++
                                handler.postDelayed({ step() }, 2500L)
                            }
                        }, 6000L)
                    }

                    fun startStage2Impl() {
                        stage = 2
                        val urls = synchronized(collected) { collected.toList() }
                        MoviesProClipboardLogger.stage("Playvideo", "thu được ${urls.size} URL: " + urls.joinToString { Regex("server_id=(\\d+)").find(it)?.groupValues?.get(1) ?: "?" })
                        for ((sid, nm) in WANT) {
                            val u = urls.firstOrNull { Regex("server_id=$sid(&|$)").containsMatchIn(it) }
                            if (u != null) queue.add(nm to u) else MoviesProClipboardLogger.stage("Server $nm", "không có URL (server_id=$sid)")
                        }
                        if (queue.isEmpty()) finish(Result(emptyList(), emptyList())) else step()
                    }
                    startStage2Hook = { startStage2Impl() }

                    // ===== Giai đoạn 1: chạm giữa khung, chờ quảng cáo, chạm lại =====
                    var taps = 0
                    val spots = listOf(0.5f to 0.5f, 0.5f to 0.56f, 0.5f to 0.44f)
                    fun tap() {
                        if (finished || stage != 1 || stage2Started) return
                        if (taps >= 40) { MoviesProClipboardLogger.stage("Tap", "hết 40 lần chạm mà chưa có playvideo.php"); finish(Result(emptyList(), emptyList())); return }
                        if (popups.isEmpty()) {
                            val (fx, fy) = spots[taps % spots.size]
                            taps++
                            sendTouch(w * fx, h * fy)
                        }
                        handler.postDelayed({ tap() }, 2000L)
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    try { CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(w, h)) } catch (_: Throwable) {}

                    handler.postDelayed({ finish(null) }, 300_000L)
                    MoviesProClipboardLogger.stage("Filemoon", "nhúng player: $playerUrl")
                    wv.loadDataWithBaseURL("https://moviespro.top/", wrapper(playerUrl), "text/html", "utf-8", null)
                    handler.postDelayed({ tap() }, 4000L)
                } catch (e: Throwable) {
                    MoviesProClipboardLogger.stage("Filemoon Exception", e.stackTraceToString().take(800))
                    cont.resume(null)
                }
            }
        }
    }

    // cầu nối: startStage2() được gọi từ shouldInterceptRequest (khai báo trước nên cần hook)
    private var startStage2Hook: (() -> Unit)? = null
    private fun startStage2() { startStage2Hook?.invoke() }
}

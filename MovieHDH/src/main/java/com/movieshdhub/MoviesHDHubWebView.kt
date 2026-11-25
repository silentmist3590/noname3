package com.movieshdhub

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class SniffResult(
    val url: String,
    val referer: String,
    val cookies: String,
    val requestHeaders: Map<String, String>
)

object WebViewSniffer {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val AD_DOMAINS = listOf(
        "doubleclick", "googlesyndication", "googleadservices",
        "google-analytics", "googletagmanager", "googletagservices",
        "amung.us", "histats", "waust.at", "adservice", "adskeeper",
        "disable-devtool", "acscdn", "rapidadexchange", "usrpubtrck",
        "suv5", "aclib", "cloudflareinsights", "beacon.min.js",
        "cast_sender", "cdn-cgi/rum", "gstatic.com/cv", "cuid"
    )

    private val SEGMENT_PATTERN = Regex(""".*\.(ts|m4s|key|aac|vtt|srt|jpg|png|webp|gif)(\?.*)?$""")

    suspend fun sniff(
        url: String,
        referer: String,
        headers: Map<String, String>,
        timeoutMs: Long = 60_000L
    ): List<SniffResult> {
        val activity = CommonActivity.activity ?: return emptyList()

        return suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val results = ConcurrentHashMap<String, SniffResult>()
            val done = AtomicBoolean(false)
            val resumed = AtomicBoolean(false)
            var lastPageUrl = url
            var timeoutRunnable: Runnable? = null
            var finishRunnable: Runnable? = null

            fun finishOnce(value: List<SniffResult>) {
                if (!resumed.compareAndSet(false, true)) return
                timeoutRunnable?.let { main.removeCallbacks(it) }
                finishRunnable?.let { main.removeCallbacks(it) }
                if (cont.isActive) cont.resume(value)
            }

            main.post {
                try {
                    val overlay = FrameLayout(activity).apply {
                        setBackgroundColor(0xE6000000.toInt())
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    val spinner = ProgressBar(activity).apply {
                        isIndeterminate = true
                        layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                            gravity = Gravity.CENTER
                        }
                    }

                    @Suppress("SetJavaScriptEnabled")
                    val webView = WebView(activity).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = UA
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportZoom(false)
                        }
                        setBackgroundColor(0xFF000000.toInt())
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                    }

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                    overlay.addView(
                        webView,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    overlay.addView(spinner)

                    fun cleanup() {
                        if (!done.compareAndSet(false, true)) return
                        timeoutRunnable?.let { main.removeCallbacks(it) }
                        finishRunnable?.let { main.removeCallbacks(it) }
                        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                        main.post {
                            try { overlay.removeAllViews() } catch (_: Throwable) {}
                            try { (overlay.parent as? ViewGroup)?.removeView(overlay) } catch (_: Throwable) {}
                            try { webView.stopLoading() } catch (_: Throwable) {}
                            try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
                            try { webView.destroy() } catch (_: Throwable) {}
                            finishOnce(sortResults(results.values.toList()))
                        }
                    }

                    fun scheduleFinish(delayMs: Long) {
                        finishRunnable?.let { main.removeCallbacks(it) }
                        val r = Runnable { cleanup() }
                        finishRunnable = r
                        main.postDelayed(r, delayMs)
                    }

                    cont.invokeOnCancellation {
                        cleanup()
                    }

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            if (isMediaUrl(u)) {
                                val ref = lastPageUrl
                                val reqHdrs: Map<String, String> = try {
                                    request.requestHeaders ?: emptyMap()
                                } catch (_: Throwable) {
                                    emptyMap()
                                }

                                val cookieFromReq = reqHdrs.entries
                                    .firstOrNull { it.key.equals("Cookie", true) }
                                    ?.value.orEmpty()
                                val cookieFromJar = try {
                                    CookieManager.getInstance().getCookie(u).orEmpty()
                                } catch (_: Throwable) {
                                    ""
                                }

                                val mergedCookie = when {
                                    cookieFromReq.isNotBlank() && cookieFromJar.isNotBlank() ->
                                        "$cookieFromReq; $cookieFromJar"
                                    cookieFromReq.isNotBlank() -> cookieFromReq
                                    else -> cookieFromJar
                                }

                                if (results.putIfAbsent(
                                        u,
                                        SniffResult(u, ref, mergedCookie, reqHdrs)
                                    ) == null
                                ) {
                                    ClipboardLogger.logAndCopy(
                                        "SNIFF",
                                        "hit=${u.take(180)} | hdrs=${reqHdrs.keys.joinToString(",")} | cookie.len=${mergedCookie.length}"
                                    )
                                    if (results.size >= 2) scheduleFinish(3500L)
                                    else scheduleFinish(6000L)
                                }
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            if (pageUrl != null) lastPageUrl = pageUrl
                        }

                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            if (pageUrl != null) lastPageUrl = pageUrl
                            spinner.visibility = View.GONE
                            view?.evaluateJavascript(AUTOPLAY_JS, null)
                            view?.evaluateJavascript(DPAD_JS, null)
                        }
                    }

                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            val m = msg?.message() ?: return false
                            if (m.startsWith("[HOOK]")) {
                                ClipboardLogger.logAndCopy("JS", m.take(400))
                            }
                            return true
                        }
                    }

                    val keyListener = View.OnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                webView.evaluateJavascript("__dpadMove(0,-25)", null); true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                webView.evaluateJavascript("__dpadMove(0, 25)", null); true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                webView.evaluateJavascript("__dpadMove(-25,0)", null); true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                webView.evaluateJavascript("__dpadMove( 25,0)", null); true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                webView.evaluateJavascript("__dpadClick()", null); true
                            }
                            else -> false
                        }
                    }
                    webView.setOnKeyListener(keyListener)
                    overlay.setOnKeyListener(keyListener)

                    try {
                        (activity.window.decorView as? ViewGroup)?.addView(overlay)
                    } catch (_: Throwable) {
                    }

                    val initHeaders = headers.filterKeys { !it.equals("Referer", true) }
                    val effectiveReferer = headers.entries
                        .firstOrNull { it.key.equals("Referer", true) }
                        ?.value ?: referer
                    if (effectiveReferer.isNotBlank()) {
                        webView.loadUrl(url, initHeaders + ("Referer" to effectiveReferer))
                    } else {
                        webView.loadUrl(url, initHeaders)
                    }

                    val tRunnable = Runnable { cleanup() }
                    timeoutRunnable = tRunnable
                    main.postDelayed(tRunnable, timeoutMs)
                } catch (t: Throwable) {
                    ClipboardLogger.logAndCopy("WEBVIEW", "init fail: ${t.message}")
                    finishOnce(emptyList())
                }
            }
        }
    }

    private fun sortResults(list: List<SniffResult>): List<SniffResult> =
        list.sortedWith(
            compareBy(
                {
                    when {
                        it.url.contains("/master.m3u8") -> 0
                        it.url.contains(".m3u8", ignoreCase = true) -> 1
                        it.url.contains(".mp4", ignoreCase = true) -> 2
                        else -> 3
                    }
                },
                { it.url.length }
            )
        )

    private fun isMediaUrl(u: String): Boolean {
        val l = u.lowercase()
        if (AD_DOMAINS.any { l.contains(it) }) return false
        if (SEGMENT_PATTERN.matches(l)) return false
        return l.contains(".m3u8") || l.contains(".mp4")
    }

    private const val AUTOPLAY_JS = """
    (function(){
      var sels=['button[aria-label*="lay" i]','.jw-bigplay','.vjs-big-play-button','#bigPlay','#play','video'];
      function clickAll(){
        sels.forEach(function(s){
          document.querySelectorAll(s).forEach(function(el){
            try{
              if(el.tagName==='VIDEO'){el.muted=true;el.play().catch(function(){});}
              else el.click();
            }catch(e){}
          });
        });
      }
      clickAll();setTimeout(clickAll,1500);setTimeout(clickAll,4000);setTimeout(clickAll,8000);
    })();
    """

    private const val DPAD_JS = """
    (function(){
      if(window.__dpadInit)return;window.__dpadInit=true;
      var c=document.createElement('div');c.id='__dpad_cursor';
      c.style.cssText='position:fixed;width:22px;height:22px;border-radius:50%;background:rgba(255,60,60,0.85);border:2px solid #fff;box-shadow:0 0 10px rgba(255,60,60,0.9);z-index:2147483647;pointer-events:none;display:none;transition:left .08s,top .08s;';
      document.body.appendChild(c);
      window.__dpadX=window.innerWidth/2;window.__dpadY=window.innerHeight/2;
      function render(){c.style.left=window.__dpadX+'px';c.style.top=window.__dpadY+'px';c.style.display='block';}
      window.__dpadMove=function(dx,dy){window.__dpadX=Math.max(0,Math.min(window.innerWidth-22,window.__dpadX+dx));window.__dpadY=Math.max(0,Math.min(window.innerHeight-22,window.__dpadY+dy));render();};
      window.__dpadClick=function(){
        var el=document.elementFromPoint(window.__dpadX+11,window.__dpadY+11);if(!el)return;
        if(el.focus)el.focus();if(el.click)el.click();
        ['mousedown','mouseup','click'].forEach(function(t){try{el.dispatchEvent(new MouseEvent(t,{bubbles:true}));}catch(e){}});
      };
      render();
    })();
    """
}

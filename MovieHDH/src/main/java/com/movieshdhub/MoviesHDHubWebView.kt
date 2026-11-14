package com.movieshdhub

import android.annotation.SuppressLint
import android.app.Activity
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class SniffResult(val url: String, val referer: String)

/**
 * WebView Sniffer — không phụ thuộc kotlinx.coroutines.
 *  - Golden Rule #1: chỉ dùng CommonActivity.activity
 *  - Golden Rule #4: D-pad cursor cho TV Box
 *  - Golden Rule #6: mọi thao tác UI qua Handler.post(main)
 *  - Golden Rule #7: cookie/headers do WebView tự xử lý
 *
 * Vì stream_urls trên vaplayer/vidsrc được mã hoá WASM — phải để browser
 * tự chạy WASM rồi sniff URL m3u8/mp4 từ network.
 */
object WebViewSniffer {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    /** Domain quảng cáo / tracker — bỏ qua hoàn toàn. */
    private val AD_DOMAINS = listOf(
        "doubleclick", "googlesyndication", "googleadservices",
        "google-analytics", "googletagmanager", "googletagservices",
        "amung.us", "histats", "waust.at", "adservice", "adskeeper",
        "disable-devtool", "acscdn", "rapidadexchange", "usrpubtrck",
        "suv5", "aclib", "cloudflareinsights", "beacon.min.js",
        "cast_sender", "cdn-cgi/rum", "gstatic.com/cv"
    )

    /** Default 60s — TV series cần lâu hơn movie. */
    suspend fun sniff(
        url: String,
        referer: String,
        headers: Map<String, String>,
        timeoutMs: Long = 60_000L
    ): List<SniffResult> {
        val activity = CommonActivity.activity ?: return emptyList()

        return suspendCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            val results = ConcurrentHashMap<String, SniffResult>()
            val done = AtomicBoolean(false)
            val lastPageUrl = arrayOf(url)
            val resumed = AtomicBoolean(false)

            // resume cont đúng 1 lần duy nhất
            fun finish(value: List<SniffResult>) {
                if (!resumed.compareAndSet(false, true)) return
                cont.resume(value)
            }

            main.post {
                try {
                    // ---------- Overlay + spinner ----------
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

                    // ---------- WebView ----------
                    val webView = WebView(activity).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
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

                    // ---------- Cleanup ----------
                    fun cleanup() {
                        if (!done.compareAndSet(false, true)) return
                        main.post {
                            try { overlay.removeAllViews() } catch (_: Throwable) {}
                            try { (overlay.parent as? ViewGroup)?.removeView(overlay) } catch (_: Throwable) {}
                            try { webView.stopLoading() } catch (_: Throwable) {}
                            try { webView.loadUrl("about:blank") } catch (_: Throwable) {}
                            try { webView.destroy() } catch (_: Throwable) {}
                            finish(sortResults(results.values.toList()))
                        }
                    }

                    fun scheduleFinish(delayMs: Long) {
                        main.postDelayed({ cleanup() }, delayMs)
                    }

                    // ---------- WebViewClient: network sniff + JS console ----------
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            // Log ALL request khi debug (chỉ in ra ClipboardLogger, không spam UI)
                            if (isMediaUrl(u)) {
                                val ref = lastPageUrl[0]
                                if (results.putIfAbsent(u, SniffResult(u, ref)) == null) {
                                    ClipboardLogger.logAndCopy("SNIFF", "hit=${u.take(240)}")
                                    // Có 2 hit là đủ, chờ thêm 2s cho chắc rồi đóng
                                    if (results.size >= 2) scheduleFinish(2000)
                                    else scheduleFinish(5000)
                                }
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null) lastPageUrl[0] = url
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (url != null) lastPageUrl[0] = url
                            spinner.visibility = View.GONE
                            view?.evaluateJavascript(AUTOPLAY_JS, null)
                            view?.evaluateJavascript(DPAD_JS, null)
                            // Diagnostic: hook fetch/XHR để thấy response của API
                            view?.evaluateJavascript(NETWORK_HOOK_JS, null)
                        }
                    }

                    // Nhận console.log của JS hook → ghi vào ClipboardLogger
                    webView.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            val m = msg?.message() ?: return false
                            if (m.startsWith("[HOOK]")) {
                                ClipboardLogger.logAndCopy("JS", m.take(400))
                            }
                            return true
                        }
                    }

                    // ---------- D-pad cursor (Golden Rule #4) ----------
                    webView.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP ->
                                { webView.evaluateJavascript("__dpadMove(0,-25)", null); true }
                            KeyEvent.KEYCODE_DPAD_DOWN ->
                                { webView.evaluateJavascript("__dpadMove(0, 25)", null); true }
                            KeyEvent.KEYCODE_DPAD_LEFT ->
                                { webView.evaluateJavascript("__dpadMove(-25,0)", null); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT ->
                                { webView.evaluateJavascript("__dpadMove( 25,0)", null); true }
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER ->
                                { webView.evaluateJavascript("__dpadClick()", null); true }
                            else -> false
                        }
                    }

                    // ---------- Attach + load ----------
                    try {
                        (activity.window.decorView as? ViewGroup)?.addView(overlay)
                    } catch (_: Throwable) {}
                    webView.loadUrl(url)

                    // Hard timeout
                    main.postDelayed({ cleanup() }, timeoutMs)

                } catch (t: Throwable) {
                    ClipboardLogger.logAndCopy("WEBVIEW", "init fail: ${t.message}")
                    finish(emptyList())
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------
    private fun sortResults(list: List<SniffResult>): List<SniffResult> =
        list.sortedWith(
            compareBy(
                { if (it.url.contains(".m3u8", true)) 0 else 1 },
                {
                    when {
                        it.url.contains("stellarconductornexus") -> 0
                        it.url.contains("vaplayer") -> 1
                        it.url.contains("vidsrc") -> 2
                        it.url.contains("playervdofree") -> 3
                        else -> 4
                    }
                }
            )
        )

    private fun isMediaUrl(u: String): Boolean {
        val l = u.lowercase()
        if (AD_DOMAINS.any { l.contains(it) }) return false
        // HLS segment (.ts, .m4s, .key, .aac, .vtt) — bỏ qua, chỉ lấy playlist
        val segmentPattern = Regex(""".*\.(ts|m4s|key|aac|vtt|srt|jpg|png|webp|gif)(\?.*)?$""")
        if (segmentPattern.matches(l)) return false
        // Playlist / video
        if (l.contains(".m3u8")) return true
        if (l.contains(".mp4")) return true
        if (l.contains("/manifest")) return true
        if (l.contains("/playlist") && (l.contains("m3u") || l.contains("hls"))) return true
        return false
    }

    // ==================================================================
    // JS injection
    // ==================================================================
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

    /**
     * Hook fetch + XHR để log URL và response body ra console.
     * Rất hữu ích để debug khi m3u8 không xuất hiện.
     */
    private const val NETWORK_HOOK_JS = """
    (function(){
      if(window.__netHook)return;window.__netHook=true;

      var origFetch=window.fetch;
      if(origFetch){
        window.fetch=function(){
          var u=(arguments[0]&&arguments[0].url)||arguments[0]||'';
          console.log('[HOOK] fetch '+u);
          var p=origFetch.apply(this,arguments);
          p.then(function(r){
            try{r.clone().text().then(function(t){
              console.log('[HOOK] resp '+u+' => '+t.substring(0,400));
            }).catch(function(){});}catch(e){}
          });
          return p;
        };
      }

      var origOpen=XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open=function(m,u){
        console.log('[HOOK] xhr '+m+' '+u);
        var self=this;
        this.addEventListener('load',function(){
          try{console.log('[HOOK] resp '+u+' => '+(self.responseText||'').substring(0,400));}catch(e){}
        });
        return origOpen.apply(this,arguments);
      };
    })();
    """
}

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
                    var retryCount = 0

                    // Lưu lại orientation hiện tại để khôi phục sau khi đóng WebView
                    val originalOrientation = ctx.resources.configuration.orientation

                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try {
                            // ⭐ QUAN TRỌNG: Gỡ WebView khỏi Activity trước khi destroy
                            val parent = wv.parent
                            if (parent is ViewGroup) {
                                parent.removeView(wv)
                            }
                            wv.stopLoading()
                            wv.destroy()
                            
                            // Khôi phục orientation nếu bị thay đổi
                            if (ctx.resources.configuration.orientation != originalOrientation) {
                                CommonActivity.activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            }
                        } catch (_: Exception) {}
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
                                val desc = error?.description?.toString() ?: ""
                                ClipboardLogger.logAndCopy("WebView Error", "${request.url.host}: ${error?.errorCode} $desc")
                                
                                if (retryCount < 3 && (desc.contains("QUIC", true) || error?.errorCode == -1 || error?.errorCode == -6)) {
                                    retryCount++
                                    ClipboardLogger.logAndCopy("Retry", "Lỗi mạng, thử tải lại lần $retryCount...")
                                    handler.postDelayed({
                                        wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                                    }, 1500)
                                }
                            }
                        }
                    }

                    val params = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    try {
                        CommonActivity.activity?.addContentView(wv, params)
                    } catch (_: Exception) {}

                    val clickRunnable = object : Runnable {
                        override fun run() {
                            if (finished) return
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
                                        "var v=document.querySelector('video');if(v){v.muted=true;v.play().catch(function(){});}" +
                                        "})()", null
                                    )
                                    sendTouch(wv, 540f, 960f)
                                } catch (_: Exception) {}
                            }
                            handler.postDelayed(this, 2500)
                        }
                    }
                    handler.postDelayed(clickRunnable, 2000)

                    handler.postDelayed({ 
                        if (!finished) finish(null) 
                    }, 180_000L)

                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }
    }

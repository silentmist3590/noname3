    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStream(
        pageUrl: String, referer: String, ua: String, seen: MutableList<String>
    ): SniffResult? {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx: Context? = CommonActivity.activity
                if (ctx == null) { 
                    ClipboardLogger.logAndCopy("Error", "No Context")
                    cont.resume(null); return@post 
                }

                try {
                    val wv = WebView(ctx)
                    var finished = false
                    var clickCount = 0

                    fun finish(res: SniffResult?) {
                        if (finished) return
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                        cont.resume(res)
                    }

                    val streamRegex = Regex("""\.m3u8|\.mpd|master\.txt""", RegexOption.IGNORE_CASE)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        setSupportMultipleWindows(true)
                        userAgentString = ua
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                            val popup = WebView(ctx)
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                                }
                            }
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            transport?.webView = popup
                            resultMsg?.sendToTarget()
                            handler.postDelayed({ try { popup.destroy() } catch (_: Exception) {} }, 2000)
                            return true
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            ClipboardLogger.logAndCopy("Page Started", "$url")
                            val js = """
                                (function() {
                                    try { Object.defineProperty(navigator, 'webdriver', {get: () => undefined}); } catch(e) {}
                                    try { window.chrome = { runtime: {} }; } catch(e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                            
                            startAutoClick(wv, handler) { clickCount++ }
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val u = request?.url?.toString() ?: return null
                            
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }

                            if (seen.size < 100) {
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
                            ClipboardLogger.logAndCopy("Nav", "shouldOverride: $u")
                            if (AD_DOMAINS.any { u.contains(it, ignoreCase = true) }) return true
                            if (!u.startsWith("http")) return true
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            ClipboardLogger.logAndCopy("Page Finished", "$url")
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                ClipboardLogger.logAndCopy("WebView Error", "${request.url.host}: ${error?.errorCode} ${error?.description}")
                            }
                        }
                        
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) {
                                ClipboardLogger.logAndCopy("WebView Http Error", "${request.url.host}: ${errorResponse?.statusCode}")
                            }
                        }
                    }

                    wv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
                    wv.layout(0, 0, 1080, 1920)
                    wv.translationX = -4000f
                    try {
                        CommonActivity.activity?.addContentView(wv, ViewGroup.LayoutParams(1080, 1920))
                    } catch (e: Exception) {
                        ClipboardLogger.logAndCopy("AddView Error", e.message ?: "Unknown")
                    }

                    handler.postDelayed({ 
                        if (!finished) {
                            ClipboardLogger.logAndCopy("Timeout", "Seen:\n${seen.takeLast(10).joinToString("\n")}")
                            finish(null) 
                        }
                    }, 60_000L)

                    ClipboardLogger.logAndCopy("Loading URL", pageUrl)
                    wv.loadUrl(pageUrl, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    ClipboardLogger.logAndCopy("Exception", e.message ?: "Unknown")
                    cont.resume(null)
                }
            }
        }
    }

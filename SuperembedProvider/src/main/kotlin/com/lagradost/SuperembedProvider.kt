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


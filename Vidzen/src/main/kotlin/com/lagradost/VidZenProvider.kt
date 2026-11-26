package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull // Thêm import này
import org.json.JSONObject
import java.net.URLEncoder

class VidZenProvider : MainAPI() {
    override var mainUrl = "https://vidzen.fun"
    override var name = "VidZen"
    override var lang = "en"
    override val hasMainPage = true
    override var hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    private val tmdbApi = "https://tmdb-proxy.silentmist3590.workers.dev/3"
    private val tmdbKey = "c3d997893bf55c01f39f821b53781547"
    private val cfKiller = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$tmdbApi/trending/all/week?api_key=$tmdbKey" to "Trending",
        "$tmdbApi/movie/popular?api_key=$tmdbKey" to "Popular Movies",
        "$tmdbApi/tv/popular?api_key=$tmdbKey" to "Popular TV"
    )

    private fun headers() = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    // ... (getMainPage, search, load giữ nguyên) ...

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("=== PLAY ===", "data=$data")

        val tmdbId = Regex("""/(movie|tv)/(\d+)""").find(data)?.groupValues?.get(2)
        if (tmdbId != null) {
            SubtitleFetcher.fetchSubtitles(tmdbId, null, null, subtitleCallback)
        }

        // 1. Sniff URL từ WebView
        val sniffed = VidZenSniffer.sniff(
            url = data,
            referer = "$mainUrl/",
            timeoutMs = 45_000L,
            waitForResultsMs = 5_000L
        )

        ClipboardLogger.logAndCopy("SNIFF", "Bắt được ${sniffed.size} URL")

        val candidates = sniffed
            .filter {
                it.url.contains("/api/stream/", true) ||
                it.url.contains(".m3u8", true) ||
                it.url.contains(".mp4", true)
            }
            .filterNot { it.url.contains(".js", true) }
            .distinctBy { it.url }
            .take(3)

        if (candidates.isEmpty()) {
            ClipboardLogger.logAndCopy("LINKS", "❌ Không có ứng viên nào")
            return false
        }

        var emitted = false
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        for (hit in candidates) {
            ClipboardLogger.logAndCopy("HIT", "[${hit.source}] ${hit.url.take(100)}...")

            // 2. Dùng CloudflareKiller để lấy cookie cf_clearance
            var cookie = ""
            try {
                // Thực hiện một request qua CloudflareKiller để nó giải challenge và lưu cookie
                withTimeoutOrNull(10_000L) {
                    app.get(hit.url, interceptor = cfKiller)
                }
                // Lấy cookie từ CookieManager sau khi CloudflareKiller đã chạy
                cookie = android.webkit.CookieManager.getInstance()
                    .getCookie(hit.url).orEmpty()
                ClipboardLogger.logAndCopy("CF-COOKIE", "cookie.len=${cookie.length}")
            } catch (t: Throwable) {
                ClipboardLogger.logAndCopy("CF-FAIL", "${t.message?.take(80)}")
            }

            // 3. Emit link với đầy đủ header (bao gồm cookie nếu có)
            try {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name • Auto",
                        url = hit.url,
                        type = if (hit.url.contains(".mp4", true))
                            ExtractorLinkType.VIDEO
                        else
                            ExtractorLinkType.M3U8
                    ) {
                        this.referer = hit.referer
                        val headerMap = mutableMapOf(
                            "Referer" to hit.referer,
                            "Origin" to mainUrl,
                            "User-Agent" to ua
                        )
                        if (cookie.isNotBlank()) {
                            headerMap["Cookie"] = cookie
                        }
                        this.headers = headerMap
                    }
                )
                emitted = true
                ClipboardLogger.logAndCopy("EMIT", "✓ Emit thành công (cookie.len=${cookie.length})")
                break
            } catch (t: Throwable) {
                ClipboardLogger.logAndCopy("EMIT-FAIL", "${t.message?.take(80)}")
            }
        }

        if (!emitted) ClipboardLogger.logAndCopy("LINKS", "❌ Không emit được link nào")
        return emitted
    }

    // ... (Các data class giữ nguyên) ...
}

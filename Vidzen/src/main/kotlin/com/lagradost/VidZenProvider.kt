package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeoutOrNull
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
        ClipboardLogger.logAndCopy("=== PLAY ===", "data=$data")

        val tmdbId = Regex("""/(movie|tv)/(\d+)""").find(data)?.groupValues?.get(2)
        val type = if (data.contains("/tv/")) "tv" else "movie"

        if (tmdbId != null) {
            SubtitleFetcher.fetchSubtitles(tmdbId, null, null, subtitleCallback)
        }

        // Sniff với timeout ngắn hơn
        val sniffed = VidZenSniffer.sniff(
            url = data,
            referer = "$mainUrl/",
            timeoutMs = 45_000L,      // Giảm xuống 45s
            waitForResultsMs = 5_000L // Chờ tối đa 5s sau khi có kết quả đầu tiên
        )

        ClipboardLogger.logAndCopy("SNIFF", "Bắt được ${sniffed.size} URL")

        // Chỉ lấy tối đa 5 link tốt nhất để thử
        val candidates = sniffed
            .filter { 
                it.url.contains(".m3u8", true) || 
                it.url.contains(".mp4", true) ||
                it.url.contains("/api/stream/", true) ||
                it.url.contains("v1_", true)
            }
            .filterNot { it.url.contains(".js", true) }
            .distinctBy { it.url }
            .take(5)

        var emitted = false

        for (hit in candidates) {
            ClipboardLogger.logAndCopy("HIT", "[${hit.source}] ${hit.url.take(150)}")

            // Thử M3u8Helper với timeout 8 giây
            val links = withTimeoutOrNull(8_000L) {
                try {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = hit.url,
                        referer = hit.referer,
                        headers = mapOf("Referer" to hit.referer, "Origin" to mainUrl)
                    )
                } catch (t: Throwable) {
                    ClipboardLogger.logAndCopy("M3U8-FAIL", "${t.message?.take(80)}")
                    null
                }
            }

            if (links != null && links.isNotEmpty()) {
                links.forEach { callback(it); emitted = true }
                ClipboardLogger.logAndCopy("M3U8", "✓ ${links.size} chất lượng từ ${hit.url.take(50)}...")
                break // Đã thành công, dừng lại
            } else {
                // Fallback: Phát trực tiếp link gốc (M3U8 hoặc MP4)
                ClipboardLogger.logAndCopy("FALLBACK", "Phát trực tiếp: ${hit.url.take(80)}...")
                try {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name • Auto",
                            url = hit.url,
                            type = if (hit.url.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        ) {
                            this.referer = hit.referer
                            this.headers = mapOf("Referer" to hit.referer, "Origin" to mainUrl)
                        }
                    )
                    emitted = true
                    break // Đã emit được 1 link, dừng lại
                } catch (t: Throwable) {
                    ClipboardLogger.logAndCopy("FALLBACK-FAIL", "${t.message?.take(80)}")
                }
            }
        }

        if (!emitted) {
            ClipboardLogger.logAndCopy("LINKS", "❌ Không có stream nào phát được")
        }
        return emitted
    }

    // ... (Các data class giữ nguyên) ...
}

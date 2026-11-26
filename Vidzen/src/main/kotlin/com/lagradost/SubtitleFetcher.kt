package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.SubtitleHelper // <-- ĐÃ THÊM IMPORT NÀY
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

object SubtitleFetcher {

    // ⚠️ ĐIỀN API KEY CỦA BẠN VÀO ĐÂY
    private const val OPENSUBTITLES_API_KEY = "YOUR_OPENSUBTITLES_API_KEY"
    private const val SUBDL_API_KEY = "YOUR_SUBDL_API_KEY"

    private const val LANGUAGES = "en,vi" // Tiếng Anh và Tiếng Việt

    suspend fun fetchSubtitles(
        tmdbId: String?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        ClipboardLogger.logAndCopy("SUBS", "Bắt đầu tìm phụ đề cho TMDB=$tmdbId | Title=$title")

        // 1. OpenSubtitles
        try {
            val url = if (tmdbId != null) {
                "https://api.opensubtitles.com/api/v1/subtitles?tmdb_id=$tmdbId&languages=$LANGUAGES"
            } else if (title != null) {
                val query = URLEncoder.encode(title, "UTF-8")
                "https://api.opensubtitles.com/api/v1/subtitles?query=$query&languages=$LANGUAGES"
            } else return

            val response = app.get(
                url,
                headers = mapOf(
                    "Api-Key" to OPENSUBTITLES_API_KEY,
                    "User-Agent" to "VidZen v1.0"
                )
            ).text

            val json = JSONObject(response)
            val dataArray = json.optJSONArray("data")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val subObj = dataArray.optJSONObject(i) ?: continue
                    val attributes = subObj.optJSONObject("attributes") ?: continue
                    val files = attributes.optJSONArray("files") ?: continue
                    if (files.length() == 0) continue

                    val fileId = files.optJSONObject(0)?.optInt("file_id") ?: continue
                    val language = attributes.optString("language", "en")
                    val downloadUrl = getOpenSubtitlesDownloadUrl(fileId) ?: continue

                    subtitleCallback.invoke(
                        SubtitleFile(
                            SubtitleHelper.fromTwoLettersToLanguage(language) ?: language,
                            downloadUrl
                        )
                    )
                    ClipboardLogger.logAndCopy("SUBS-OS", "✓ $language")
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("SUBS-OS", "✗ ${e.message?.take(100)}")
        }

        // 2. SubDL
        try {
            val url = if (tmdbId != null) {
                "https://api.subdl.com/api/v1/subtitles?api_key=$SUBDL_API_KEY&tmdb_id=$tmdbId&languages=$LANGUAGES"
            } else if (title != null) {
                val query = URLEncoder.encode(title, "UTF-8")
                "https://api.subdl.com/api/v1/subtitles?api_key=$SUBDL_API_KEY&film_name=$query&languages=$LANGUAGES"
            } else return

            val response = app.get(url).text
            val json = JSONObject(response)
            if (json.optBoolean("status", false)) {
                val subtitles = json.optJSONArray("subtitles")
                if (subtitles != null) {
                    for (i in 0 until subtitles.length()) {
                        val sub = subtitles.optJSONObject(i) ?: continue
                        val lang = sub.optString("language", "en")
                        val urlPath = sub.optString("url", "")
                        if (urlPath.isBlank()) continue

                        val downloadUrl = "https://dl.subdl.com$urlPath"
                        subtitleCallback.invoke(
                            SubtitleFile(
                                SubtitleHelper.fromTwoLettersToLanguage(lang) ?: lang,
                                downloadUrl
                            )
                        )
                        ClipboardLogger.logAndCopy("SUBS-SDL", "✓ $lang")
                    }
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("SUBS-SDL", "✗ ${e.message?.take(100)}")
        }
    }

    private suspend fun getOpenSubtitlesDownloadUrl(fileId: Int): String? {
        return try {
            val jsonBody = """{"file_id": $fileId}"""
            val response = app.post(
                "https://api.opensubtitles.com/api/v1/download",
                headers = mapOf(
                    "Api-Key" to OPENSUBTITLES_API_KEY,
                    "Content-Type" to "application/json"
                ),
                requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            ).text
            JSONObject(response).optString("link", null)
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("SUBS-OS-DL", "✗ ${e.message?.take(80)}")
            null
        }
    }
}

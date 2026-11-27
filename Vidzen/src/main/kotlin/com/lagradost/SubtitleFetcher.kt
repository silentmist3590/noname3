package com.lagradost

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.SubtitleHelper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

object SubtitleFetcher {

    private const val OPENSUBTITLES_API_KEY = "YOUR_OPENSUBTITLES_API_KEY"
    private const val SUBDL_API_KEY = "YOUR_SUBDL_API_KEY"
    private const val LANGUAGES = "vi,en" 

    suspend fun fetchSubtitles(
        tmdbId: String?,
        title: String?,
        year: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (tmdbId == null && title == null) return
        ClipboardLogger.logAndCopy("SUBS", "Bắt đầu tìm phụ đề cho TMDB=$tmdbId")

        // 1. OpenSubtitles (Chỉ lấy tối đa 2 Việt + 2 Anh)
        try {
            val query = if (title != null) URLEncoder.encode(title, "UTF-8") else ""
            val url = if (tmdbId != null) {
                "https://api.opensubtitles.com/api/v1/subtitles?tmdb_id=$tmdbId&languages=$LANGUAGES"
            } else {
                "https://api.opensubtitles.com/api/v1/subtitles?query=$query&languages=$LANGUAGES"
            }

            val response = app.get(url, headers = mapOf("Api-Key" to OPENSUBTITLES_API_KEY, "User-Agent" to "VidZen v1.0")).text
            val dataArray = JSONObject(response).optJSONArray("data")
            
            if (dataArray != null) {
                var viCount = 0
                var enCount = 0
                for (i in 0 until dataArray.length()) {
                    val subObj = dataArray.optJSONObject(i) ?: continue
                    val attributes = subObj.optJSONObject("attributes") ?: continue
                    val files = attributes.optJSONArray("files") ?: continue
                    if (files.length() == 0) continue

                    val lang = attributes.optString("language", "en")
                    if (lang == "vi" && viCount >= 2) continue
                    if (lang == "en" && enCount >= 2) continue

                    val fileId = files.optJSONObject(0)?.optInt("file_id") ?: continue
                    val downloadUrl = getOpenSubtitlesDownloadUrl(fileId) ?: continue

                    subtitleCallback.invoke(newSubtitleFile(SubtitleHelper.fromTwoLettersToLanguage(lang) ?: lang, downloadUrl))
                    ClipboardLogger.logAndCopy("SUBS-OS", "✓ Đã thêm phụ đề: $lang")
                    
                    if (lang == "vi") viCount++
                    if (lang == "en") enCount++
                    if (viCount >= 2 && enCount >= 2) break
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("SUBS-OS", "✗ Lỗi: ${e.message?.take(50)}")
        }

        // 2. SubDL (Tương tự, giới hạn số lượng)
        try {
            val query = if (title != null) URLEncoder.encode(title, "UTF-8") else ""
            val url = if (tmdbId != null) {
                "https://api.subdl.com/api/v1/subtitles?api_key=$SUBDL_API_KEY&tmdb_id=$tmdbId&languages=$LANGUAGES"
            } else {
                "https://api.subdl.com/api/v1/subtitles?api_key=$SUBDL_API_KEY&film_name=$query&languages=$LANGUAGES"
            }

            val response = app.get(url).text
            val json = JSONObject(response)
            
            if (json.optBoolean("status", false)) {
                val subtitles = json.optJSONArray("subtitles")
                if (subtitles != null) {
                    var viCount = 0
                    var enCount = 0
                    for (i in 0 until subtitles.length()) {
                        val sub = subtitles.optJSONObject(i) ?: continue
                        val lang = sub.optString("language", "en")
                        
                        if (lang == "vi" && viCount >= 2) continue
                        if (lang == "en" && enCount >= 2) continue

                        val urlPath = sub.optString("url", "")
                        if (urlPath.isBlank()) continue

                        val downloadUrl = "https://dl.subdl.com$urlPath"
                        subtitleCallback.invoke(newSubtitleFile(SubtitleHelper.fromTwoLettersToLanguage(lang) ?: lang, downloadUrl))
                        ClipboardLogger.logAndCopy("SUBS-SDL", "✓ Đã thêm phụ đề: $lang")
                        
                        if (lang == "vi") viCount++
                        if (lang == "en") enCount++
                        if (viCount >= 2 && enCount >= 2) break
                    }
                }
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("SUBS-SDL", "✗ Lỗi: ${e.message?.take(50)}")
        }
    }

    private suspend fun getOpenSubtitlesDownloadUrl(fileId: Int): String? {
        return try {
            val jsonBody = """{"file_id": $fileId}"""
            val response = app.post(
                "https://api.opensubtitles.com/api/v1/download",
                headers = mapOf("Api-Key" to OPENSUBTITLES_API_KEY, "Content-Type" to "application/json"),
                requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            ).text
            JSONObject(response).optString("link", null)
        } catch (e: Exception) { null }
    }
}

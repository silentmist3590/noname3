package com.lagradost

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TubiTVProvider : MainAPI() {
    override var mainUrl = "https://tubitv.com"
    override var name = "TubiTV"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val DEFAULT_SUBDL_API_KEY = ""
    }

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val tmdbHosts = listOf(
        "https://tmdb-proxy.silentmist3590.workers.dev/3",
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0

    private val tmdbKeys = listOf(
        "c3d997893bf55c01f39f821b53781547",
        "439c478a771f35c05022f9feabcca01c",
        "8476a7ab80ad76f0936744df0430e67c"
    )

    private val imdbCache = ConcurrentHashMap<String, Pair<Int, String>>()

    @Volatile
    private var cachedProxySpec: String = ""

    @Volatile
    private var cachedClient: OkHttpClient? = null

    private val blockedWebViewDomains = listOf(
        "googletagmanager.com", "google-analytics.com", "doubleclick.net",
        "googlesyndication.com", "googleadservices.com", "scorecardresearch.com",
        "quantserve.com", "facebook.net", "branch.io", "sentry.io"
    )

    override val mainPage = mainPageOf(
        "featured" to "Tubi • Featured",
        "most_popular" to "Tubi • Most Popular",
        "recently_added" to "Tubi • Recently Added",
        "action" to "Tubi • Action",
        "thrillers" to "Tubi • Thrillers",
        "sci_fi_and_fantasy" to "Tubi • Sci-Fi & Fantasy",
        "comedy" to "Tubi • Comedy",
        "horror" to "Tubi • Horror",
        "tv_comedies" to "Tubi • TV Shows"
    )

    private fun resolveAppContext(): Context? {
        TubiTVSettingsStore.getAppContext()?.let { return it }
        CommonActivity.activity?.let { return it.applicationContext ?: it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            method.invoke(null) as? Context
        }.getOrNull()
    }

    private fun getSetting(key: String): String {
        val ctx = resolveAppContext() ?: return ""
        return runCatching {
            ctx.getSharedPreferences(TubiTVSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, "")?.trim() ?: ""
        }.getOrDefault("")
    }

    private fun resolveRelayUrl(): String = getSetting(TubiTVSettingsStore.KEY_RELAY_URL)
    private fun resolveProxySpec(): String = getSetting(TubiTVSettingsStore.KEY_PROXY_ADDR)
    private fun resolveSubdlApiKey(): String =
        getSetting(TubiTVSettingsStore.KEY_SUBDL_API).ifBlank { DEFAULT_SUBDL_API_KEY.trim() }

    private fun getOkHttpClient(proxySpec: String): OkHttpClient {
        val existing = cachedClient
        if (existing != null && cachedProxySpec == proxySpec) return existing

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (proxySpec.isNotBlank()) {
            val isSocks = proxySpec.startsWith("socks", ignoreCase = true)
            val clean = proxySpec
                .removePrefix("socks5://")
                .removePrefix("socks4://")
                .removePrefix("socks://")
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
            val parts = clean.split(":")
            val host = parts.getOrNull(0)?.trim().orEmpty()
            val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: if (isSocks) 1080 else 8080
            if (host.isNotBlank() && port in 1..65535) {
                val proxyType = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
                builder.proxy(Proxy(proxyType, InetSocketAddress(host, port)))
            }
        }

        val created = builder.build()
        cachedProxySpec = proxySpec
        cachedClient = created
        return created
    }

    private fun applyRelayIfConfigured(targetUrl: String): String {
        val relay = resolveRelayUrl()
        if (relay.isBlank()) return targetUrl
        val encoded = URLEncoder.encode(targetUrl, "UTF-8")
        return when {
            relay.contains("{url}") -> relay.replace("{url}", encoded)
            relay.endsWith("=") -> "$relay$encoded"
            relay.contains("?") -> "${relay.trimEnd('&')}&url=$encoded"
            else -> "${relay.trimEnd('/')}/?url=$encoded"
        }
    }

    private fun tubiHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept" to "application/json, text/html, */*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to referer,
        "Origin" to mainUrl
    )

    private data class HttpFetchResult(val code: Int, val text: String, val isGeoBlocked: Boolean)

    private fun looksGeoBlocked(code: Int, body: String): Boolean {
        if (code == 403 || code == 451) return true
        val lower = body.lowercase()
        return lower.contains("unavailable in your location") ||
            lower.contains("not available in your country") ||
            lower.contains("tubi is currently unavailable") ||
            lower.contains("content unavailable") ||
            lower.contains("access denied")
    }

    private suspend fun httpGetTubi(
        rawUrl: String,
        referer: String = "$mainUrl/",
        timeoutSec: Long = 15L
    ): HttpFetchResult = withContext(Dispatchers.IO) {
        val relayUrl = resolveRelayUrl()
        val proxySpec = resolveProxySpec()
        val finalUrl = applyRelayIfConfigured(rawUrl)

        try {
            if (relayUrl.isBlank() && proxySpec.isBlank()) {
                val resp = app.get(finalUrl, headers = tubiHeaders(referer), timeout = timeoutSec)
                val body = resp.text
                return@withContext HttpFetchResult(resp.code, body, looksGeoBlocked(resp.code, body))
            }

            val client = getOkHttpClient(proxySpec)
            val reqBuilder = Request.Builder().url(finalUrl)
            tubiHeaders(referer).forEach { (k, v) -> reqBuilder.header(k, v) }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                return@withContext HttpFetchResult(resp.code, body, looksGeoBlocked(resp.code, body))
            }
        } catch (e: Exception) {
            ClipboardLogger.logAndCopy("HTTP Err", "${rawUrl.take(90)} -> ${e.message}")
            HttpFetchResult(0, "", false)
        }
    }

    private fun extractWindowDataJson(html: String): JSONObject? {
        val markers = listOf("window.__data=", "window.__data =", "__INITIAL_STATE__=")
        for (marker in markers) {
            val idx = html.indexOf(marker)
            if (idx < 0) continue
            val startBrace = html.indexOf('{', idx + marker.length)
            if (startBrace < 0) continue

            var depth = 0
            var inString = false
            var escape = false
            var endIdx = -1

            for (i in startBrace until html.length) {
                val c = html[i]
                if (escape) { escape = false; continue }
                if (c == '\\' && inString) { escape = true; continue }
                if (c == '"') { inString = !inString; continue }
                if (!inString) {
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) { endIdx = i + 1; break }
                    }
                }
            }

            if (endIdx > startBrace) {
                val rawJson = html.substring(startBrace, endIdx).replace("undefined", "null")
                val parsed = runCatching { JSONObject(rawJson) }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private fun slugify(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "watch" }

    private fun normalizePoster(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> "https://$raw"
        }
    }

    private fun pickPosterFromJson(obj: JSONObject): String? {
        for (key in listOf("posterarts", "thumbnails", "backgrounds", "landscape_images", "hero_images")) {
            val arr = obj.optJSONArray(key)
            if (arr != null && arr.length() > 0) {
                normalizePoster(arr.optString(0, "").trim())?.let { return it }
            }
        }
        return normalizePoster(obj.optString("poster", obj.optString("image", "")))
    }

    private fun buildItemUrl(typeCode: String, id: String, title: String, year: Int?): String {
        val kind = if (typeCode == "s" || typeCode.equals("series", true)) "s" else "v"
        return "tubi:$kind:$id:${year ?: 0}:${slugify(title)}"
    }

    private fun jsonToSearchResponse(obj: JSONObject): SearchResponse? {
        val id = obj.optString("id", "").trim()
        if (id.isBlank()) return null
        val typeCode = obj.optString("type", "v").lowercase()
        if (typeCode == "l" || typeCode == "c") return null
        val title = obj.optString("title", "").trim()
        if (title.isBlank()) return null

        val isSeries = typeCode == "s" || typeCode == "series"
        val year = obj.optInt("year", 0).takeIf { it > 1900 }
        val poster = pickPosterFromJson(obj)
        val dataUrl = buildItemUrl(typeCode, id, title, year)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, dataUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, dataUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun collectVideosFromWindowData(root: JSONObject): List<JSONObject> {
        val collected = mutableListOf<JSONObject>()
        val byId = root.optJSONObject("video")?.optJSONObject("byId") ?: return collected
        val keys = byId.keys()
        while (keys.hasNext()) {
            byId.optJSONObject(keys.next())?.let { collected.add(it) }
        }
        return collected
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val slug = request.data
        val limit = 36
        val cursor = (page - 1) * limit
        val apiUrl = "$mainUrl/oz/containers/$slug/content?parentId=&cursor=$cursor&limit=$limit&isKidsModeEnabled=false"
        val res = httpGetTubi(apiUrl)

        if (res.isGeoBlocked) {
            ClipboardLogger.logAndCopy(
                "GeoBlock",
                "TubiTV chặn IP/Vùng (HTTP ${res.code}). Bật VPN US/CA hoặc nhập Relay/Proxy trong Cài đặt."
            )
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = mutableListOf<SearchResponse>()
        val seenIds = HashSet<String>()

        if (res.code in 200..299 && res.text.trimStart().startsWith("{")) {
            runCatching {
                val json = JSONObject(res.text)
                val contents = json.optJSONObject("contents")
                if (contents != null) {
                    val keys = contents.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val item = contents.optJSONObject(key) ?: continue
                        val id = item.optString("id", key)
                        if (seenIds.add(id)) jsonToSearchResponse(item)?.let { items.add(it) }
                    }
                }
                val children = json.optJSONArray("children")
                if (children != null) {
                    for (i in 0 until children.length()) {
                        val childId = children.optString(i, "")
                        val item = contents?.optJSONObject(childId) ?: continue
                        if (seenIds.add(childId)) jsonToSearchResponse(item)?.let { items.add(it) }
                    }
                }
            }
        }

        if (items.isEmpty() && page == 1) {
            val htmlRes = httpGetTubi("$mainUrl/category/$slug")
            if (htmlRes.isGeoBlocked) {
                ClipboardLogger.logAndCopy("GeoBlock", "TubiTV chặn IP tại /category/$slug (HTTP ${htmlRes.code}).")
            } else if (htmlRes.code in 200..299) {
                extractWindowDataJson(htmlRes.text)?.let { root ->
                    for (v in collectVideosFromWindowData(root)) {
                        val id = v.optString("id", "")
                        if (id.isNotBlank() && seenIds.add(id)) jsonToSearchResponse(v)?.let { items.add(it) }
                    }
                }
            }
        }

        return newHomePageResponse(HomePageList(request.name, items, isHorizontalImages = false), hasNext = items.size >= 18)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val items = mutableListOf<SearchResponse>()
        val seenIds = HashSet<String>()

        val apiRes = httpGetTubi("$mainUrl/oz/search/$encoded")
        if (apiRes.isGeoBlocked) {
            ClipboardLogger.logAndCopy("GeoBlock", "TubiTV Search bị chặn IP (HTTP ${apiRes.code}). Bật VPN US/CA hoặc cài Proxy/Relay.")
            return emptyList()
        }

        if (apiRes.code in 200..299) {
            val trimmed = apiRes.text.trimStart()
            runCatching {
                if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id", "")
                        if (id.isNotBlank() && seenIds.add(id)) jsonToSearchResponse(obj)?.let { items.add(it) }
                    }
                } else if (trimmed.startsWith("{")) {
                    val contents = JSONObject(trimmed).optJSONObject("contents")
                    if (contents != null) {
                        val keys = contents.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = contents.optJSONObject(k) ?: continue
                            if (seenIds.add(k)) jsonToSearchResponse(v)?.let { items.add(it) }
                        }
                    }
                }
            }
        }

        if (items.isEmpty()) {
            val htmlRes = httpGetTubi("$mainUrl/search/$encoded")
            if (htmlRes.code in 200..299 && !htmlRes.isGeoBlocked) {
                extractWindowDataJson(htmlRes.text)?.let { root ->
                    for (v in collectVideosFromWindowData(root)) {
                        val id = v.optString("id", "")
                        if (id.isNotBlank() && seenIds.add(id)) jsonToSearchResponse(v)?.let { items.add(it) }
                    }
                }
            }
        }
        return items
    }

    private data class TubiRef(
        val kind: String,
        val id: String,
        val year: Int?,
        val slug: String,
        val titleHint: String = "",
        val season: Int? = null,
        val episode: Int? = null
    )

    private fun parseTubiRef(input: String): TubiRef? {
        if (input.startsWith("tubi:")) {
            val parts = input.split(":")
            return when (parts.getOrNull(1)) {
                "ep" -> TubiRef(
                    kind = "v",
                    id = parts.getOrNull(2) ?: return null,
                    titleHint = parts.getOrNull(3) ?: "",
                    season = parts.getOrNull(4)?.toIntOrNull(),
                    episode = parts.getOrNull(5)?.toIntOrNull(),
                    year = parts.getOrNull(6)?.toIntOrNull()?.takeIf { it > 1900 },
                    slug = slugify(parts.getOrNull(3) ?: "episode")
                )
                "s", "v" -> TubiRef(
                    kind = parts[1],
                    id = parts.getOrNull(2) ?: return null,
                    year = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it > 1900 },
                    slug = parts.getOrNull(4) ?: "watch"
                )
                else -> null
            }
        }
        Regex("""tubitv\.com/(movies|tv-shows|series)/(\d+)(?:/([^/?#]+))?""").find(input)?.let { m ->
            val kind = if (m.groupValues[1] == "series") "s" else "v"
            return TubiRef(kind, m.groupValues[2], null, m.groupValues[3].ifBlank { "watch" })
        }
        return null
    }

    private suspend fun fetchTubiContentJson(ref: TubiRef): JSONObject? {
        val apiRes = httpGetTubi("$mainUrl/oz/videos/${ref.id}/content")
        if (apiRes.isGeoBlocked) {
            ClipboardLogger.logAndCopy("GeoBlock", "TubiTV chặn IP khi tải chi tiết ID=${ref.id} (HTTP ${apiRes.code}).")
            return null
        }
        if (apiRes.code in 200..299 && apiRes.text.trimStart().startsWith("{")) {
            val j = runCatching { JSONObject(apiRes.text) }.getOrNull()
            if (j != null && (j.has("id") || j.has("title"))) return j
        }

        val webPath = if (ref.kind == "s") "series/${ref.id}/${ref.slug}" else "movies/${ref.id}/${ref.slug}"
        val htmlRes = httpGetTubi("$mainUrl/$webPath")
        if (htmlRes.isGeoBlocked) {
            ClipboardLogger.logAndCopy("GeoBlock", "TubiTV chặn IP tại /$webPath (HTTP ${htmlRes.code}).")
            return null
        }
        if (htmlRes.code in 200..299) {
            return extractWindowDataJson(htmlRes.text)?.optJSONObject("video")?.optJSONObject("byId")?.optJSONObject(ref.id)
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = parseTubiRef(url) ?: return null
        val json = fetchTubiContentJson(ref)
            ?: throw ErrorLoadingException("Không tải được dữ liệu từ TubiTV (kiểm tra VPN US/CA hoặc Proxy trong Cài đặt).")

        val id = json.optString("id", ref.id)
        val typeCode = json.optString("type", ref.kind).lowercase()
        val isSeries = typeCode == "s" || json.has("children")
        val title = json.optString("title", "TubiTV #$id").trim()
        val plot = json.optString("description", "")
        val year = json.optInt("year", 0).takeIf { it > 1900 } ?: ref.year
        val poster = pickPosterFromJson(json)
        val backdrop = json.optJSONArray("backgrounds")?.optString(0, "")?.let { normalizePoster(it) } ?: poster
        val durationMin = json.optInt("duration", 0).takeIf { it > 0 }?.let { it / 60 }

        val tags = mutableListOf<String>()
        val tagArr = json.optJSONArray("tags")
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                tagArr.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }

        val canonicalUrl = buildItemUrl(if (isSeries) "s" else "v", id, title, year)

        if (!isSeries) {
            val playPayload = JSONObject().apply {
                put("id", id)
                put("kind", "v")
                put("title", title)
                put("year", year ?: 0)
                put("slug", slugify(title))
            }.toString()

            return newMovieLoadResponse(title, canonicalUrl, TvType.Movie, playPayload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.duration = durationMin
                this.tags = tags
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val seasonsArr = json.optJSONArray("children") ?: JSONArray()
            for (sIdx in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.optJSONObject(sIdx) ?: continue
                val seasonNum = seasonObj.optString("id", "${sIdx + 1}").toIntOrNull() ?: (sIdx + 1)
                val epArr = seasonObj.optJSONArray("children") ?: JSONArray()
                for (eIdx in 0 until epArr.length()) {
                    val epObj = epArr.optJSONObject(eIdx) ?: continue
                    val epId = epObj.optString("id", "").trim()
                    if (epId.isBlank()) continue
                    val rawEpTitle = epObj.optString("title", "Episode ${eIdx + 1}").trim()
                    val parsedEpNum = Regex("""S\d+:E(\d+)""", RegexOption.IGNORE_CASE)
                        .find(rawEpTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: epObj.optInt("episode_number", eIdx + 1)

                    val epPayload = JSONObject().apply {
                        put("id", epId)
                        put("kind", "ep")
                        put("title", title)
                        put("epTitle", rawEpTitle)
                        put("s", seasonNum)
                        put("e", parsedEpNum)
                        put("year", year ?: 0)
                        put("slug", slugify(rawEpTitle))
                    }.toString()

                    episodes.add(
                        newEpisode(epPayload) {
                            this.name = rawEpTitle
                            this.season = seasonNum
                            this.episode = parsedEpNum
                            this.posterUrl = pickPosterFromJson(epObj)
                            this.description = epObj.optString("description", "")
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, canonicalUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    private data class SubtitleCandidate(
        val source: String,
        val langCode: String,
        val langLabel: String,
        val url: String,
        val releaseHint: String,
        val isHi: Boolean = false
    )

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]
            for (apiKey in tmdbKeys) {
                val res = runCatching {
                    app.get("$host$path${sep}api_key=$apiKey", headers = mapOf("User-Agent" to ua, "Accept" to "application/json"), timeout = 8L).text
                }.getOrNull()
                if (!res.isNullOrBlank()) {
                    val json = runCatching { JSONObject(res) }.getOrNull()
                    if (json != null && !json.has("status_code")) { tmdbPrefer = hi; return json }
                }
            }
        }
        return null
    }

    private suspend fun resolveImdbAndTmdb(title: String, year: Int?, isTv: Boolean): Pair<Int, String> {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return 0 to ""
        val cacheKey = "${if (isTv) "tv" else "movie"}:${cleanTitle.lowercase()}:${year ?: 0}"
        imdbCache[cacheKey]?.let { return it }

        val mediaType = if (isTv) "tv" else "movie"
        val q = URLEncoder.encode(cleanTitle, "UTF-8")
        val yearParam = if (year != null && year > 1900) {
            if (isTv) "&first_air_date_year=$year" else "&year=$year"
        } else ""

        val searchJson = tmdbGet("/search/$mediaType?query=$q$yearParam&page=1") ?: tmdbGet("/search/$mediaType?query=$q&page=1")
        val tmdbId = searchJson?.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
        if (tmdbId <= 0) return 0 to ""

        val imdbId = tmdbGet("/$mediaType/$tmdbId/external_ids")?.optString("imdb_id", "")?.trim().orEmpty()
        val pair = tmdbId to imdbId
        if (imdbId.startsWith("tt") || tmdbId > 0) imdbCache[cacheKey] = pair
        return pair
    }

    private fun appendSubExtFragment(url: String, formatHint: String = "srt"): String {
        val lower = url.lowercase()
        if (lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass") || lower.contains(".ssa")) return url
        val cleanExt = when (formatHint.lowercase()) {
            "vtt", "webvtt" -> "vtt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            else -> "srt"
        }
        return "$url#file.$cleanExt"
    }

    private object SubdlLoopbackProxy {
        data class Entry(val zipUrl: String, val isTv: Boolean, val season: Int?, val episode: Int?, val ua: String)
        private var serverSocket: ServerSocket? = null
        private var port: Int = -1
        private val seq = AtomicInteger(0)
        private val entries = ConcurrentHashMap<String, Entry>()
        private val memCache = ConcurrentHashMap<String, Pair<String, ByteArray>>()

        @Synchronized
        fun ensureStarted(): Int {
            if (port > 0 && serverSocket?.isClosed == false) return port
            return runCatching {
                val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                Thread {
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            Thread { handleClient(client) }.apply { isDaemon = true }.start()
                        } catch (_: Exception) {
                            if (ss.isClosed) break
                        }
                    }
                }.apply { isDaemon = true }.start()
                port
            }.getOrDefault(-1)
        }

        fun register(zipUrl: String, isTv: Boolean, season: Int?, episode: Int?, ua: String): String? {
            val p = ensureStarted()
            if (p <= 0) return null
            if (entries.size > 200) entries.clear()
            val token = seq.incrementAndGet().toString()
            entries[token] = Entry(zipUrl, isTv, season, episode, ua)
            return "http://127.0.0.1:$p/subdl/$token.srt"
        }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val token = Regex("""/subdl/(\d+)\.srt""").find(parts.getOrNull(1) ?: "")?.groupValues?.getOrNull(1)
                val entry = token?.let { entries[it] }
                val out = socket.getOutputStream()
                if (token == null || entry == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val resolved = memCache[token] ?: fetchAndUnzip(entry)?.also {
                    if (memCache.size >= 12) memCache.clear()
                    memCache[token] = it
                }
                if (resolved == null) {
                    out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                val (mime, bytes) = resolved
                val header = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(bytes)
                out.flush()
            } catch (_: Exception) {
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }

        private fun fetchAndUnzip(entry: Entry): Pair<String, ByteArray>? {
            val zipBytes = downloadBlocking(entry.zipUrl, mapOf("User-Agent" to entry.ua, "Referer" to "https://subdl.com/", "Accept" to "*/*")) ?: return null
            if (zipBytes.isEmpty()) return null
            if (zipBytes.size >= 4 && zipBytes[0] == 0x50.toByte() && zipBytes[1] == 0x4B.toByte()) {
                val validExts = listOf(".srt", ".vtt", ".ass", ".ssa")
                val collected = mutableListOf<Pair<String, ByteArray>>()
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                    var ze = zis.nextEntry
                    while (ze != null) {
                        val name = ze.name ?: ""
                        val lower = name.lowercase()
                        if (!ze.isDirectory && !lower.contains("__macosx/") && validExts.any { lower.endsWith(it) }) {
                            val data = zis.readBytes()
                            if (data.isNotEmpty()) collected.add(name to data)
                        }
                        zis.closeEntry()
                        ze = zis.nextEntry
                    }
                }
                if (collected.isEmpty()) return null
                val best = collected.sortedWith(
                    compareBy<Pair<String, ByteArray>>(
                        { if (entry.isTv && !matchesEpisodeHint(it.first, entry.season, entry.episode)) 1 else 0 },
                        { val l = it.first.lowercase(); when { l.endsWith(".srt") -> 0; l.endsWith(".vtt") -> 1; else -> 2 } }
                    )
                ).first()
                val mime = if (best.first.lowercase().endsWith(".vtt")) "text/vtt; charset=utf-8" else "application/x-subrip; charset=utf-8"
                return mime to best.second
            }
            val head = zipBytes.take(150).toByteArray().toString(Charsets.UTF_8).trimStart()
            if (head.startsWith("<!doctype", true) || head.startsWith("<html", true)) return null
            val mime = if (head.contains("WEBVTT", true)) "text/vtt; charset=utf-8" else "application/x-subrip; charset=utf-8"
            return mime to zipBytes
        }

        private fun matchesEpisodeHint(fileName: String, season: Int?, episode: Int?): Boolean {
            if (season == null || episode == null) return true
            val lower = fileName.lowercase()
            val s2 = season.toString().padStart(2, '0')
            val e2 = episode.toString().padStart(2, '0')
            return listOf("s${s2}e${e2}", "s${season}e${e2}", "s${s2}e${episode}", "${season}x${e2}", "${s2}x${e2}", "episode.${e2}", "ep${e2}").any { lower.contains(it) }
        }

        private fun downloadBlocking(urlStr: String, headers: Map<String, String>): ByteArray? {
            var current = urlStr
            for (hop in 0..3) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(current).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false; connectTimeout = 12_000; readTimeout = 12_000; requestMethod = "GET"
                        headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")?.trim()
                        if (!loc.isNullOrBlank()) { current = if (loc.startsWith("http", true)) loc else URL(URL(current), loc).toString(); continue }
                    }
                    if (code in 200..299) return conn.inputStream.use { it.readBytes() }
                    return null
                } catch (_: Exception) { return null } finally { conn?.disconnect() }
            }
            return null
        }
    }

    private suspend fun queryOpenSubtitlesRest(imdbId: String, isTv: Boolean, season: Int?, episode: Int?): List<SubtitleCandidate> {
        if (!imdbId.startsWith("tt")) return emptyList()
        val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId.removePrefix("tt") }
        val headers = mapOf("User-Agent" to "trailers.to-UA", "X-User-Agent" to "trailers.to-UA")
        return listOf("vie" to "vi", "eng" to "en").amap { (subLang, code) ->
            val out = mutableListOf<SubtitleCandidate>()
            runCatching {
                val url = if (isTv && season != null && episode != null) {
                    "https://rest.opensubtitles.org/search/episode-$episode/imdbid-$cleanId/season-$season/sublanguageid-$subLang"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$subLang"
                }
                val res = app.get(url, headers = headers, timeout = 8L).text.trim()
                if (!res.startsWith("[")) return@runCatching
                val arr = JSONArray(res)
                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedWith(compareBy<JSONObject>({ if (it.optString("SubHearingImpaired", "0") == "1") 1 else 0 }, { if (it.optString("SubFormat", "").lowercase() in listOf("srt", "vtt")) 0 else 1 }))
                for (item in items.take(4)) {
                    val rawLink = item.optString("SubDownloadLink", "").trim()
                    if (!rawLink.startsWith("http")) continue
                    val directLink = rawLink.replace("/filead/", "/subencoding-utf8/filead/").replace("/file/", "/subencoding-utf8/file/").removeSuffix(".gz")
                    out.add(SubtitleCandidate("OpenSubtitles", code, if (code == "vi") "Vietnamese" else "English", appendSubExtFragment(directLink, item.optString("SubFormat", "srt")), item.optString("MovieReleaseName", "").trim(), item.optString("SubHearingImpaired", "0") == "1"))
                }
            }
            out
        }.flatten()
    }

    private suspend fun queryOpenSubtitlesStremio(imdbId: String, isTv: Boolean, season: Int?, episode: Int?): List<SubtitleCandidate> {
        if (!imdbId.startsWith("tt")) return emptyList()
        val url = if (isTv) "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:${season ?: 1}:${episode ?: 1}.json" else "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        val out = mutableListOf<SubtitleCandidate>()
        runCatching {
            val text = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 8L).text.trim()
            if (!text.startsWith("{")) return@runCatching
            val arr = JSONObject(text).optJSONArray("subtitles") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val subUrl = obj.optString("url", "").trim()
                if (!subUrl.startsWith("http")) continue
                val l = obj.optString("lang", "").lowercase()
                val rel = obj.optString("releaseGroup", obj.optString("movieReleaseName", "")).trim()
                when {
                    l == "vie" || l == "vi" -> out.add(SubtitleCandidate("OpenSubtitles-V3", "vi", "Vietnamese", appendSubExtFragment(subUrl, "srt"), rel))
                    l == "eng" || l == "en" -> out.add(SubtitleCandidate("OpenSubtitles-V3", "en", "English", appendSubExtFragment(subUrl, "srt"), rel))
                }
            }
        }
        return out
    }

    private suspend fun querySubdl(tmdbId: Int, imdbId: String, title: String, year: Int?, isTv: Boolean, season: Int?, episode: Int?, apiKey: String): List<SubtitleCandidate> {
        if (apiKey.isBlank()) return emptyList()
        val encKey = URLEncoder.encode(apiKey, "UTF-8")
        val idParam = when {
            imdbId.startsWith("tt") -> "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}"
            tmdbId > 0 -> "tmdb_id=$tmdbId"
            else -> "film_name=${URLEncoder.encode(title, "UTF-8")}" + if (year != null) "&year=$year" else ""
        }
        val queryUrl = if (isTv) {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encKey&$idParam&type=tv&season_number=${season ?: 1}&episode_number=${episode ?: 1}&languages=VI,EN"
        } else {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encKey&$idParam&type=movie&languages=VI,EN"
        }
        val out = mutableListOf<SubtitleCandidate>()
        runCatching {
            val resp = app.get(queryUrl, headers = mapOf("User-Agent" to ua, "Accept" to "application/json"), timeout = 10L)
            if (resp.code !in 200..299) return@runCatching
            val json = JSONObject(resp.text)
            if (!json.optBoolean("status", false)) return@runCatching
            val subs = json.optJSONArray("subtitles") ?: return@runCatching
            val viItems = mutableListOf<JSONObject>()
            val enItems = mutableListOf<JSONObject>()
            for (i in 0 until subs.length()) {
                val obj = subs.optJSONObject(i) ?: continue
                val l = obj.optString("language", "").lowercase()
                if (l.contains("vietnam") || l == "vi" || l == "vie") viItems.add(obj)
                else if (l.contains("english") || l == "en" || l == "eng") enItems.add(obj)
            }
            for ((langInfo, list) in listOf(("vi" to "Vietnamese") to viItems.sortedBy { if (it.optBoolean("hi", false)) 1 else 0 }.take(3), ("en" to "English") to enItems.sortedBy { if (it.optBoolean("hi", false)) 1 else 0 }.take(2))) {
                for (item in list) {
                    val rawPath = item.optString("url", "").trim()
                    if (rawPath.isBlank()) continue
                    val fullZipUrl = when {
                        rawPath.startsWith("http", true) -> rawPath
                        rawPath.startsWith("/") -> "https://dl.subdl.com$rawPath"
                        else -> "https://dl.subdl.com/$rawPath"
                    }
                    val proxyUrl = SubdlLoopbackProxy.register(fullZipUrl, isTv, season, episode, ua) ?: continue
                    out.add(SubtitleCandidate("SubDL", langInfo.first, langInfo.second, proxyUrl, item.optString("release_name", item.optString("name", "")).trim(), item.optBoolean("hi", false)))
                }
            }
        }
        return out
    }

    private suspend fun autoLoadExactSubtitles(title: String, year: Int?, isTv: Boolean, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit): Int {
        val (tmdbId, imdbId) = resolveImdbAndTmdb(title, year, isTv)
        val subdlKey = resolveSubdlApiKey()
        val rawCandidates = listOf(0, 1, 2).amap { idx ->
            when (idx) {
                0 -> queryOpenSubtitlesRest(imdbId, isTv, season, episode)
                1 -> queryOpenSubtitlesStremio(imdbId, isTv, season, episode)
                else -> querySubdl(tmdbId, imdbId, title, year, isTv, season, episode, subdlKey)
            }
        }.flatten()

        val seenUrls = HashSet<String>()
        val deduped = rawCandidates.filter { seenUrls.add(it.url) }

        val viAll = deduped.filter { it.langCode == "vi" }.sortedBy { if (it.isHi) 1 else 0 }
        val selectedVi = mutableListOf<SubtitleCandidate>()
        viAll.firstOrNull { it.source == "SubDL" }?.let { selectedVi.add(it) }
        viAll.firstOrNull { it.source.startsWith("OpenSubtitles") }?.let { selectedVi.add(it) }
        for (cand in viAll) {
            if (selectedVi.size >= 2) break
            if (selectedVi.none { it.url == cand.url }) selectedVi.add(cand)
        }

        val selectedEn = deduped.filter { it.langCode == "en" }.sortedBy { if (it.isHi) 1 else 0 }.take(1)

        var count = 0
        for ((idx, cand) in selectedVi.withIndex()) {
            val hint = if (cand.releaseHint.isNotBlank()) " (${cand.releaseHint.take(24)})" else ""
            subtitleCallback.invoke(SubtitleFile("Tiếng Việt #${idx + 1} • ${cand.source}$hint", cand.url))
            count++
        }
        for (cand in selectedEn) {
            val hint = if (cand.releaseHint.isNotBlank()) " (${cand.releaseHint.take(24)})" else ""
            subtitleCallback.invoke(SubtitleFile("English #1 • ${cand.source}$hint", cand.url))
            count++
        }
        ClipboardLogger.logAndCopy("Subtitles", "Đã tự động nạp ${selectedVi.size}/2 VI + ${selectedEn.size}/1 EN")
        return count
    }

    private fun qualityFromHeight(height: Int): Int = when {
        height >= 2160 -> Qualities.P2160.value
        height >= 1440 -> Qualities.P1440.value
        height >= 1080 -> Qualities.P1080.value
        height >= 720 -> Qualities.P720.value
        height >= 480 -> Qualities.P480.value
        height >= 360 -> Qualities.P360.value
        height >= 240 -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }

    private fun isDrmProtectedResource(resObj: JSONObject): Boolean {
        val type = resObj.optString("type", "").lowercase()
        val licenseServer = resObj.optString("license_server", "").trim()
        val drmObj = resObj.optJSONObject("drm")
        return type.contains("widevine") || type.contains("playready") || type.contains("fairplay") || type.contains("drm") || licenseServer.isNotBlank() || (drmObj != null && drmObj.length() > 0)
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String =
        if (relative.startsWith("http", true)) relative else runCatching { URL(URL(baseUrl), relative).toString() }.getOrDefault(relative)

    private data class HlsVariant(val height: Int, val bandwidth: Long, val label: String, val url: String)

    private suspend fun parseHlsVariants(masterUrl: String): List<HlsVariant> {
        val res = httpGetTubi(masterUrl, referer = "$mainUrl/", timeoutSec = 10L)
        if (res.code !in 200..299 || !res.text.contains("#EXT-X-STREAM-INF")) return emptyList()
        val variants = mutableListOf<HlsVariant>()
        val lines = res.text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val nextLine = lines.drop(i + 1).firstOrNull { it.trim().isNotEmpty() && !it.trim().startsWith("#") }?.trim() ?: continue
            val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val bandwidth = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val qLabel = when { height >= 2160 -> "4K 2160p"; height > 0 -> "${height}p"; bandwidth > 0 -> "${bandwidth / 1000}kbps"; else -> "Variant" }
            variants.add(HlsVariant(height, bandwidth, qLabel, resolveRelativeUrl(masterUrl, nextLine)))
        }
        return variants.sortedWith(compareByDescending<HlsVariant> { it.height }.thenByDescending { it.bandwidth })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffTubiWebView(pageUrl: String): List<Pair<String, ExtractorLinkType>> {
        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val ctx = resolveAppContext()
                if (ctx == null) { cont.resume(emptyList()); return@post }
                try {
                    val wv = WebView(ctx)
                    val finished = AtomicBoolean(false)
                    val foundStreams = Collections.synchronizedList(mutableListOf<Pair<String, ExtractorLinkType>>())
                    val seen = Collections.synchronizedSet(HashSet<String>())
                    fun finish() {
                        if (!finished.compareAndSet(false, true)) return
                        handler.removeCallbacksAndMessages(null)
                        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) { }
                        cont.resume(foundStreams.toList())
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false; userAgentString = ua; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
                    wv.webChromeClient = WebChromeClient()
                    val streamRegex = Regex("""\.(m3u8|mp4)(\?|$|#)""", RegexOption.IGNORE_CASE)
                    val ignoreRegex = Regex("""\.(ts|m4s|js|css|png|jpg|webp|svg|woff2?)(\?|$)""", RegexOption.IGNORE_CASE)
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (finished.get()) return null
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http", true)) return null
                            if (blockedWebViewDomains.any { u.contains(it, true) }) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            if (streamRegex.containsMatchIn(u) && !ignoreRegex.containsMatchIn(u) && seen.add(u)) {
                                foundStreams.add(u to if (u.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8)
                                handler.postDelayed({ finish() }, 2500)
                            }
                            return null
                        }
                    }
                    handler.postDelayed({ finish() }, 22_000L)
                    wv.loadUrl(pageUrl, mapOf("Referer" to "$mainUrl/"))
                } catch (_: Exception) { cont.resume(emptyList()) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("Start", "TubiTV loadLinks: $data")

        val meta = runCatching { JSONObject(data) }.getOrNull()
        val ref = if (meta != null) {
            TubiRef(meta.optString("kind", "v"), meta.optString("id", ""), meta.optInt("year", 0).takeIf { it > 1900 }, meta.optString("slug", "watch"), meta.optString("title", ""), meta.optInt("s", 0).takeIf { it > 0 }, meta.optInt("e", 0).takeIf { it > 0 })
        } else parseTubiRef(data)

        if (ref == null || ref.id.isBlank()) {
            ClipboardLogger.logAndCopy("Error", "Dữ liệu không hợp lệ: $data")
            return false
        }

        val isTv = ref.kind == "ep" || ref.kind == "s" || (ref.season != null && ref.episode != null)
        val contentJson = fetchTubiContentJson(ref)
        val resolvedTitle = ref.titleHint.ifBlank { contentJson?.optString("title", "") ?: "" }
        val resolvedYear = ref.year ?: contentJson?.optInt("year", 0)?.takeIf { it > 1900 }

        var totalSubs = autoLoadExactSubtitles(resolvedTitle, resolvedYear, isTv, ref.season, ref.episode, subtitleCallback)

        val tubiSubs = contentJson?.optJSONArray("subtitles")
        if (tubiSubs != null) {
            for (i in 0 until tubiSubs.length()) {
                val sub = tubiSubs.optJSONObject(i) ?: continue
                val subUrl = sub.optString("url", "").trim()
                val subLang = sub.optString("lang", "English").trim()
                if (subUrl.startsWith("http")) {
                    subtitleCallback.invoke(SubtitleFile("Tubi Native • $subLang", appendSubExtFragment(subUrl, "vtt")))
                    totalSubs++
                }
            }
        }

        val seenUrls = HashSet<String>()
        var totalLinks = 0
        var drmSkipped = 0
        val streamHeaders = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl)

        val resources = contentJson?.optJSONArray("video_resources") ?: JSONArray()
        for (i in 0 until resources.length()) {
            val resObj = resources.optJSONObject(i) ?: continue
            val resType = resObj.optString("type", "hls").ifBlank { "hls" }
            if (isDrmProtectedResource(resObj)) {
                drmSkipped++
                ClipboardLogger.logAndCopy("Skip DRM", "Bỏ qua nguồn DRM '$resType'")
                continue
            }
            val manifestUrl = resObj.optJSONObject("manifest")?.optString("url", "")?.ifBlank { resObj.optString("url", "") } ?: resObj.optString("url", "")
            if (!manifestUrl.startsWith("http", true)) continue

            val serverLabel = "Server ${i + 1} (${resType.uppercase()})"
            val linkType = if (resType.contains("mp4", true) || manifestUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

            if (seenUrls.add(manifestUrl)) {
                callback.invoke(newExtractorLink(this.name, "TubiTV • $serverLabel • Auto", manifestUrl, linkType) {
                    this.referer = "$mainUrl/"; this.quality = Qualities.P1080.value; this.headers = streamHeaders
                })
                totalLinks++
            }
            if (linkType == ExtractorLinkType.M3U8) {
                for (v in parseHlsVariants(manifestUrl)) {
                    if (!seenUrls.add(v.url)) continue
                    callback.invoke(newExtractorLink(this.name, "TubiTV • $serverLabel • ${v.label}", v.url, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"; this.quality = qualityFromHeight(v.height); this.headers = streamHeaders
                    })
                    totalLinks++
                }
            }
        }

        if (totalLinks == 0 && drmSkipped == 0) {
            for ((idx, pair) in sniffTubiWebView("$mainUrl/movies/${ref.id}/${ref.slug}").withIndex()) {
                val (sUrl, sType) = pair
                if (!seenUrls.add(sUrl)) continue
                callback.invoke(newExtractorLink(this.name, "TubiTV • WebView #${idx + 1} Auto", sUrl, sType) {
                    this.referer = "$mainUrl/"; this.quality = Qualities.P1080.value; this.headers = streamHeaders
                })
                totalLinks++
            }
        }

        ClipboardLogger.logAndCopy(if (totalLinks > 0) "Done" else "Failed", "Kết quả: $totalLinks luồng phát (DRM skipped: $drmSkipped) & $totalSubs phụ đề.")
        return totalLinks > 0
    }
}

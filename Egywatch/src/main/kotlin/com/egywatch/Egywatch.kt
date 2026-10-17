package com.egywatch

import android.util.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Egywatch (إيجي واتش) provider.
 *
 * The Android app (`com.linkletter.app`) is an EasyPlex fork backed by a Laravel JSON API.
 * Every response is a plain JSON object once the mobile auth headers are omitted, e.g.
 *   https://gateway.egywatch.live/egywatchapp/public/api/movies/latestadded/{code}
 *
 * Content is nested as: movie / series / anime -> seasons -> episodes -> videos. The actual
 * playback files are exposed either as direct mp4/m3u8 links or as third-party embeds that the
 * original app resolves through its own server-side scraper at `mawdhou3.com`
 * (host -> `scrapefinal/<name>.php`). We replicate both paths in [EgywatchHostExtractor] and fall
 * back to CloudStream's built-in extractors.
 */
class Egywatch : MainAPI() {
    companion object {
        private const val TAG = "Egywatch"

        /** Public API key baked into the app, appended as the trailing `{code}` path segment. */
        const val CODE = "p2lbgWkFrykA4QyUmpHihzmc5BNzIABq"

        /** Headers the app sends on every API call (auth headers intentionally omitted). */
        const val APP_UA = "EasyPlex (Android 14; M2006C3MG; Redmi angelica; en)"
        const val PACKAGE_NAME = "com.linkletter.app"
        private val API_HEADERS = mapOf(
            "User-Agent" to APP_UA,
            "packagename" to PACKAGE_NAME,
            "accept" to "application/json"
        )

        /** Primary gateway first, then the backup mirror advertised in `api_urls.json`. */
        private val BASES = listOf(
            "https://gateway.egywatch.live/egywatchapp/public/api/",
            "https://rn62mwg.com/egywatchapp/public/api/"
        )

        private val DIRECT_FILE = Regex("(?i)^https?://.+?\\.(mp4|m3u8|mkv|webm)(\\?.*)?$")

        /** Leading markers of the pseudo-URLs we use as carrier `data`. */
        private val TOKENS = listOf("movie:", "live:", "ep:")

        // Every video is resolved in parallel; a dead or hung embed is dropped after this budget
        // instead of stalling the whole player load.
        private const val SOURCE_TIMEOUT_MS = 12_000L
    }

    override var name = "Egywatch"
    override var mainUrl = "https://gateway.egywatch.live"
    override var lang = "ar"

    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.Live
    )

    override val mainPage = mainPageOf(
        "movies/latestadded" to "أحدث الأفلام",
        "movies/byrating" to "أفلام الأعلى تقييماً",
        "movies/byviews" to "أفلام الأكثر مشاهدة",
        "series/latestadded" to "أحدث المسلسلات",
        "series/byrating" to "مسلسلات الأعلى تقييماً",
        "series/byviews" to "مسلسلات الأكثر مشاهدة",
        "animes/byrating" to "أنمي الأعلى تقييماً",
        "categories/streaming/show/streaming" to "بث مباشر"
    )

    // ------------------------------------------------------------------ HTTP

    private val probeClient by lazy {
        app.baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun getJson(path: String): JSONObject? {
        var last: Throwable? = null
        // The gateway sporadically answers 404 with a bare `{"message":""}` (bad node);
        // retry a couple of times across both mirrors before giving up.
        repeat(3) { attempt ->
            for (base in BASES) {
                try {
                    val text = app.get(base + path, headers = API_HEADERS).text
                    val json = JSONObject(text)
                    if (json.length() == 1 && json.has("message")) {
                        last = IllegalStateException("empty message from $base")
                        continue
                    }
                    return json
                } catch (e: Throwable) {
                    last = e
                }
            }
            if (attempt < 2) delay(400)
        }
        Log.i(TAG, "getJson failed $path -> $last")
        return null
    }

    private fun toList(json: JSONObject, vararg keys: String): List<SearchResponse> {
        for (key in keys) {
            val arr = json.optJSONArray(key) ?: continue
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { toSearch(arr.getJSONObject(i)) }.getOrNull()
            }
        }
        return emptyList()
    }

    /**
     * Some entries in the video list look like real files but are empty placeholders on the CDN
     * (they answer `416 Range Not Satisfiable` / size 0). Probe with a minimal range request and
     * only treat a definitive 416/404 as dead, so uncertain responses never drop a good source.
     */
    private suspend fun isDeadDirect(url: String, userAgent: String?): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent ?: APP_UA)
                .header("Range", "bytes=0-1")
                .get()
                .build()
            withContext(Dispatchers.IO) { probeClient.newCall(request).execute() }.use { response ->
                response.code == 416 || response.code == 404
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ main

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = getJson("${request.data}/$CODE?page=$page")
            ?: return newHomePageResponse(request.name, emptyList())
        val items = toList(json, "data", "anime", "suggested")
        val current = json.optInt("current_page", 1)
        val last = json.optInt("last_page", current)
        return newHomePageResponse(request.name, items, current < last)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val json = getJson("search/$q/$CODE") ?: return emptyList()
        return toList(json, "search", "data")
    }

    private fun toSearch(o: JSONObject): SearchResponse? {
        val id = o.optInt("id", -1)
        if (id < 0) return null
        val type = o.optString("type").lowercase()
        val title = o.optString("name").ifBlank { o.optString("title") }
            .ifBlank { o.optString("original_name") }
        if (title.isBlank()) return null
        val poster = o.optString("poster_path").ifBlank { o.optString("backdrop_path") }.ifBlank { null }
        val tvType = when {
            type.contains("anime") -> TvType.Anime
            type.contains("serie") || type.contains("series") -> TvType.TvSeries
            type.contains("stream") || type.contains("live") -> TvType.Live
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, detailUrl(tvType, id), tvType) {
            this.posterUrl = poster
        }
    }

    private fun detailUrl(tvType: TvType, id: Int): String {
        val key = when (tvType) {
            TvType.TvSeries -> "serie"
            TvType.Anime, TvType.AnimeMovie -> "anime"
            TvType.Live -> "live"
            else -> "movie"
        }
        return "$mainUrl/detail/$key/$id"
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse {
        val parts = url.substringAfter("/detail/", url).split("/")
        val key = parts.getOrNull(0).orEmpty()
        val id = parts.getOrNull(1)?.toIntOrNull()
        if (id == null) return emptyLoad(url)
        return when (key) {
            "serie" -> loadSeries(url, id, "series/show", "series/season", TvType.TvSeries)
            "anime" -> loadSeries(url, id, "animes/show", "animes/season", TvType.Anime)
            "live" -> loadLive(url, id)
            else -> loadMovie(url, id)
        }
    }

    private suspend fun emptyLoad(url: String): LoadResponse =
        newMovieLoadResponse("Egywatch", url, TvType.Movie, "") { }

    private suspend fun loadMovie(url: String, id: Int): LoadResponse {
        val json = getJson("media/detail/$id/$CODE") ?: return emptyLoad(url)
        val title = json.optString("title").ifBlank { json.optString("original_name") }
        return newMovieLoadResponse(title, url, TvType.Movie, "movie:$id") {
            this.posterUrl = json.optString("poster_path").ifBlank { json.optString("backdrop_path") }.ifBlank { null }
            this.plot = json.optString("overview").ifBlank { null }
            this.year = json.optString("release_date").take(4).toIntOrNull()
            this.tags = genresOf(json)
            this.duration = json.optString("runtime").toIntOrNull()
            this.actors = castOf(json)
        }
    }

    private suspend fun loadLive(url: String, id: Int): LoadResponse {
        val json = getJson("stream/show/$id/$CODE") ?: return emptyLoad(url)
        val title = json.optString("name").ifBlank { json.optString("overview") }
        return newMovieLoadResponse(title, url, TvType.Live, "live:$id") {
            this.posterUrl = json.optString("poster_path").ifBlank { null }
            this.plot = json.optString("overview").ifBlank { null }
            this.tags = genresOf(json)
            this.actors = castOf(json)
        }
    }

    private suspend fun loadSeries(
        url: String,
        id: Int,
        showPath: String,
        seasonPath: String,
        tvType: TvType
    ): LoadResponse {
        val json = getJson("$showPath/$id/$CODE") ?: return emptyLoad(url)
        val title = json.optString("name").ifBlank { json.optString("original_name") }
        val poster = json.optString("poster_path").ifBlank { json.optString("backdrop_path") }.ifBlank { null }
        val episodes = mutableListOf<Episode>()
        val seasons = json.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until seasons.length()) {
            val season = seasons.getJSONObject(i)
            val seasonId = season.optInt("id", -1)
            if (seasonId < 0) continue
            val seasonNumber = season.optInt("season_number", i + 1)
            val sJson = getJson("$seasonPath/$seasonId/$CODE") ?: continue
            val eps = sJson.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val ep = eps.getJSONObject(j)
                val epNumber = ep.optInt("episode_number", j + 1)
                val epName = ep.optString("name").ifBlank { "الحلقة $epNumber" }
                val still = ep.optString("still_path").ifBlank { poster }
                episodes += newEpisode("ep:$seasonId:$epNumber") {
                    this.name = epName
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = still
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, tvType, episodes) {
            this.posterUrl = poster
            this.plot = json.optString("overview").ifBlank { null }
            this.year = json.optString("first_air_date").take(4).toIntOrNull()
            this.tags = genresOf(json)
            this.actors = castOf(json)
        }
    }

    private fun genresOf(json: JSONObject): List<String> {
        val arr = json.optJSONArray("genreslist") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
    }

    /** `casterslist` -> [{name, profile_path}] as CloudStream actor entries. */
    private fun castOf(json: JSONObject): List<ActorData> {
        val arr = json.optJSONArray("casterslist") ?: return emptyList()
        val result = mutableListOf<ActorData>()
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val name = o.optString("name").ifBlank { o.optString("original_name") }.trim()
            if (name.isBlank()) continue
            val image = o.optString("profile_path").ifBlank { null }
            result += ActorData(Actor(name, image), roleString = null)
        }
        return result
    }

    // ----------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videos = videosFor(data)
        if (videos.isEmpty()) {
            Log.i(TAG, "no videos for $data")
            return false
        }
        // Resolve every video concurrently so a dead or Cloudflare-blocked embed fails on its own
        // instead of holding up the player. Each gets its own [SOURCE_TIMEOUT_MS] budget. The
        // framework callbacks are serialised because they are not documented as thread-safe.
        val emitted = HashSet<String>()
        val lock = Any()
        val guarded: (ExtractorLink) -> Unit = { link ->
            val key = if (link.url.contains(".m3u8")) link.url.substringBefore('?') else link.url
            synchronized(lock) { if (emitted.add(key)) callback(link) }
        }
        val guardedSub: (SubtitleFile) -> Unit = { sub -> synchronized(lock) { subtitleCallback(sub) } }
        val results = supervisorScope {
            videos.map { video ->
                async(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            resolveVideo(video, guardedSub, guarded)
                        } ?: false
                    } catch (e: Throwable) {
                        Log.i(TAG, "resolveVideo FAILED -> $e")
                        false
                    }
                }
            }.awaitAll()
        }
        return results.any { it }
    }

    /**
     * CloudStream runs the carrier `data` through [MainAPI.fixUrl] before calling [loadLinks], so a
     * pseudo-URL like `ep:25165:1` arrives as `https://gateway.egywatch.live/ep:25165:1`. Strip
     * everything before our own token so the id parsing below keeps working.
     */
    private fun carrierData(data: String): String {
        for (token in TOKENS) {
            val i = data.indexOf(token)
            if (i >= 0) return data.substring(i)
        }
        return data
    }

    private suspend fun videosFor(rawData: String): List<JSONObject> {
        val data = carrierData(rawData)
        val parts = data.split(":")
        return when (parts.firstOrNull()) {
            "movie" -> videosFrom("media/detail/${parts.getOrNull(1)}/$CODE")
            "live" -> videosFrom("stream/show/${parts.getOrNull(1)}/$CODE")
            "ep" -> {
                val seasonId = parts.getOrNull(1) ?: return emptyList()
                val epNumber = parts.getOrNull(2)?.toIntOrNull() ?: return emptyList()
                episodeVideos(seasonId, epNumber)
            }
            else -> emptyList()
        }
    }

    private suspend fun videosFrom(path: String): List<JSONObject> {
        val json = getJson(path) ?: return emptyList()
        val arr = json.optJSONArray("videos") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { arr.getJSONObject(it) }.getOrNull() }
    }

    /** Episodes live under the series and anime season routes; try both until one matches. */
    private suspend fun episodeVideos(seasonId: String, episodeNumber: Int): List<JSONObject> {
        for (prefix in listOf("series/season", "animes/season")) {
            val json = getJson("$prefix/$seasonId/$CODE") ?: continue
            val eps = json.optJSONArray("episodes") ?: continue
            for (i in 0 until eps.length()) {
                val ep = eps.getJSONObject(i)
                if (ep.optInt("episode_number", -1) == episodeNumber) {
                    val arr = ep.optJSONArray("videos") ?: return emptyList()
                    return (0 until arr.length())
                        .mapNotNull { runCatching { arr.getJSONObject(it) }.getOrNull() }
                }
            }
        }
        return emptyList()
    }

    /**
     * The API `header` field is one of:
     *  - ""                                        -> no extra headers
     *  - "https://embed.host/"                     -> bare referer
     *  - "origin:https://x|referer:https://y/"      -> explicit headers (order not guaranteed)
     *  - "Accept-Encoding:identity|connection:..."  -> non-referer headers, ignored
     */
    private fun parseHeader(headerRaw: String): Pair<String?, String?> {
        val raw = headerRaw.trim()
        if (raw.isBlank()) return null to null
        if (raw.startsWith("http") && !raw.contains('|')) return raw to null
        var referer: String? = null
        var origin: String? = null
        raw.split('|').forEach { part ->
            val p = part.trim()
            val i = p.indexOf(':')
            if (i <= 0) return@forEach
            val value = p.substring(i + 1).trim()
            when (p.substring(0, i).trim().lowercase()) {
                "referer", "referrer" -> if (value.startsWith("http")) referer = value
                "origin" -> if (value.startsWith("http")) origin = value
            }
        }
        return referer to origin
    }

    private suspend fun resolveVideo(
        video: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = video.optString("link").trim()
        if (link.isBlank() || !link.startsWith("http")) return false
        val server = video.optString("server").ifBlank { name }
        val userAgent = video.optString("useragent").ifBlank { null }
        val (referer, origin) = parseHeader(video.optString("header"))

        // Direct media file — no extraction needed, but skip empty placeholders.
        if (DIRECT_FILE.containsMatchIn(link)) {
            if (isDeadDirect(link, userAgent)) {
                Log.i(TAG, "dead direct link, skipped: $link")
                return false
            }
            callback(newExtractorLink(name, server, link, ExtractorLinkType.VIDEO) {
                if (!referer.isNullOrBlank()) this.referer = referer
                this.headers = buildMap {
                    if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
                    if (!origin.isNullOrBlank()) put("Origin", origin)
                }
            })
            return true
        }

        // The app resolves embeds through its own server-side scraper first.
        if (EgywatchHostExtractor.extract(link, server, referer, origin, userAgent, callback)) return true

        // CloudStream built-in extractors as a fallback (streamwish, uqload, streamruby ...).
        return runCatching {
            loadExtractor(link, referer, subtitleCallback, callback)
        }.getOrElse {
            Log.i(TAG, "loadExtractor failed $link -> $it")
            false
        }
    }
}

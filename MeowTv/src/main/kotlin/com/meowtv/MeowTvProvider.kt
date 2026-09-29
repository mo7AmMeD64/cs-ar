package com.meowtv

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.json.JSONObject
import java.security.MessageDigest

/**
 * MeowTV (meowtv.ru) — CloudStream provider.
 *
 * Metadata: TMDB proxy https://mid.vidzee.wtf/tmdb (same JSON as api.themoviedb.org/3).
 * Streams:   https://api.meowtv.ru — POST /streams/ticket (~58 s validity) then
 *            GET /streams/tv/{tmdbId}/{season}/{episode}?s={server} with header x-stream-ticket.
 *
 * The stream response is encrypted: {"n": "<hex>", "d": "<base64>"}.
 * Algorithm recovered from the client bundle's WASM:
 *   sha   = SHA-256( (STATIC_KEY + n).toByteArray(UTF_8) )   // n as-is, NOT hex-decoded
 *   plain = base64Decode(d) XOR sha[i % 32]                  // cyclic 32 bytes
 *   plain = JSON  ->  {"language":"Auto","url":"https://...m3u8","headers":{}} or {"streams":[...]}
 *
 * Subtitles: GET /subs/tv/{id}/{s}/{e} -> [{"label":"Arabic","file":"https://...vtt"}]
 */
class MeowTvProvider : MainAPI() {

    override var name = "MeowTV"
    override var mainUrl = "https://meowtv.ru"
    private val apiUrl = "https://api.meowtv.ru"
    private val tmdbApi = "https://mid.vidzee.wtf/tmdb"
    private val tmdbKey = "adc48d20c0956934fb224de5c40bb85d"

    // Stream cipher static key — recovered from the client bundle WASM (f45: xor of 3 static strings)
    private val staticKey = "10eE3wyyhkF38rwLFwisRYgwP2bbTL_Y"

    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    companion object {
        private const val TAG = "MeowTV"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0.0.0 Mobile Safari/537.36"

        // Server fallback order (observed: Gachiakuta/Reacher -> dcloud, GoT -> doppler)
        private val SERVERS = listOf(
            "dcloud", "doppler", "acme", "tik", "ipcloud", "turkce", "hindi", "english", "febbox",
        )

        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
    }

    private val siteHeaders = mapOf(
        "Origin" to "https://meowtv.ru",
        "Referer" to "https://meowtv.ru/",
        "User-Agent" to USER_AGENT,
    )

    // ---------- JSON helpers ----------

    private fun String.toJsonArray(): JsonArray? = try {
        if (isBlank() || this == "[]") null
        else Json.parseToJsonElement(this).jsonArray
    } catch (_: Exception) { null }

    private fun String.toJsonObject(): JsonObject? = try {
        if (isBlank()) null
        else Json.parseToJsonElement(this).jsonObject
    } catch (_: Exception) { null }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (v != null && v != "null") return v
        }
        return null
    }

    private fun JsonObject.tmdbPoster(size: String = "w500"): String? =
        str("poster_path")?.let { "$TMDB_IMG/$size$it" }

    // ---------- decrypt ----------

    /** sha = SHA256(STATIC_KEY + n) -> plain = base64Decode(d) XOR sha (cyclic 32) */
    private fun decryptMeow(n: String, d: String): String? = try {
        val sha = MessageDigest.getInstance("SHA-256")
            .digest((staticKey + n).toByteArray(Charsets.UTF_8))   // 32 bytes
        val cipher = Base64.decode(d, Base64.DEFAULT)
        val plain = ByteArray(cipher.size)
        for (i in cipher.indices) plain[i] = (cipher[i].toInt() xor sha[i % 32].toInt()).toByte()
        val text = String(plain, Charsets.UTF_8)
        if (text.contains("{")) text else null
    } catch (_: Exception) { null }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "/trending/all/week" to "Trending This Week",
        "/tv/popular" to "Popular Series",
        "/tv/top_rated" to "Top Rated Series",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/discover/movie?with_genres=16" to "Animation Movies",
    )

    private fun JsonElement.toSearchResult(defaultType: TvType): SearchResponse? {
        val j = this.jsonObject
        val mediaType = j.str("media_type")
            ?: (if (defaultType == TvType.Movie) "movie" else "tv")
        if (mediaType == "person") return null
        val id = j["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = j.str("title", "name", "original_title", "original_name") ?: return null
        val poster = j.tmdbPoster()
        val isMovie = mediaType == "movie"
        return if (isMovie) {
            newMovieSearchResponse(title, "meowtv://movie/$id", TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, "meowtv://tv/$id", TvType.TvSeries) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(
            "$tmdbApi${request.data}",
            headers = siteHeaders,
            params = mapOf("api_key" to tmdbKey, "language" to "en-US", "page" to page.toString()),
        ).text.toJsonObject()

        val defaultType =
            if (request.data.contains("movie")) TvType.Movie else TvType.TvSeries

        val items = res?.get("results")?.jsonArray
            ?.mapNotNull { it.toSearchResult(defaultType) }
            ?: emptyList()

        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get(
            "$tmdbApi/search/multi",
            headers = siteHeaders,
            params = mapOf(
                "api_key" to tmdbKey,
                "language" to "en-US",
                "query" to query,
                "include_adult" to "false",
            ),
        ).text.toJsonObject() ?: return emptyList()

        return res["results"]?.jsonArray
            ?.mapNotNull { it.toSearchResult(TvType.TvSeries) }
            ?: emptyList()
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // url: meowtv://tv/{tmdbId} | meowtv://movie/{tmdbId}
        val scheme = url.substringAfter("meowtv://", "")
        if (scheme.isBlank()) return null
        val isMovie = scheme.startsWith("movie/")
        val id = scheme.substringAfter("/")

        val detailsPath = if (isMovie) "/movie/$id" else "/tv/$id"
        val j = app.get(
            "$tmdbApi$detailsPath",
            headers = siteHeaders,
            params = mapOf("api_key" to tmdbKey, "language" to "en-US"),
        ).text.toJsonObject() ?: return null

        val title = j.str("title", "name", "original_title", "original_name") ?: return null
        val poster = j.tmdbPoster()
        val bgPoster = j.str("backdrop_path")?.let { "$TMDB_IMG/w780$it" }
        val plot = j.str("overview")
        val year = j.str("release_date", "first_air_date")?.take(4)?.toIntOrNull()
        val rating = j["vote_average"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        val tags = j["genres"]?.jsonArray
            ?.mapNotNull { it.jsonObject.str("name") }
            ?: emptyList()

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, "movie:$id") {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
            }
        }

        // TV: episodes from every season
        val seasonsRaw = j["seasons"]?.jsonArray ?: return null
        val episodes = mutableListOf<Episode>()
        for (s in seasonsRaw) {
            val seasonNum = s.jsonObject["season_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            val seasonJson = app.get(
                "$tmdbApi/tv/$id/season/$seasonNum",
                headers = siteHeaders,
                params = mapOf("api_key" to tmdbKey, "language" to "en-US"),
            ).text.toJsonObject() ?: continue

            for (e in seasonJson["episodes"]?.jsonArray ?: emptyList()) {
                val ep = e.jsonObject
                val epNum = ep["episode_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                // fix = false: keep our 'tv:{id}:{s}:{e}' data — newEpisode's default fixUrl()
                // prepends mainUrl and mangles it into 'https://meowtv.ru/tv:...'
                episodes.add(
                    newEpisode(
                        url = "tv:$id:$seasonNum:$epNum",
                        initializer = {
                            this.name = ep.str("name") ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = ep.str("still_path")?.let { "$TMDB_IMG/w300$it" }
                            this.description = ep.str("overview")
                        },
                        fix = false,
                    )
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bgPoster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(rating)
        }
    }

    // ---------- links ----------

    private suspend fun m3u8Link(
        url: String,
        label: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ExtractorLink = newExtractorLink(
        source = name,
        name = label,
        url = url,
        type = ExtractorLinkType.M3U8,
    ) {
        this.referer = mainUrl
        // some streams (i-arch/acme) require their own Referer from the decrypted payload
        this.headers = if (extraHeaders.isNotEmpty()) extraHeaders else mapOf(
            "Origin" to "https://meowtv.ru",
            "Referer" to "https://meowtv.ru/",
        )
    }

    private suspend fun newTicket(): String? =
        app.post(
            "$apiUrl/streams/ticket",
            headers = siteHeaders,
            json = JSONObject(),   // body "{}" with application/json
        ).text.toJsonObject()?.str("ticket")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data: movie:{tmdbId} | tv:{tmdbId}:{season}:{episode}
        // (normalize: the app may prepend mainUrl to the episode data)
        val parts = data.substringAfterLast("/").split(":")
        val kind = parts.getOrNull(0) ?: return false
        Log.i(TAG, "loadLinks data='$data' kind=$kind")

        val streamPath: String
        val subsPath: String
        if (kind == "movie") {
            val id = parts.getOrNull(1) ?: return false
            streamPath = "/streams/movie/$id"      // by analogy, not captured in HAR
            subsPath = "/subs/movie/$id"
        } else {
            val id = parts.getOrNull(1) ?: return false
            val season = parts.getOrNull(2) ?: return false
            val episode = parts.getOrNull(3) ?: return false
            streamPath = "/streams/tv/$id/$season/$episode"
            subsPath = "/subs/tv/$id/$season/$episode"
        }

        // subtitles (plain WebVTT — rezesubs.com 403s requests without proper headers)
        try {
            app.get("$apiUrl$subsPath", headers = siteHeaders).text.toJsonArray()
                ?.forEach { el ->
                    val label = el.jsonObject.str("label") ?: return@forEach
                    val file = el.jsonObject.str("file") ?: return@forEach
                    subtitleCallback(
                        newSubtitleFile(label, file) {
                            this.headers = siteHeaders
                        }
                    )
                }
        } catch (_: Exception) {}

        // streams — fresh ticket per server (observed client behaviour).
        // NOTE: keep API pressure LOW — the API soft-rate-limits (encrypted empty {})
        // when hammered, which kills all subsequent requests. Single pass, one retry.
        var foundAny = false
        var attempt = 0
        while (attempt < 2 && !foundAny) {
            if (attempt > 0) delay(8000)
            for (server in SERVERS) {
                try {
                    val ticket = newTicket()
                    if (ticket.isNullOrBlank()) {
                        Log.i(TAG, "$server: no ticket")
                        continue
                    }

                    val r = app.get(
                        "$apiUrl$streamPath?s=$server",
                        headers = siteHeaders + mapOf("x-stream-ticket" to ticket),
                    )
                    Log.i(TAG, "$server: ${r.code}")
                    if (r.code != 200) continue   // 404 = No stream -> next server

                    val blob = r.text.toJsonObject()
                    val n = blob?.str("n")
                    val d = blob?.str("d")
                    if (n == null || d == null) {
                        Log.i(TAG, "$server: blob missing n/d")
                        continue
                    }
                    val plain = decryptMeow(n, d)
                    val stream = plain?.toJsonObject()
                    if (stream == null) {
                        Log.i(TAG, "$server: decrypt failed")
                        continue
                    }

                    // headers the stream itself requires (e.g. Referer for i-arch)
                    val payloadHeaders = LinkedHashMap<String, String>()
                    val hdrs = stream["headers"]?.jsonObject
                    if (hdrs != null) {
                        for ((k, v) in hdrs.entries) {
                            val value = v.jsonPrimitive.contentOrNull
                            if (!value.isNullOrBlank()) payloadHeaders[k] = value
                        }
                    }

                    // {"language":"Auto","url":"https://...m3u8","headers":{}} — single-stream shape
                    val single = stream.str("url")
                    if (single != null) {
                        Log.i(TAG, "$server: URL $single")
                        callback(m3u8Link(single, stream.str("language") ?: "Auto", payloadHeaders))
                        foundAny = true
                    }

                    // {"streams": [{"language": "...", "url": "..."}]} — multi-server shape
                    stream["streams"]?.jsonArray?.forEach { el ->
                        val sUrl = el.jsonObject.str("url") ?: return@forEach
                        if (!sUrl.startsWith("http")) return@forEach   // skip embed pages
                        Log.i(TAG, "$server: streams[] $sUrl")
                        callback(m3u8Link(sUrl, el.jsonObject.str("language") ?: "Auto", payloadHeaders))
                        foundAny = true
                    }

                    if (foundAny) return true
                } catch (e: Exception) {
                    Log.i(TAG, "$server: ERR ${e.message}")
                }
            }
            attempt++
        }
        Log.i(TAG, "loadLinks done, found=$foundAny")
        return foundAny
    }
}

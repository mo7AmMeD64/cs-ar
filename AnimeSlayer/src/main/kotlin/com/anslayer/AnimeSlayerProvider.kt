package com.anslayer

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Anime Slayer (أنمي سلاير, anslayer.com) — CloudStream provider.
 *
 * API (from vinovo.to HAR capture + live verification):
 * - Base: https://anslayer.com/anime/public
 *   headers: client-id: android-app2, client-secret: 7befba62..., UA okhttp/5.3.2,
 *   the Accept header is application-star-plus-json — WITHOUT client-id/secret the API 403s
 *   ("Invalid Client-Id and Client-Secret")
 * - GET animes/get-published-animes?json={url-encoded}:
 *     {"_offset":0,"_limit":30,"_order_by":"latest_first","list_type":"anime_list","just_info":"Yes"}
 *     list_type "filter" + anime_name = the SEARCH
 *     list_type "latest_updated_episode_new" = the latest episodes
 *     -> response.data[{anime_id, anime_name, anime_type: TV|Movie, anime_release_year,
 *        anime_rating, anime_cover_image_url, anime_status}]
 * - GET anime/get-anime-details?anime_id={id}&fetch_episodes=Yes&more_info=No
 *     -> response.episodes = {"data": [19 episodes]} each with episode_urls EMBEDDED
 *     (cdn vq.php is DEAD/404; muilt = a-reslayer.com/la/public/api/f?n={slug}\{ep} works)
 * - GET https://a-reslayer.com/la/public/api/f?n={slug}\{ep} -> JSON array of hoster urls:
 *     mediafire (file_premium -> direct mp4 scrape), streamtape, vinovo (recaptcha, skip),
 *     playmate (POST /api/s {"c":id,"d":"web"} -> sx = HLS master), firestream (IP-bound, skip)
 */
class AnimeSlayerProvider : MainAPI() {

    override var name = "أنمي سلاير"
    override var mainUrl = "https://anslayer.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)

    companion object {
        private const val API = "https://anslayer.com/anime/public"
        private const val CLIENT_ID = "android-app2"
        private const val CLIENT_SECRET = "7befba6263cc14c90d2f1d6da2c5cf9b251bfbbd"
        private const val UA = "okhttp/5.3.2"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private fun apiHeaders() = mapOf(
        "client-id" to CLIENT_ID,
        "client-secret" to CLIENT_SECRET,
        "User-Agent" to UA,
        "Accept" to "application/*+json",
    )

    private suspend fun apiGet(url: String, params: Map<String, String> = mapOf()): JsonObject? = try {
        json.parseToJsonElement(app.get(url, params = params, headers = apiHeaders()).text).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.arr(key: String) = this[key]?.jsonArray

    private fun JsonObject.obj(key: String) = this[key]?.jsonObject

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (v != null && v != "null") return v
        }
        return null
    }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun itemFrom(o: JsonObject): SearchResponse? {
        val id = o.str("anime_id") ?: return null
        val title = o.str("anime_name") ?: return null
        val isMovie = o.str("anime_type")?.contains("Movie", ignoreCase = true) == true
        return newTvSeriesSearchResponse(
            title,
            "anslayer://anime/$id",
            if (isMovie) TvType.Movie else TvType.Anime,
        ) {
            this.year = o.int("anime_release_year") ?: o.str("anime_release_year")?.take(4)?.toIntOrNull()
            this.posterUrl = o.str("anime_cover_image_url")
        }
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "anime_list" to "الأحدث",
        "latest_updated_episode_new" to "أحدث الحلقات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val listJson = """{"_offset":${(page - 1) * 30},"_limit":30,"_order_by":"latest_first","list_type":"${request.data}","just_info":"Yes"}"""
        val j = apiGet(
            "$API/animes/get-published-animes",
            params = mapOf("json" to listJson),
        )
        val items = j?.obj("response")?.arr("data")?.mapNotNull { it.jsonObject.let(::itemFrom) } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val listJson = """{"_offset":0,"_limit":30,"_order_by":"latest_first","list_type":"filter","anime_name":"$q","just_info":"Yes"}"""
        val j = apiGet(
            "$API/animes/get-published-animes",
            params = mapOf("json" to listJson),
        )
        return j?.obj("response")?.arr("data")?.mapNotNull { it.jsonObject.let(::itemFrom) } ?: emptyList()
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // NOTE: the url may be mangled by CloudStream's fixUrl; check "anslayer://" first
        if (!url.contains("anslayer://")) return null
        val id = url.substringAfter("anslayer://anime/", "").substringBefore("?").trim()
        if (id.isBlank()) return null

        val j = apiGet(
            "$API/anime/get-anime-details",
            params = mapOf("anime_id" to id, "fetch_episodes" to "Yes", "more_info" to "No"),
        )?.obj("response") ?: return null
        val title = j.str("anime_name") ?: "anime $id"
        val poster = j.str("anime_cover_image_url")
        val plot = j.str("anime_description")
        val year = j.int("anime_release_year") ?: j.str("anime_release_year")?.take(4)?.toIntOrNull()
        val status = when (j.str("anime_status")) {
            "Finished Airing" -> ShowStatus.Completed
            "Currently Airing" -> ShowStatus.Ongoing
            else -> null
        }
        val tags = j.str("anime_genres")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val isMovie = j.str("anime_type")?.contains("Movie", ignoreCase = true) == true

        // episodes.data[...] each with episode_urls (the muilt a-reslayer f URL works)
        val epList = j.obj("episodes")?.arr("data")?.mapNotNull { it.jsonObject } ?: emptyList()
        val episodes = epList.mapNotNull { e ->
            val urls = e.arr("episode_urls")?.mapNotNull { it.jsonObject } ?: emptyList()
            // prefer the "muilt" (a-reslayer f) server — it returns the hoster url array
            val f2 = urls.firstOrNull { it.str("episode_server_name") == "muilt" }?.str("episode_url")
                ?: urls.firstOrNull { it.str("episode_url")?.contains("/api/f?") == true }?.str("episode_url")
                ?: return@mapNotNull null
            val epNum = e.int("episode_number")
            newEpisode(
                url = "anslayer://f2/$f2",
                initializer = {
                    this.name = e.str("episode_name") ?: "حلقة ${epNum ?: ""}"
                    this.episode = epNum
                    this.posterUrl = null
                },
                fix = false,
            )
        }
        if (isMovie || epList.size <= 1) {
            if (episodes.isNotEmpty()) {
                return newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.backgroundPosterUrl = j.str("anime_banner_image_url")
                    this.comingSoon = false
                }
            }
        }
        if (episodes.isEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, emptyList()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.backgroundPosterUrl = j.str("anime_banner_image_url")
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.backgroundPosterUrl = j.str("anime_banner_image_url")
            this.showStatus = status
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.contains("anslayer://f2/")) {
            val f2Url = data.substringAfter("anslayer://f2/").trim()
            val j = try {
                json.parseToJsonElement(app.get(f2Url, headers = mapOf("User-Agent" to UA)).text).jsonArray
            } catch (_: Exception) {
                return false
            }
            var got = false
            for (u in j) {
                val url = u.jsonPrimitive.contentOrNull ?: continue
                got = extractHoster(url, subtitleCallback, callback) || got
            }
            return got
        }
        // direct hoster url fallback
        if (data.startsWith("http")) {
            return extractHoster(data, subtitleCallback, callback)
        }
        return false
    }

    /** resolve one hoster url (mediafire/playmate/streamtape/lulustream/...) */
    private suspend fun extractHoster(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return when {
            url.contains("mediafire.com") -> resolveMediafire(url, callback)
            url.contains("playmate.to") -> resolvePlaymate(url, callback)
            url.contains("vinovo.to") -> false   // recaptcha-gated token endpoint
            else -> try {
                loadExtractor(url, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)
            } catch (_: Exception) {
                false
            }
        }
    }

    /** mediafire file page -> the direct download url */
    private suspend fun resolveMediafire(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val html = app.get(url, headers = mapOf("User-Agent" to UA)).text
            val dl = Regex("href=\"(https://download[^\"]+)\"").find(html)?.groupValues?.get(1) ?: return false
            val name = dl.substringAfterLast("/").substringBeforeLast(".")
            callback(
                newExtractorLink("Mediafire", name, dl, ExtractorLinkType.VIDEO) {
                    this.referer = "https://www.mediafire.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** playmate: POST api/s with c=id and d=web -> sx = HLS master */
    private suspend fun resolvePlaymate(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val id = url.substringAfterLast("/").substringBefore("?")
            val r = try {
                json.parseToJsonElement(app.post(
                    "https://playmate.to/api/s",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to "https://playmate.to/",
                        "Content-Type" to "application/json",
                    ),
                    json = org.json.JSONObject(mapOf("c" to id, "d" to "web")),
                ).text).jsonObject
            } catch (_: Exception) {
                return false
            }
            val sx = r.str("sx") ?: return false
            callback(
                newExtractorLink("Playmate", "Playmate", sx, ExtractorLinkType.M3U8) {
                    this.referer = "https://playmate.to/"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}

package com.stardima

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
 * Stardima (ستارديما, jcartoonApp) — CloudStream provider.
 *
 * API (from api.onesignal.com HAR capture + live verification):
 * - Base: https://app.stardima.com/api  (x-api-key required, plain JSON)
 * - searchByOption?type=series|movies|last_episodes and page=N -> videos + pagination
 * - video/{id} -> detail: type, seasons[{id, name, season_number}],
 *   movie servers[{label, type: "mp4"|"backup", url}]
 * - episodes/{season_id} -> data[{title, episode_number, watch_url}] (PLAIN, no decryption!)
 * - serversvip is encrypted but NOT needed (the free watch_url path works)
 * - Episode watch_url = v2.hyperwatching.com/watch/{hashid} (Laravel Inertia SPA):
 *   props.video.servers[{id, name}] -> GET embed/{hashid}/server/{id}/url -> watch_url (hoster embed)
 * - Movie "backup" servers = direct mp4 (stardima.worldnow.top/aflam/...)
 * - Hoster embeds (uqload/lulustream/savefiles/mixdrop/hgcloud) -> loadExtractor
 */
class StardimaProvider : MainAPI() {

    override var name = "ستارديما"
    override var mainUrl = "https://app.stardima.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Cartoon, TvType.Anime)

    companion object {
        private const val API = "https://app.stardima.com/api"
        private const val HW = "https://v2.hyperwatching.com"
        private const val API_KEY = "vGIu8q9aap55zyANSD3jvmbttClhuRmykbQjzsIQxoWloFmp29W2qqdTSrwR"
        private const val UA = "jcartoonApp/1.0.7 (Android)"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private fun apiHeaders() = mapOf(
        "x-api-key" to API_KEY,
        "User-Agent" to UA,
        "Accept" to "application/json",
    )

    /** one GET for all API endpoints, returns null on any failure */
    private suspend fun apiGet(url: String, params: Map<String, String> = mapOf(), headers: Map<String, String> = apiHeaders()): JsonObject? = try {
        json.parseToJsonElement(app.get(url, params = params, headers = headers).text).jsonObject
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
        val id = o.str("id") ?: return null
        val title = o.str("title") ?: return null
        val isSeries = o.str("is_series")?.lowercase() == "true"
        return newTvSeriesSearchResponse(
            title,
            "stardima://vid/$id",
            if (isSeries) TvType.TvSeries else TvType.Movie,
        ) {
            this.year = o.int("year") ?: o.str("year")?.take(4)?.toIntOrNull()
            this.posterUrl = o.str("poster_url") ?: o.str("background_url")
        }
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "series" to "مسلسلات",
        "movies" to "أفلام",
        "last_episodes" to "أحدث الحلقات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val j = apiGet("$API/searchByOption", params = mapOf("type" to request.data, "query" to "", "page" to page.toString()))
        val items = j?.arr("videos")?.mapNotNull { it.jsonObject.let(::itemFrom) } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val j = apiGet("$API/search", params = mapOf("categories[]" to "all", "query" to q, "language" to "all"))
        return j?.arr("videos")?.mapNotNull { it.jsonObject.let(::itemFrom) } ?: emptyList()
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // NOTE: the url may be mangled by CloudStream's fixUrl; check "stardima://" first
        if (!url.contains("stardima://")) return null
        val payload = url.substringAfter("stardima://", "")
        if (!payload.startsWith("vid/")) return null
        val id = payload.substringAfter("/").substringBefore("?").trim()
        if (id.isBlank()) return null

        val j = apiGet("$API/video/$id") ?: return null
        val title = j.str("title") ?: "video $id"
        val poster = j.str("poster_url")
        val plot = j.str("description")
        val year = j.int("year") ?: j.str("year")?.take(4)?.toIntOrNull()
        val tags = j.str("tags")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val background = j.str("background_url")

        if (j.str("type") == "series") {
            val seasons = j.arr("seasons")?.mapNotNull { it.jsonObject } ?: emptyList()
            // fetch all seasons in parallel
            val seasonLists = coroutineScope {
                seasons.map { s -> async { getEpisodes(s) } }.awaitAll()
            }
            val episodes = seasonLists.flatten()
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.backgroundPosterUrl = background
                this.showStatus = when (j.str("status_text")) {
                    "مكتمل" -> ShowStatus.Completed
                    else -> ShowStatus.Ongoing
                }
            }
        }
        // movie
        return newMovieLoadResponse(title, url, TvType.Movie, "stardima://vid/$id") {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.backgroundPosterUrl = background
        }
    }

    private suspend fun getEpisodes(season: JsonObject): List<Episode> {
        val seasonId = season.str("id") ?: return emptyList()
        val j = apiGet("$API/episodes/$seasonId") ?: return emptyList()
        val seasonNum = season.int("season_number")
        return j.arr("data")?.mapNotNull { d ->
            val o = d.jsonObject
            val watch = o.str("watch_url") ?: return@mapNotNull null
            val epNum = o.int("episode_number")
            val title = o.str("title") ?: "حلقة ${epNum ?: ""}"
            newEpisode(
                url = "stardima://hw/$watch",
                initializer = {
                    this.name = title
                    this.episode = epNum
                    this.season = seasonNum
                },
                fix = false,
            )
        } ?: emptyList()
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // NOTE: the data may be the mangled load url; check "stardima://" FIRST
        if (data.contains("stardima://vid/")) {
            val id = data.substringAfter("stardima://vid/").substringBefore("?").trim()
            val j = apiGet("$API/video/$id") ?: return false
            var got = false
            for (s in j.arr("servers") ?: emptyList()) {
                val o = s.jsonObject
                val sUrl = o.str("url") ?: continue
                if (o.str("type") == "backup") {
                    callback(
                        newExtractorLink(name, o.str("label") ?: "backup", sUrl, ExtractorLinkType.VIDEO) {
                            this.referer = ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    got = true
                } else {
                    got = loadExtractor(sUrl, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback) || got
                }
            }
            return got
        }
        if (data.contains("stardima://hw/")) {
            val watchUrl = data.substringAfter("stardima://hw/").trim()
            val hashid = watchUrl.substringAfterLast("/").substringBefore("?")
            val html = try {
                app.get(watchUrl, headers = mapOf("User-Agent" to UA, "Referer" to "$HW/")).text
            } catch (_: Exception) {
                return false
            }
            // parse the Inertia data-page props (HTML-unescaped JSON)
            val pageJson = Regex("data-page=\"([^\"]*)\"").find(html)?.groupValues?.get(1) ?: return false
            val unescaped = pageJson
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&#039;", "\'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
            val page = try {
                json.parseToJsonElement(unescaped).jsonObject
            } catch (_: Exception) {
                return false
            }
            val video = page.obj("props")?.obj("video") ?: return false
            val servers = video.arr("servers") ?: return false
            var got = false
            for (s in servers) {
                val o = s.jsonObject
                val linkId = o.str("id") ?: continue
                val wu = try {
                    apiGet(
                        "$HW/embed/$hashid/server/$linkId/url",
                        headers = mapOf(
                            "User-Agent" to UA,
                            "Accept" to "application/json",
                            "Referer" to watchUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                    )?.str("watch_url")
                } catch (_: Exception) { null } ?: continue
                got = loadExtractor(wu, referer = HW, subtitleCallback = subtitleCallback, callback = callback) || got
            }
            return got
        }
        // direct url fallback
        if (data.startsWith("http")) {
            callback(
                newExtractorLink(name, "direct", data, ExtractorLinkType.VIDEO) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }
}

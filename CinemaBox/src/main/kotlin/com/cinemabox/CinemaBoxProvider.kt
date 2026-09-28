package com.cinemabox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/**
 * Cinema Box (cinema.albox.co) provider.
 *
 * API (v4, no login needed, identified only by "Device-*" headers):
 *   GET /home                               -> sections[] (each with data[] cards)
 *   GET /search?term=&page_number=&page_size=[&category_id=]
 *   GET /shows/shows/dynamic/{id}           -> post_info + sections (trailer / episodes / related)
 *   GET /shows/seasons/player/{seasonId}    -> episodes[] of a season
 *   GET /shows/episodes/player/{id}         -> videos[] + subtitles[] (movies use the show id)
 */
class CinemaBoxProvider : MainAPI() {

    override var name = "Cinema Box"
    override var mainUrl = "https://cinema.albox.co"
    private val apiUrl = "$mainUrl/api/v4"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
    )

    companion object {
        private const val PAGE_SIZE = 25
        private const val MAX_SEARCH_PAGES = 3
        private const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 14; 23043RP34G Build/UKQ1.240624.001)"
        private val ANIME_GENRES = setOf("shounen", "shoujo", "seinen", "josei", "anime")
    }

    // Random per-session id, the API only uses it to tell devices apart.
    private val deviceId: String =
        (1..16).map { "0123456789abcdef".random() }.joinToString("")

    private val apiHeaders: Map<String, String> = mapOf(
        "Accept-Language" to "en",
        "Device-Id" to deviceId,
        "Device-Model" to "Xiaomi 23043RP34G",
        "Device-OS-Version" to "14",
        "Device-Store" to "googleplay",
        "App-Version" to "4.6.12",
        "X-Hide-Sensitive-Content" to "true",
        "X-Local-Before" to "true",
        "X-ISP-ID" to "1",
        "User-Agent" to USER_AGENT,
    )

    // ------------------------------------------------------------------ helpers

    private fun String.toJsonObject(): JsonObject? = try {
        if (isBlank()) null else Json.parseToJsonElement(this).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

    private suspend fun apiGet(path: String): JsonObject? = try {
        app.get("$apiUrl/$path", headers = apiHeaders).text.toJsonObject()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Card -> SearchResponse. Only MOVIE / SERIES cards are kept (genres, ads, collections are dropped). */
    private fun JsonObject.toSearch(categoryHint: String? = null): SearchResponse? {
        val id = str("id") ?: return null
        val title = str("title")?.trim() ?: return null
        val poster = obj("style")?.str("image")

        return when (str("type")?.uppercase()) {
            "MOVIE" -> newMovieSearchResponse(title, id, TvType.Movie) {
                this.posterUrl = poster
            }

            "SERIES" -> {
                val hint = categoryHint?.lowercase() ?: ""
                val tvType = when {
                    hint.contains("anime") -> TvType.Anime
                    hint.contains("cartoon") || hint.contains("carton") -> TvType.Cartoon
                    else -> TvType.TvSeries
                }
                newTvSeriesSearchResponse(title, id, tvType) {
                    this.posterUrl = poster
                }
            }

            else -> null
        }
    }

    private suspend fun searchPage(
        term: String,
        page: Int,
        categoryId: String?,
    ): Pair<List<SearchResponse>, Int> {
        val q = URLEncoder.encode(term, "UTF-8")
        val cat = if (categoryId != null) "&category_id=$categoryId" else ""
        val res = apiGet("search?page_size=$PAGE_SIZE&page_number=$page&term=$q$cat")
            ?: return emptyList<SearchResponse>() to 0

        val items = res.arr("results")?.objects()
            ?.mapNotNull { it.toSearch(it.str("category_name")) }
            ?: emptyList()
        val totalPages = res.obj("pagination")?.str("total_pages")?.toIntOrNull() ?: 1
        return items to totalPages
    }

    private fun epochMillisToYear(millis: String?): Int? {
        val ms = millis?.toLongOrNull() ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR)
    }

    private fun languageName(code: String): String = when (code.lowercase()) {
        "ar" -> "Arabic"
        "en" -> "English"
        "fr" -> "French"
        "tr" -> "Turkish"
        "es" -> "Spanish"
        else -> code
    }

    // ---------------------------------------------------------------- main page

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "18" to "أفلام",
        "20" to "مسلسلات",
        "8" to "أنمي",
        "9" to "برامج تلفزيونية",
        "39" to "كرتون",
        "50" to "رمضان",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // "home" returns every section of the app home screen in one request.
        if (request.data == "home") {
            if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

            val res = apiGet("home")
                ?: return newHomePageResponse(emptyList<HomePageList>(), false)

            val lists = res.arr("sections")?.objects()?.mapNotNull { section ->
                val title = section.str("title")?.trim()
                if (title.isNullOrBlank()) return@mapNotNull null
                val items = section.arr("data")?.objects()
                    ?.mapNotNull { it.toSearch(title) }
                    ?: emptyList()
                if (items.isEmpty()) null
                else HomePageList(title.replace("Carton", "Cartoon"), items)
            } ?: emptyList()

            return newHomePageResponse(lists, false)
        }

        // Any other entry is a category id, paged through /search.
        val (items, totalPages) = searchPage("", page, request.data)
        return newHomePageResponse(request, items, page < totalPages)
    }

    // ------------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        var page = 1
        while (page <= MAX_SEARCH_PAGES) {
            val (items, totalPages) = searchPage(query.trim(), page, null)
            results.addAll(items)
            if (items.isEmpty() || page >= totalPages) break
            page++
        }
        return results.distinctBy { it.url }
    }

    // --------------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse? {
        val id = url.trim().substringAfterLast("/")

        var res = apiGet("shows/shows/dynamic/$id")
        if (res?.obj("post_info") == null) {
            res = apiGet("shows/shows/dynamic/$id?season_id=0")
        }
        val details = res ?: return null
        val info = details.obj("post_info") ?: return null

        val title = info.str("title")?.trim() ?: return null
        val poster = info.str("image")
        val background = info.str("background_image")
        val year = epochMillisToYear(info.str("release_date"))
        val genres = info.arr("genres")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val rating = info.obj("rating")
        val header = listOfNotNull(
            rating?.str("value")?.let { "IMDb $it" },
            rating?.str("age"),
        ).joinToString(" | ")
        val plot = listOf(header, info.str("description")?.trim() ?: "")
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }

        val sections = details.arr("sections")?.objects() ?: emptyList()

        val trailerUrl = sections
            .firstOrNull { it.str("section_type") == "trailer" }
            ?.arr("data")?.objects()?.firstOrNull()
            ?.str("url")

        val related = sections
            .filter { it.str("section_type") == "normalPoster" }
            .flatMap { s -> s.arr("data")?.objects()?.mapNotNull { it.toSearch() } ?: emptyList() }

        val isMovie = info.str("type")?.uppercase() == "MOVIE"

        if (isMovie) {
            val playerId = info.str("episode_id") ?: id
            return newMovieLoadResponse(title, id, TvType.Movie, playerId) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.plot = plot
                this.tags = genres
                this.year = year
                this.duration = info.str("length")?.toIntOrNull()?.div(60)
                this.recommendations = related
                addTrailer(trailerUrl)
            }
        }

        // ---- series: collect season ids (season number -> id)
        val seasons = mutableListOf<Pair<Int, String>>()
        sections
            .filter { it.str("section_type")?.contains("season", ignoreCase = true) == true }
            .forEach { s ->
                s.arr("data")?.objects()?.forEachIndexed { index, item ->
                    item.str("id")?.let { sid -> seasons.add((index + 1) to sid) }
                }
            }
        val currentSeasonId = info.str("current_season_id")
        if (seasons.none { it.second == currentSeasonId } && currentSeasonId != null) {
            seasons.add((info.str("season_number")?.toIntOrNull() ?: 1) to currentSeasonId)
        }

        val episodes = mutableListOf<Episode>()
        for ((seasonNumber, seasonId) in seasons) {
            val list = apiGet("shows/seasons/player/$seasonId")?.arr("episodes")?.objects()
                ?: continue
            list.forEachIndexed { index, ep ->
                val epId = ep.str("id") ?: return@forEachIndexed
                val epNumber = ep.str("episode_number")?.toIntOrNull() ?: (index + 1)
                episodes.add(
                    newEpisode(epId) {
                        this.name = "الحلقة $epNumber"
                        this.season = seasonNumber
                        this.episode = epNumber
                        this.posterUrl = ep.str("image")
                        this.runTime = ep.str("length")?.toIntOrNull()?.div(60)
                    }
                )
            }
        }

        // Fallback: the "episodes" section of the details response.
        if (episodes.isEmpty()) {
            sections.firstOrNull { it.str("section_type") == "episodes" }
                ?.arr("data")?.objects()?.forEachIndexed { index, ep ->
                    val epId = ep.str("id") ?: return@forEachIndexed
                    val epNumber = ep.str("description")?.toIntOrNull() ?: (index + 1)
                    episodes.add(
                        newEpisode(epId) {
                            this.name = "الحلقة $epNumber"
                            this.season = info.str("season_number")?.toIntOrNull() ?: 1
                            this.episode = epNumber
                            this.posterUrl = ep.obj("style")?.str("image")
                            this.runTime = ep.str("length")?.toIntOrNull()?.div(60)
                        }
                    )
                }
        }

        val sorted = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        val isAnime = genres.any { it.lowercase() in ANIME_GENRES }
        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, id, tvType, sorted) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.plot = plot
            this.tags = genres
            this.year = year
            this.recommendations = related
            addTrailer(trailerUrl)
        }
    }

    // ---------------------------------------------------------------- loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = data.trim().substringAfterLast("/")
        val res = apiGet("shows/episodes/player/$id") ?: return false

        res.arr("subtitles")?.objects()?.forEach { sub ->
            val subUrl = sub.str("vtt") ?: sub.str("srt") ?: return@forEach
            val code = sub.str("language") ?: "ar"
            subtitleCallback(newSubtitleFile(languageName(code), subUrl))
        }

        var found = false
        res.arr("videos")?.objects()?.forEach { video ->
            val videoUrl = video.str("url") ?: return@forEach
            val quality = video.str("quality") ?: ""
            callback(
                newExtractorLink(
                    source = name,
                    name = if (quality.isBlank()) name else "$name $quality",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = getQualityFromName(quality)
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            found = true
        }
        return found
    }
}

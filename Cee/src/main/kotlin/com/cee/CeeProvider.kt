package com.cee

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder

class CeeProvider : MainAPI() {
    override var name = "Cee"
    override var mainUrl = "https://cee.buzz"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private val api = "$mainUrl/api/android"
    private val json = Json { ignoreUnknownKeys = true }

    // =============================== main page ===============================

    override val mainPage = mainPageOf(
        "new" to "أحدث الإضافات",
        "m:desc" to "أفلام - الأحدث",
        "m:views_desc" to "أفلام - الأكثر مشاهدة",
        "m:stars_desc" to "أفلام - أعلى تقييم",
        "s:desc" to "مسلسلات - الأحدث",
        "s:views_desc" to "مسلسلات - الأكثر مشاهدة",
        "s:stars_desc" to "مسلسلات - أعلى تقييم",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = when {
            request.data == "new" -> {
                val resp = apiArr("newlyVideosItems/level/0/offset/12/page/$page")
                resp.mapNotNull { it.toSearch() }
            }
            request.data.startsWith("m:") || request.data.startsWith("s:") -> {
                val kind = if (request.data.startsWith("m:")) 1 else 2
                val sort = request.data.substringAfter(":")
                val zeroPage = (page - 1).coerceAtLeast(0)
                val resp = apiArr("video/V/2?videoKind=$kind&itemsPerPage=30&pageNumber=$zeroPage&sortParam=$sort")
                resp.mapNotNull { it.toSearch() }
            }
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items, items.size >= 24)
    }

    // =============================== search ===============================

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val q = URLEncoder.encode(query.trim(), "utf-8")
        if (q.isBlank()) return@coroutineScope emptyList()
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val range = "1900,$year"

        val (movies, series) = listOf("movies", "series").map { type ->
            async {
                apiArr("AdvancedSearch?level=0&videoTitle=$q&staffTitle=$q&year=$range&page=0&type=$type&itemsPerPage=30")
                    .mapNotNull { it.toSearch() }
            }
        }.awaitAll()

        // interleave movies and series for balanced results
        val out = ArrayList<SearchResponse>(movies.size + series.size)
        val max = maxOf(movies.size, series.size)
        for (i in 0 until max) {
            if (i < movies.size) out.add(movies[i])
            if (i < series.size) out.add(series[i])
        }
        out.distinctBy { it.url }
    }

    // =============================== load ===============================

    override suspend fun load(url: String): LoadResponse? {
        val id = url.trim().substringAfterLast("/")
        val j = apiObj("allVideoInfo/id/$id") ?: return null

        val title = j.str("en_title") ?: return null
        val poster = j.str("imgObjUrl") ?: j.str("img")
        val plot = j.str("ar_content") ?: j.str("en_content")
        val year = j.str("year")?.take(4)?.toIntOrNull()
        val score = j.str("stars")?.toFloatOrNull()?.let { Score.from10(it) }
        val tags = (j["categories"] as? List<*>)?.mapNotNull { c ->
            (c as? Map<*, *>)?.let { m -> (m["en_title"] ?: m["ar_title"]) as? String }
        }?.filter { it.isNotBlank() } ?: emptyList()

        if (j.str("kind")?.toIntOrNull() == 2) {
            // series
            val eps = apiArr("videoSeason/id/$id")
            val episodes = eps.mapNotNull { ep ->
                val epId = ep.str("nb") ?: return@mapNotNull null
                val epTitle = ep.str("en_title") ?: "حلقة"
                val epNum = ep.str("episodeNummer")?.toIntOrNull() ?: 1
                val seasonNum = ep.str("season")?.toIntOrNull() ?: 1
                newEpisode(epId) {
                    this.name = "موسم $seasonNum - الحلقة $epNum"
                    this.episode = epNum
                    this.season = seasonNum
                    this.posterUrl = ep.str("imgObjUrl") ?: poster
                    this.description = ep.str("ar_content")
                }
            }.sortedBy { (it.season ?: 1) * 10000 + (it.episode ?: 0) }

            return newTvSeriesLoadResponse(title, id, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = tags
            }
        }

        // movie
        return newMovieLoadResponse(title, id, TvType.Movie, id) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.score = score
            this.tags = tags
        }
    }

    // =============================== links ===============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = data.trim().substringAfterLast("/")

        val videos = apiArr("transcoddedFiles/id/$id")
        if (videos.isEmpty()) return false

        // reversed = highest quality first
        videos.reversed().forEach { v ->
            val videoUrl = v.str("videoUrl") ?: return@forEach
            val resolution = v.str("resolution") ?: "Default"
            callback(
                newExtractorLink(source = name, name = resolution, url = videoUrl) {
                    getQualityFromName(resolution)
                },
            )
        }

        // subtitles
        apiObj("allVideoInfo/id/$id")?.let { details ->
            (details["translations"] as? List<*>)?.forEach { sub ->
                val m = sub as? Map<*, *> ?: return@forEach
                val file = m["file"] as? String ?: return@forEach
                val lang = m["name"] as? String ?: return@forEach
                subtitleCallback(SubtitleFile(lang, file))
            }
        }

        return true
    }

    // =============================== helpers ===============================

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] as? JsonPrimitive ?: continue
            val c = v.contentOrNull ?: continue
            if (c.isNotBlank() && c != "null") return c
        }
        return null
    }

    private suspend fun apiArr(path: String): List<JsonObject> = try {
        val text = app.get("$api/$path").text
        if (text.isBlank()) emptyList()
        else json.parseToJsonElement(text).let { el ->
            (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun apiObj(path: String): JsonObject? = try {
        val text = app.get("$api/$path").text
        if (text.isBlank()) null else json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.toSearch(): SearchResponse? {
        val nb = str("nb") ?: return null
        val title = str("en_title") ?: str("ar_title") ?: return null
        val poster = str("imgObjUrl") ?: str("img")
        val score = str("stars")?.toFloatOrNull()?.let { Score.from10(it) }
        val isSeries = str("kind")?.toIntOrNull() == 2

        return if (isSeries) {
            newTvSeriesSearchResponse(title, nb, TvType.TvSeries) {
                this.posterUrl = poster
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, nb, TvType.Movie) {
                this.posterUrl = poster
                this.score = score
            }
        }
    }
}

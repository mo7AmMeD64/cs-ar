package com.cinemaplus

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Cinema Plus (cinema.plus app) — CloudStream provider.
 *
 * The app is a WebView shell around a GitHub Pages site:
 *   https://ffrrx-2000.github.io/cinema-plas-bot/
 *
 * - The home page contains the WHOLE catalog inline (615 movie cards):
 *     <a class="slider-link" href="movie.html?tmdb={tmdbId}&mux={muxPlaybackId}">
 *     with title from img.movie-poster[alt] and TMDB w780 poster from [src].
 * - discover.html has 497 more cards with rich metadata:
 *     <div class="card-wrapper" data-title="..." data-genre="Drama|Music" data-year="2026">
 *       <a class="card" href="movie.html?tmdb=...&mux=...">
 * - Playback: plain Mux HLS, no ticket/encryption:
 *     https://stream.mux.com/{muxPlaybackId}.m3u8  (multi-rendition master playlist)
 * - Metadata: TMDB direct (api.themoviedb.org), language=ar.
 * - Movies only (site currently has no series streams; series pages not captured).
 */
class CinemaPlusProvider : MainAPI() {

    override var name = "Cinema Plus"
    override var mainUrl = SITE
    private val tmdbApi = "https://api.themoviedb.org/3"
    private val tmdbKey = "06f120992cfacd7c118f6e7086d23544"

    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val TAG = "CinemaPlus"
        private const val SITE = "https://ffrrx-2000.github.io/cinema-plas-bot/"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
    }

    // ---------- catalog ----------

    private data class CatalogItem(
        val tmdbId: Int,
        val muxId: String,
        val title: String,
        val poster: String?,
        val year: Int? = null,
        val genres: List<String> = emptyList(),
    )

    private fun String.toDoc() = try {
        Jsoup.parse(this, SITE)
    } catch (_: Exception) { null }

    private fun kotlinx.serialization.json.JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (v != null && v != "null") return v
        }
        return null
    }

    /** Extract tmdb id + mux playback id from 'movie.html?tmdb=X&mux=Y' */
    private fun parseCardHref(href: String): Pair<Int, String>? {
        val clean = href.substringAfterLast("/")
        if (!clean.startsWith("movie.html")) return null
        val tmdb = clean.substringAfter("tmdb=", "").substringBefore("&").trim().toIntOrNull() ?: return null
        val mux = clean.substringAfter("mux=", "").trim().takeWhile { it.isLetterOrDigit() }
        if (mux.length < 20) return null
        return tmdb to mux
    }

    /** Walk up to the card element that holds the poster/title */
    private fun findCard(a: Element): Element? {
        var el: Element? = a
        repeat(5) {
            el = el?.parent() ?: return null
            val cls = el?.className() ?: ""
            if (cls.contains("card-wrapper") || cls.contains("movie-card")) return el
        }
        return null
    }

    private fun parseCatalogPage(html: String): List<CatalogItem> {
        val doc = html.toDoc() ?: return emptyList()
        val anchors = doc.select("a[href*=movie.html?tmdb=]")
        val out = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<Int>()
        for (a in anchors) {
            val (tmdb, mux) = parseCardHref(a.attr("href")) ?: continue
            if (!seen.add(tmdb)) continue
            val card = findCard(a)
            // title: card-wrapper data-title > movie-poster alt > any img alt
            val img = card?.selectFirst("img.movie-poster") ?: card?.selectFirst("img") ?: a.selectFirst("img")
            val title = card?.attr("data-title")?.takeIf { it.isNotBlank() }
                ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: continue
            val poster = img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
            val year = card?.attr("data-year")?.trim()?.toIntOrNull()
            val genres = card?.attr("data-genre")?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            out.add(CatalogItem(tmdb, mux, title, poster, year, genres))
        }
        return out
    }

    @Volatile
    private var catalogCache: Pair<Long, List<CatalogItem>>? = null

    /** Home + discover pages = the full catalog (mux ids are baked into the pages) */
    private suspend fun getCatalog(): List<CatalogItem> {
        catalogCache?.let { (ts, items) ->
            if (System.currentTimeMillis() - ts < 20 * 60 * 1000 && items.isNotEmpty()) return items
        }
        val items = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<Int>()
        for (page in listOf(SITE, "${SITE}discover.html")) {
            try {
                val html = app.get(page).text
                for (item in parseCatalogPage(html)) {
                    if (seen.add(item.tmdbId)) items.add(item)
                }
            } catch (_: Exception) {}
        }
        if (items.isNotEmpty()) catalogCache = System.currentTimeMillis() to items
        return items
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "home" to "كل الأفلام",
        "discover" to "اكتشف",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catalog = getCatalog()
        // light pseudo-pagination so the UI loads fast (25 per chunk)
        val chunkSize = 25
        val startIdx = ((page - 1) % 40) * chunkSize
        val chunk = catalog.drop(startIdx).take(chunkSize)

        val items = chunk.map { item ->
            newMovieSearchResponse(
                item.title,
                "cinemaplus://movie/${item.tmdbId}:${item.muxId}",
                TvType.Movie,
            ) {
                this.posterUrl = item.poster
                this.year = item.year
            }
        }
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        return getCatalog()
            .filter { it.title.lowercase().contains(q) }
            .map { item ->
                newMovieSearchResponse(
                    item.title,
                    "cinemaplus://movie/${item.tmdbId}:${item.muxId}",
                    TvType.Movie,
                ) {
                    this.posterUrl = item.poster
                    this.year = item.year
                }
            }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // url: cinemaplus://movie/{tmdbId}:{muxId}
        val payload = url.substringAfter("cinemaplus://", "")
        if (!payload.startsWith("movie/")) return null
        val body = payload.substringAfter("/")
        val tmdbId = body.substringBefore(":").trim().toIntOrNull() ?: return null
        val muxId = body.substringAfter(":", "").trim()
        if (muxId.length < 20) return null

        // metadata from TMDB direct (Arabic)
        val j = try {
            app.get(
                "$tmdbApi/movie/$tmdbId",
                params = mapOf("api_key" to tmdbKey, "language" to "ar"),
            ).text.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
        } catch (_: Exception) { null }

        val title = j?.str("title", "original_title") ?: "Movie $tmdbId"
        val poster = j?.str("poster_path")?.let { "$TMDB_IMG/w500$it" }
        val bgPoster = j?.str("backdrop_path")?.let { "$TMDB_IMG/w780$it" }
        val plot = j?.str("overview")
        val year = j?.str("release_date")?.take(4)?.toIntOrNull()
        val rating = j?.get("vote_average")?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        val tags = j?.get("genres")?.jsonArray
            ?.mapNotNull { it.jsonObject.str("name") }
            ?: emptyList()
        val runtime = j?.get("runtime")?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        return newMovieLoadResponse(title, url, TvType.Movie, "movie:$tmdbId:$muxId") {
            this.posterUrl = poster
            this.backgroundPosterUrl = bgPoster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(rating)
            this.duration = runtime
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // data: movie:{tmdbId}:{muxId}
        val body = data.substringAfterLast("/")
        val muxId = body.substringAfter(":", "").trim()
        if (muxId.length < 20) return false

        val master = "https://stream.mux.com/$muxId.m3u8"
        Log.i(TAG, "loadLinks mux=$muxId -> $master")

        // plain HLS — no ticket, no encryption; expand quality variants
        try {
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = master,
                referer = "",
            )
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
                return true
            }
        } catch (e: Exception) {
            Log.i(TAG, "generateM3u8 failed: ${e.message}")
        }

        // fallback: the master playlist itself
        callback(
            newExtractorLink(
                source = name,
                name = "Mux",
                url = master,
                type = ExtractorLinkType.M3U8,
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

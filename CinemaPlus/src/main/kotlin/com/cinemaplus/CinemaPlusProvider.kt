package com.cinemaplus

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Cinema Plus (cinema.plus app) — CloudStream provider.
 *
 * Architecture (from cinemaplus.har + live verification):
 * - The app is an AppCreator24 (e-droid) WebView shell.
 * - **config.php** lists ~627 content sections, one per movie/series:
 *     [s{pageId}_tipo=2][s{pageId}_tit={title}][s{pageId}_idgo={tmdbId}]...
 * - Each section is a static HTML page:
 *     GET https://html.e-droid.net/html/get_html.php?ida=3796813&ids={pageId}
 *     (requires User-Agent "Android Vinebre Software"!)
 *     - SERIES pages: const SERIES_ID = {tmdb}; const episodeLinks = {"s-e": "muxToken"|"fullUrl"|"#"}
 *     - MOVIE pages:  const MOVIE_ID = {tmdb}; const videoSources = {"480": "url"|"muxToken"}
 * - Playback: non-http values are Mux tokens -> https://stream.mux.com/{token}.m3u8
 *   (plain HLS, fMP4 renditions — ExoPlayer plays the master playlist natively)
 * - Movies catalog (with posters): https://ffrrx-2000.github.io/cinema-plas-bot/
 * - Metadata: TMDB direct (api.themoviedb.org), language=ar.
 */
class CinemaPlusProvider : MainAPI() {

    override var name = "Cinema Plus"
    override var mainUrl = SITE
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val TAG = "CinemaPlus"
        private const val SITE = "https://ffrrx-2000.github.io/cinema-plas-bot/"
        private const val CONFIG_URL =
            "https://config.e-droid.net/srv/config.php?v=211&vname=3.0&idapp=3796813&idusu=0&cod_g=&gp=0&am=0&idl=en&pa_env=1&pa=GB&pn=cinema.plus&fus=010100000000&aid=7db3574f7ef4288a"
        private const val EDOROID_UA = "Android Vinebre Software"
        private const val GET_HTML = "https://html.e-droid.net/html/get_html.php?ida=3796813&ids=%s"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_KEY = "06f120992cfacd7c118f6e7086d23544"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p"
        private val SKIP_TITLES = listOf("الرئيسية", "الرئيسيه", "اكتشف")
    }

    // ---------- helpers ----------

    private fun String.toDoc() = try { Jsoup.parse(this, SITE) } catch (_: Exception) { null }

    private fun kotlinx.serialization.json.JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (v != null && v != "null") return v
        }
        return null
    }

    private suspend fun tmdbJson(path: String): JsonObject? = try {
        app.get(
            "$TMDB_API$path",
            params = mapOf("api_key" to TMDB_KEY, "language" to "ar"),
        ).text.let { Json.parseToJsonElement(it).jsonObject }
    } catch (_: Exception) { null }

    private fun cleanTitle(t: String) = t.replace(Regex("\\s*logo$", RegexOption.IGNORE_CASE), "").trim()

    /** Mux token (non-http) -> stream.mux.com/{token}.m3u8 ; full URL -> as-is */
    private fun toStreamUrl(src: String): String =
        if (src.startsWith("http")) src else "https://stream.mux.com/$src.m3u8"

    // ---------- config (the content list: movies + series + anime) ----------

    private data class ConfigItem(val pageId: String, val title: String, val tmdbId: Int)

    @Volatile
    private var configCache: Pair<Long, List<ConfigItem>>? = null

    private suspend fun getConfig(): List<ConfigItem> {
        configCache?.let { (ts, items) ->
            if (System.currentTimeMillis() - ts < 60 * 60 * 1000 && items.isNotEmpty()) return items
        }
        val body = try {
            app.get(CONFIG_URL, headers = mapOf("User-Agent" to EDOROID_UA)).text
        } catch (_: Exception) { return emptyList() }

        val sections = LinkedHashMap<String, MutableMap<String, String>>()
        Regex("\\[s(\\d+)_(\\w+)=([^\\]]*)\\]").findAll(body).forEach { m ->
            sections.getOrPut(m.groupValues[1]) { mutableMapOf() }[m.groupValues[2]] = m.groupValues[3]
        }
        val items = sections.mapNotNull { (pageId, d) ->
            if (d["tipo"] != "2") return@mapNotNull null
            val title = d["tit"]?.trim().takeUnless { it.isNullOrBlank() } ?: return@mapNotNull null
            if (SKIP_TITLES.any { title.contains(it) }) return@mapNotNull null
            val tmdbId = d["idgo"]?.trim()?.toIntOrNull() ?: return@mapNotNull null
            ConfigItem(pageId, cleanTitle(title), tmdbId)
        }
        if (items.isNotEmpty()) configCache = System.currentTimeMillis() to items
        Log.i(TAG, "config: ${items.size} items")
        return items
    }

    // ---------- content page (get_html) ----------

    private suspend fun fetchContentPage(pageId: String): String? = try {
        app.get(GET_HTML.format(pageId), headers = mapOf("User-Agent" to EDOROID_UA)).text
    } catch (_: Exception) { null }

    /** SERIES page: const episodeLinks = {"s-e": "token"|"url"|"#"} */
    private fun parseEpisodeLinks(html: String): Map<String, String>? {
        val m = Regex("const\\s+episodeLinks\\s*=\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return Regex("\"(\\d+-\\d+)\":\\s*\"([^\"]*)\"").findAll(m.groupValues[1])
            .associate { it.groupValues[1] to it.groupValues[2] }
            .ifEmpty { null }
    }

    /** MOVIE page: const videoSources = {"quality": "url"|"token"} */
    private fun parseVideoSources(html: String): Map<String, String>? {
        val m = Regex("const\\s+videoSources\\s*=\\s*\\{(.*?)\\}", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return Regex("\"([^\"]+)\":\\s*\"([^\"]*)\"").findAll(m.groupValues[1])
            .mapNotNull { mr ->
                val v = mr.groupValues[2]
                if (v.isBlank()) null else mr.groupValues[1] to v
            }
            .toMap()
            .ifEmpty { null }
    }

    private fun isSeriesPage(html: String) = html.contains("const SERIES_ID") && parseEpisodeLinks(html) != null

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "movies" to "أفلام",
        "all" to "المحتوى الكامل (مسلسلات وأفلام)",
    )

    private suspend fun catalogFromGithub(): List<CatalogItem> {
        val items = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<Int>()
        for (page in listOf(SITE, "${SITE}discover.html")) {
            try {
                val doc = app.get(page).text.toDoc() ?: continue
                for (a in doc.select("a[href*=movie.html?tmdb=]")) {
                    val href = a.attr("href").substringAfterLast("/")
                    if (!href.startsWith("movie.html")) continue
                    val tmdb = href.substringAfter("tmdb=", "").substringBefore("&").trim().toIntOrNull() ?: continue
                    val mux = href.substringAfter("mux=", "").trim().takeWhile { it.isLetterOrDigit() }
                    if (mux.length < 20 || !seen.add(tmdb)) continue
                    var el: Element? = a
                    var card: Element? = null
                    repeat(5) {
                        el = el?.parent() ?: return@repeat
                        val cls = el?.className() ?: ""
                        if (cls.contains("card-wrapper") || cls.contains("movie-card")) { card = el; return@repeat }
                    }
                    val img = card?.selectFirst("img.movie-poster") ?: card?.selectFirst("img") ?: a.selectFirst("img")
                    val title = cleanTitle(
                        card?.attr("data-title")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                            ?: continue
                    )
                    items.add(CatalogItem(tmdb, mux, title, img?.attr("src")?.takeIf { it.isNotBlank() }))
                }
            } catch (_: Exception) {}
        }
        return items
    }

    private data class CatalogItem(val tmdbId: Int, val muxId: String, val title: String, val poster: String?)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val chunkSize = 24
        val startIdx = ((page - 1) % 50) * chunkSize

        if (request.data == "movies") {
            val catalog = catalogFromGithub()
            val chunk = catalog.drop(startIdx).take(chunkSize)
            val items = chunk.map { item ->
                newMovieSearchResponse(
                    item.title,
                    "cinemaplus://movie/${item.tmdbId}:${item.muxId}",
                    TvType.Movie,
                ) { this.posterUrl = item.poster }
            }
            return@coroutineScope newHomePageResponse(request.name, items)
        }

        // "all" — config-based content list, posters via TMDB (parallel)
        val config = getConfig()
        val chunk = config.drop(startIdx).take(chunkSize)
        val items = chunk.map { item ->
            async {
                val j = tmdbJson("/movie/${item.tmdbId}") ?: tmdbJson("/tv/${item.tmdbId}")
                val title = j?.str("title", "name", "original_title", "original_name") ?: item.title
                val isTv = j?.contains("seasons") == true || j?.str("name") != null && j.str("title") == null
                val poster = j?.str("poster_path")?.let { "$TMDB_IMG/w500$it" }
                val year = j?.str("release_date", "first_air_date")?.take(4)?.toIntOrNull()
                if (isTv) {
                    newTvSeriesSearchResponse(title, "cinemaplus://watch/${item.pageId}:${item.tmdbId}", TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                } else {
                    newMovieSearchResponse(title, "cinemaplus://watch/${item.pageId}:${item.tmdbId}", TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            }
        }.awaitAll()
        return@coroutineScope newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val matches = getConfig().filter { it.title.lowercase().contains(q) }.take(24)
        return coroutineScope {
            matches.map { item ->
                async {
                    val j = tmdbJson("/movie/${item.tmdbId}") ?: tmdbJson("/tv/${item.tmdbId}")
                    val title = j?.str("title", "name", "original_title", "original_name") ?: item.title
                    val isTv = j?.contains("seasons") == true || (j?.str("name") != null && j.str("title") == null)
                    val poster = j?.str("poster_path")?.let { "$TMDB_IMG/w500$it" }
                    val year = j?.str("release_date", "first_air_date")?.take(4)?.toIntOrNull()
                    if (isTv) {
                        newTvSeriesSearchResponse(title, "cinemaplus://watch/${item.pageId}:${item.tmdbId}", TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title, "cinemaplus://watch/${item.pageId}:${item.tmdbId}", TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // url: cinemaplus://watch/{pageId}:{tmdbId} | cinemaplus://movie/{tmdbId}:{muxId} (legacy)
        val payload = url.substringAfter("cinemaplus://", "")
        if (payload.startsWith("movie/")) {
            val body = payload.substringAfter("/")
            val tmdbId = body.substringBefore(":").trim().toIntOrNull() ?: return null
            val muxId = body.substringAfter(":", "").trim()
            return loadLegacyMovie(tmdbId, muxId)
        }
        if (!payload.startsWith("watch/")) return null
        val body = payload.substringAfter("/")
        val pageId = body.substringBefore(":").trim()
        val tmdbId = body.substringAfter(":", "").trim().toIntOrNull() ?: return null
        if (pageId.isBlank()) return null

        val html = fetchContentPage(pageId) ?: return null
        val j = tmdbJson("/tv/$tmdbId") ?: tmdbJson("/movie/$tmdbId") ?: return null

        val title = j.str("title", "name", "original_title", "original_name") ?: return null
        val poster = j.str("poster_path")?.let { "$TMDB_IMG/w500$it" }
        val bgPoster = j.str("backdrop_path")?.let { "$TMDB_IMG/w780$it" }
        val plot = j.str("overview")
        val year = j.str("release_date", "first_air_date")?.take(4)?.toIntOrNull()
        val rating = j["vote_average"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        val tags = j["genres"]?.jsonArray?.mapNotNull { it.jsonObject.str("name") } ?: emptyList()

        val episodeLinks = parseEpisodeLinks(html)
        if (episodeLinks != null) {
            // SERIES: episodes from TMDB seasons, stream source from episodeLinks["s-e"]
            val seasonsRaw = j["seasons"]?.jsonArray ?: return null
            val episodes = mutableListOf<Episode>()
            for (s in seasonsRaw) {
                val seasonNum = s.jsonObject["season_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                val seasonJson = tmdbJson("/tv/$tmdbId/season/$seasonNum") ?: continue
                for (e in seasonJson["episodes"]?.jsonArray ?: emptyList()) {
                    val ep = e.jsonObject
                    val epNum = ep["episode_number"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                    val src = episodeLinks["$seasonNum-$epNum"]
                    if (src == null || src == "#") continue   // coming soon / not available
                    episodes.add(
                        newEpisode(
                            url = "series:$pageId:$tmdbId:$seasonNum:$epNum",
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
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgPoster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
            }
        }

        // MOVIE page (videoSources)
        val sources = parseVideoSources(html) ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, "film:$pageId:$tmdbId") {
            this.posterUrl = poster
            this.backgroundPosterUrl = bgPoster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.score = Score.from10(rating)
            this.duration = j["runtime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        }
    }

    /** Legacy github-catalog movie: {tmdbId}:{muxId} */
    private suspend fun loadLegacyMovie(tmdbId: Int, muxId: String): LoadResponse? {
        val j = tmdbJson("/movie/$tmdbId") ?: return null
        val title = j.str("title", "original_title") ?: return null
        return newMovieLoadResponse(title, "cinemaplus://movie/$tmdbId:$muxId", TvType.Movie, "movie:$tmdbId:$muxId") {
            this.posterUrl = j.str("poster_path")?.let { "$TMDB_IMG/w500$it" }
            this.backgroundPosterUrl = j.str("backdrop_path")?.let { "$TMDB_IMG/w780$it" }
            this.plot = j.str("overview")
            this.year = j.str("release_date")?.take(4)?.toIntOrNull()
            this.tags = j["genres"]?.jsonArray?.mapNotNull { it.jsonObject.str("name") } ?: emptyList()
            this.score = Score.from10(j["vote_average"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull())
        }
    }

    // ---------- links ----------

    private suspend fun linkFor(src: String, label: String): ExtractorLink {
        val url = toStreamUrl(src)
        return newExtractorLink(
            source = name,
            name = label,
            url = url,
            type = if (url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            this.quality = Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = data.substringAfterLast("/")
        Log.i(TAG, "loadLinks data=$payload")

        // legacy github-catalog format: movie:{tmdbId}:{muxId}
        if (payload.startsWith("movie:")) {
            val muxId = payload.substringAfterLast(":", "").trim()
            if (muxId.length < 20) return false
            callback(linkFor(muxId, "Mux"))
            return true
        }

        // film:{pageId}:{tmdbId} — movie page videoSources
        if (payload.startsWith("film:")) {
            val parts = payload.split(":")
            val pageId = parts.getOrNull(1)?.trim() ?: return false
            val html = fetchContentPage(pageId) ?: return false
            val sources = parseVideoSources(html) ?: return false
            var found = false
            for ((quality, src) in sources) {
                try { callback(linkFor(src, "Mux $quality")); found = true } catch (_: Exception) {}
            }
            return found
        }

        // series:{pageId}:{tmdbId}:{s}:{e} — episodeLinks["s-e"]
        if (payload.startsWith("series:")) {
            val parts = payload.split(":")
            val pageId = parts.getOrNull(1)?.trim() ?: return false
            val s = parts.getOrNull(3)?.trim() ?: return false
            val eNum = parts.getOrNull(4)?.trim() ?: return false
            val html = fetchContentPage(pageId) ?: return false
            val links = parseEpisodeLinks(html) ?: return false
            val src = links["$s-$eNum"] ?: return false
            if (src == "#") return false
            callback(linkFor(src, "Mux"))
            return true
        }
        return false
    }
}

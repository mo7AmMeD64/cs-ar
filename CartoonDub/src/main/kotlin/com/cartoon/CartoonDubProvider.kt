package com.cartoon

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * CartoonDub (كرتون مدبلج, AppCreator24 idapp=4046384) — CloudStream provider.
 *
 * Architecture (from cartoondub.har + live verification):
 * - Sections API: https://srv11.e-droid.net/srv/obtener_cards.php?idusu={any}&ind_ini=0&idsec={id}
 *   (requires User-Agent "Android Vinebre Software", responses gzip)
 * - Card format (14 fixed fields, title may contain commas):
 *     ;{id},1,,{f},{n},{TITLE},{color},{size},{YEAR},{color2},{size2},{TARGET},{URL},0
 *     [11] target section id (0/FFFFFFFF = none)  -> navigation
 *     [12] URL (FFFFFFFF/empty = none)            -> direct MP4 (playable!)
 * - Video: public Cloudflare R2 bucket, direct MP4, no encryption/signature
 * - Card posters: https://imgs1.e-droid.net/srv/imgs/cards/o4046384_{cardId}.png
 * - NOTE: CloudStream fixUrl()s the response urls (prepends mainUrl) — the mangled
 *   url still contains "cartoondub://" so load()/loadLinks must check THAT first,
 *   NOT url.startsWith("http") (the mangled url would take the movie path -> 404).
 */
class CartoonDubProvider : MainAPI() {

    override var name = "كرتون مدبلج"
    override var mainUrl = "https://pub-b534f19ddae84293ae0e0fb360695fcf.r2.dev"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.Cartoon)

    companion object {
        private const val TAG = "CartoonDub"
        private const val CARDS_URL =
            "https://srv11.e-droid.net/srv/obtener_cards.php?idusu=1234567890&ind_ini=0&idsec=%s"
        private const val EDOROID_UA = "Android Vinebre Software"
        private const val CARD_IMG = "https://imgs1.e-droid.net/srv/imgs/cards/o4046384_%s.png"
        private val EMPTY_MARKERS = setOf("", "0", "FFFFFFFF")
    }

    // ---------- cards parsing ----------

    private data class Card(
        val id: String,
        val title: String,
        val year: String?,
        val target: String?,
        val url: String?,
    )

    private fun parseCards(body: String): List<Card> {
        if (!body.startsWith("ANDROID:OK")) return emptyList()
        val out = mutableListOf<Card>()
        val segments = body.substringAfter("DATOS:").split(";")
        for (seg in segments.drop(1)) {
            val t = seg.trim()
            if (t.isEmpty() || t == "N" || t.startsWith("ANDROID")) continue
            val f = t.split(",").toMutableList()
            while (f.isNotEmpty() && f.last().isBlank()) f.removeAt(f.size - 1)
            if (f.size < 14) continue
            // 8 fixed fields at the end: color,size,YEAR,color2,size2,TARGET,URL,flag
            val flag = f[f.size - 1].trim()
            val url = f[f.size - 2].trim()
            val target = f[f.size - 3].trim()
            val year = f[f.size - 6].trim()
            val title = f.subList(5, f.size - 8).joinToString(",").trim()
            if (title.isEmpty() && url.isBlank()) continue
            out.add(
                Card(
                    id = f[0].trim(),
                    title = title,
                    year = year.takeIf { it.isNotBlank() && it != "FFFFFFFF" },
                    target = target.takeUnless { it in EMPTY_MARKERS },
                    url = url.takeUnless { it in EMPTY_MARKERS },
                )
            )
        }
        return out
    }

    private suspend fun getCards(sectionId: String): List<Card> = try {
        val body = app.get(
            CARDS_URL.format(sectionId),
            headers = mapOf("User-Agent" to EDOROID_UA),
        ).text
        parseCards(body)
    } catch (e: Exception) {
        Log.i(TAG, "cards $sectionId err: ${e.message}")
        emptyList()
    }

    private fun posterFor(cardId: String?): String? =
        cardId?.takeIf { it.isNotBlank() && it != "FFFFFFFF" }?.let { CARD_IMG.format(it) }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "38314274" to "الرئيسية",
        "38314276" to "أنمي",
        "38314275" to "كرتون",
        "38314278" to "أفلام ومسلسلات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cards = getCards(request.data)
        val items = cards.map { c ->
            val isMovie = c.url != null
            newTvSeriesSearchResponse(
                c.title,
                if (isMovie) c.url!! else "cartoondub://sec/${c.target}:${c.title}",
                if (isMovie) TvType.Movie else TvType.TvSeries,
            ) {
                this.year = c.year?.toIntOrNull()
                this.posterUrl = posterFor(c.id)
            }
        }
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        for (section in listOf("38314274", "38314276", "38314275", "38314278")) {
            for (c in getCards(section)) {
                if (!c.title.lowercase().contains(q)) continue
                val isMovie = c.url != null
                val url = if (isMovie) c.url!! else "cartoondub://sec/${c.target}:${c.title}"
                if (!seen.add(url)) continue
                out.add(
                    newTvSeriesSearchResponse(
                        c.title, url,
                        if (isMovie) TvType.Movie else TvType.TvSeries,
                    ) {
                        this.year = c.year?.toIntOrNull()
                        this.posterUrl = posterFor(c.id)
                    }
                )
            }
        }
        return out
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // NOTE: the url may be mangled by CloudStream's fixUrl (mainUrl prepended);
        // check "cartoondub://" FIRST — the mangled url starts with http!
        if (!url.contains("cartoondub://")) {
            // a real direct mp4 (only when it is a genuine R2 url)
            if (!url.startsWith("http")) return null
            val title = java.net.URLDecoder.decode(url.substringAfterLast("/"), "UTF-8")
                .substringBeforeLast(".")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {}
        }

        val payload = url.substringAfter("cartoondub://", "")
        if (!payload.startsWith("sec/")) return null
        val sectionId = payload.substringAfter("/").substringBefore(":").trim()
        val fallbackTitle = payload.substringAfter(":", "").ifBlank { "قسم $sectionId" }
        if (sectionId.isBlank()) return null

        val cards = getCards(sectionId)
        if (cards.isEmpty()) return null

        val urlCards = cards.filter { it.url != null }
        if (urlCards.size == 1 && cards.size <= 2) {
            // a single playable card -> MOVIE
            val c = urlCards[0]
            return newMovieLoadResponse(c.title, url, TvType.Movie, c.url!!) {
                this.posterUrl = posterFor(c.id)
            }
        }
        if (urlCards.isNotEmpty()) {
            // this section IS a season (episodes)
            val seriesTitle = fallbackTitle.substringBefore(" الموسم").trim()
            val episodes = urlCards.mapIndexed { idx, c ->
                val epNum = Regex("(\\d+)").findAll(c.title).lastOrNull()?.value?.toIntOrNull() ?: (idx + 1)
                newEpisode(
                    url = c.url!!,
                    initializer = {
                        this.name = c.title
                        this.episode = epNum
                        this.season = 1
                        this.posterUrl = posterFor(c.id)
                    },
                    fix = false,
                )
            }
            return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {}
        }

        // cards with targets -> flatten: collect all sub-sections' episodes with seasons
        val episodes = mutableListOf<Episode>()
        for ((idx, c) in cards.withIndex()) {
            val target = c.target ?: continue
            val subCards = getCards(target)
            val subUrls = subCards.filter { it.url != null }
            if (subUrls.isNotEmpty()) {
                val seasonNum = idx + 1
                for (sub in subUrls) {
                    val epNum = Regex("(\\d+)").findAll(sub.title).lastOrNull()?.value?.toIntOrNull()
                    episodes.add(
                        newEpisode(
                            url = sub.url!!,
                            initializer = {
                                this.name = "${c.title} - ${sub.title}"
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = posterFor(sub.id)
                            },
                            fix = false,
                        )
                    )
                }
            } else {
                // deeper nesting: sub-section links to episode sections
                for (sub in subCards) {
                    val subTarget = sub.target ?: continue
                    val epCards = getCards(subTarget).filter { it.url != null }
                    for (ec in epCards) {
                        val epNum = Regex("(\\d+)").findAll(ec.title).lastOrNull()?.value?.toIntOrNull()
                        episodes.add(
                            newEpisode(
                                url = ec.url!!,
                                initializer = {
                                    this.name = "${c.title} - ${sub.title.ifBlank { ec.title }}"
                                    this.season = idx + 1
                                    this.episode = epNum
                                },
                                fix = false,
                            )
                        )
                    }
                }
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(fallbackTitle, url, TvType.TvSeries, episodes) {}
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // NOTE: the data may be the mangled load url (mainUrl + cartoondub://sec/...)
        // — check "cartoondub://" FIRST, not startsWith("http")!
        if (data.contains("cartoondub://")) return false   // a section url, not a link
        if (!data.startsWith("http")) return false
        Log.i(TAG, "loadLinks $data")
        callback(
            newExtractorLink(
                source = name,
                name = "R2",
                url = data,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}

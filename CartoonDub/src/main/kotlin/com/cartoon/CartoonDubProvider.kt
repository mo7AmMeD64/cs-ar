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
 * - Card format: ;{id},1,,{f},{n},{TITLE},{color},{size},{YEAR},{color2},{size2},{TARGET},{URL},0,
 *     [11] target section id (0/FFFFFFFF = none)  -> navigation
 *     [12] URL (FFFFFFFF/empty = none)            -> direct MP4 (playable!)
 * - Video: public Cloudflare R2 bucket, direct MP4, no encryption/signature:
 *     https://pub-b534f19ddae84293ae0e0fb360695fcf.r2.dev/ben/بن 10 الحلقة 1 مدبلجة.mp4
 * - Hierarchy: 38314274 (الرئيسية) → series → seasons → episodes
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
        // segments after the DATOS header (the first is the section's own card)
        val segments = body.substringAfter("DATOS:").split(";")
        for (seg in segments.drop(1)) {
            val t = seg.trim()
            if (t.isEmpty() || t == "N" || t.startsWith("ANDROID")) continue
            val f = t.split(",")
            if (f.size < 12) continue
            // parse from the END (titles may contain commas)
            val flag = f[f.size - 2].trim()          // last "0"
            val url = f[f.size - 3].trim()           // URL or FFFFFFFF/empty
            val target = f[f.size - 4].trim()        // target section or 0/FFFFFFFF
            val title = f.subList(5, f.size - 5).joinToString(",").trim()
            if (title.isEmpty() && url.isBlank()) continue
            out.add(
                Card(
                    id = f[0].trim(),
                    title = title,
                    year = f.getOrNull(8)?.trim()?.takeIf { it.isNotBlank() && it != "FFFFFFFF" },
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
            val type = if (c.url != null) TvType.Movie else TvType.TvSeries
            newTvSeriesSearchResponse(
                c.title,
                if (c.url != null) c.url else "cartoondub://sec/${c.target}:${c.title}",
                type,
            ) {
                this.year = c.year?.toIntOrNull()
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
                val url = if (c.url != null) c.url else "cartoondub://sec/${c.target}:${c.title}"
                if (!seen.add(url)) continue
                out.add(
                    newTvSeriesSearchResponse(
                        c.title, url,
                        if (c.url != null) TvType.Movie else TvType.TvSeries,
                    ) { this.year = c.year?.toIntOrNull() }
                )
            }
        }
        return out
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // url: cartoondub://sec/{sectionId}:{title}  |  a direct mp4 URL (movie)
        if (url.startsWith("http")) {
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

        // any card with a URL -> this section IS a season (episodes)
        val urlCards = cards.filter { it.url != null }
        if (urlCards.isNotEmpty()) {
            val seriesTitle = fallbackTitle.substringBefore(" الموسم").trim()
            val episodes = urlCards.mapIndexed { idx, c ->
                val epNum = Regex("(\\d+)").findAll(c.title).lastOrNull()?.value?.toIntOrNull() ?: (idx + 1)
                newEpisode(
                    url = c.url!!,
                    initializer = {
                        this.name = c.title
                        this.episode = epNum
                        this.season = 1
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
                // this sub-section is a season of the series
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
        // data IS the direct R2 MP4 URL
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

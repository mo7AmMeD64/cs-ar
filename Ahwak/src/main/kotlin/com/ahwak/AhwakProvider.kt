package com.ahwak

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup

/**
 * اهواك تي في (Ahwak TV, yam.ahwaktv.net) — CloudStream provider.
 *
 * Site (from yam.ahwaktv.net HAR capture + live verification):
 * - A PHP video site (Melody/PM template):
 *   - the home: /            -> the cards <a href="watch.php?vid={id}" title="...">
 *   - the series list: /moslslat.php -> <a href="view-serie.php?name={slug}" title="...">
 *   - the search: /search.php?keywords={query}
 *   - the live search: POST /ajax-search.php (queryString={q}) -> <li data-video-id>
 * - The watch page: /watch.php?vid={id} (the video info + the view-serie link for the series)
 * - **see.php?vid={id} = THE SERVERS PAGE**: all the hoster embeds as iframes/links
 *   (1vid.xyz, vidspeed.org, uqload.is, vidhideplus.com, vk.com/video_ext.php)
 * - The hosters resolve to the m3u8s (audinifer.com/stream/{token}/.../master.m3u8,
 *   1vid.online/hls2/.../master.m3u8?t=...) -> loadExtractor
 * - The securimage captcha = the REPORT form only (not the player!)
 */
class AhwakProvider : MainAPI() {

    override var name = "اهواك تي في"
    override var mainUrl = "https://yam.ahwaktv.net"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)

    companion object {
        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
    }

    private suspend fun get(url: String): String? = try {
        val res = app.get(url, headers = mapOf("User-Agent" to UA, "Referer" to "$mainUrl/"))
        if (res.code != 200) null else res.text
    } catch (_: Exception) {
        null
    }

    private fun vidOf(url: String): String? =
        Regex("vid=([a-f0-9]+)").find(url)?.groupValues?.get(1)

    /** the watch.php cards: <a href="watch.php?vid=..." title="..."><img src/alt> */
    private fun cardsFrom(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href*=\"watch.php?vid=\"]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank {
                a.selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
            }
            if (title.isBlank()) return@mapNotNull null
            val poster = a.selectFirst("img")?.let { im ->
                im.attr("data-echo").ifBlank { im.attr("data-src").ifBlank { im.attr("src") } }
            }
            newTvSeriesSearchResponse(title, "ahwak://watch/$href", TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    /** the series cards: view-serie.php?name={slug} */
    private fun seriesFrom(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        return doc.select("a[href*=\"view-serie.php?name=\"]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val title = a.attr("title").trim().ifBlank {
                a.selectFirst("img")?.attr("alt")?.trim() ?: a.text().trim()
            }
            if (title.isBlank()) return@mapNotNull null
            val poster = a.selectFirst("img")?.let { im ->
                im.attr("data-echo").ifBlank { im.attr("data-src").ifBlank { im.attr("src") } }
            }
            newTvSeriesSearchResponse(title, "ahwak://serie/$href", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "/" to "الأحدث",
        "moslslat.php" to "مسلسلات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = get(mainUrl + request.data) ?: return newHomePageResponse(request.name, emptyList())
        val items = if (request.data.contains("moslslat")) {
            seriesFrom(html)
        } else {
            cardsFrom(html)
        }
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val html = get(
            "$mainUrl/search.php?keywords=${java.net.URLEncoder.encode(q, "UTF-8")}&video-id=",
        ) ?: return emptyList()
        return cardsFrom(html) + seriesFrom(html)
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // NOTE: the url may be mangled by CloudStream's fixUrl; check "ahwak://" first
        if (!url.contains("ahwak://")) return null
        val postUrl = url.substringAfter("ahwak://", "").trim()
        if (!postUrl.startsWith("http")) return null

        return when {
            postUrl.contains("view-serie.php") -> loadSerie(postUrl)
            postUrl.contains("watch.php") -> loadWatch(postUrl)
            else -> null
        }
    }

    /** the series page: the episodes */
    private suspend fun loadSerie(serieUrl: String): LoadResponse? {
        val html = get(serieUrl) ?: return null
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: java.net.URLDecoder.decode(serieUrl.substringAfter("name="), "UTF-8").replace("-", " ")
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = doc.selectFirst("meta[name=\"description\"]")?.attr("content")

        // the episodes: <a href="watch.php?vid={id}" title="...">
        val epCards = doc.select("a[href*=\"watch.php?vid=\"]")
        if (epCards.isEmpty()) return null
        val episodes = epCards.mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) return@mapNotNull null
            val epTitle = a.attr("title").trim()
            val epNum = Regex("الحلقة\\s*(\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("الحلقة\\s*ال(.+?)\\s").find(epTitle)?.let { ordinalOf(it.groupValues[1]) }
            newEpisode(
                url = "ahwak://watch/$href",
                initializer = {
                    this.name = epTitle.ifBlank { "الحلقة ${epNum ?: ""}" }
                    this.episode = epNum
                    this.posterUrl = a.selectFirst("img")?.let { im ->
                        im.attr("data-echo").ifBlank { im.attr("src") }
                    }
                },
                fix = false,
            )
        }
        return newTvSeriesLoadResponse(title, serieUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun ordinalOf(word: String): Int? {
        val ordinals = mapOf(
            "ولي" to 1, "الثانية" to 2, "التانية" to 2, "الثالثة" to 3, "التالتة" to 3,
            "الرابعة" to 4, "الرابعه" to 4, "الخامسة" to 5, "الخامسه" to 5,
            "السادسة" to 6, "السادسه" to 6, "السابعة" to 7, "السابعه" to 7,
            "الثامنة" to 8, "الثامنه" to 8, "التاسعة" to 9, "التاسعه" to 9,
        )
        return ordinals[word.trim()]
    }

    /** the watch page: the movie (the servers via see.php) or the series fallback */
    private suspend fun loadWatch(watchUrl: String): LoadResponse? {
        val html = get(watchUrl) ?: return null
        val doc = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[name=\"title\"]")?.attr("content")?.trim()
            ?: java.net.URLDecoder.decode(watchUrl.substringAfter("vid="), "UTF-8")
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
        val plot = doc.selectFirst("meta[property=\"og:description\"]")?.attr("content")
        val vid = vidOf(watchUrl)

        // the series? (the view-serie link on the watch page)
        val serieLink = doc.selectFirst("a[href*=\"view-serie.php?name=\"]")?.let {
            it.absUrl("href").ifBlank { it.attr("href") }
        }
        if (serieLink != null) {
            val serie = loadSerie(serieLink)
            if (serie != null) return serie
        }
        // a movie: the servers via see.php
        return newMovieLoadResponse(title, watchUrl, TvType.Movie, "ahwak://see/$vid") {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // NOTE: the data may be the mangled load url; check "ahwak://" FIRST
        if (data.contains("ahwak://see/") || data.contains("ahwak://watch/")) {
            val postUrl = data.substringAfter("ahwak://", "").trim()
            var vid = vidOf(postUrl)
            if (vid == null && postUrl.startsWith("http")) {
                // the watch url -> the see.php has the same vid
                vid = vidOf(postUrl)
            }
            if (vid == null) return false
            val seeUrl = "$mainUrl/see.php?vid=$vid"
            val html = get(seeUrl) ?: return false
            val doc = Jsoup.parse(html)
            val links = doc.select("iframe[src]").map { it.attr("src").trim() } +
                doc.select("a[href]").mapNotNull { a ->
                    val h = a.attr("href").trim()
                    h.takeIf { it.startsWith("http") && Regex("(embed|/e/|video_ext)").containsMatchIn(it) }
                }
            var got = false
            for (l in links.distinct()) {
                if (l.isBlank() || !l.startsWith("http")) continue
                got = try {
                    loadExtractor(l, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)
                } catch (_: Exception) {
                    false
                } || got
            }
            return got
        }
        if (data.startsWith("http")) {
            return try {
                loadExtractor(data, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)
            } catch (_: Exception) {
                false
            }
        }
        return false
    }
}

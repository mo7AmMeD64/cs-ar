package com.arabseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * ArabSeed / ماي سيد (m.myseed.pics) — CloudStream provider.
 *
 * Site (from m.myseed.pics HAR capture + live verification):
 * - WordPress site, each post = a movie or a series episode page
 * - Cards: <a href="{post}" title="{title}" class="movie__block"><img data-src="{poster}">
 *   on main/ (مضاف حديثاً), movies/, series/
 * - Search: POST find__posts/ with search={query} + csrf_token — the csrf comes from
 *   any page's inline script (csrf__token: "...") — the home page HAS it
 * - Watch page = {post}/watch/ :
 *     servers in data-link attributes: <a data-post data-server data-qu data-link="{hoster url}">
 *     episodes list: <ul class="episodes__list"><a href="{episode post}"><div class="epi__num">الحلقة N</div>
 * - vid/?id={base64} links wrap a hoster url (base64-decode -> the direct hoster embed)
 * - Hoster embeds (vtube.to, ok.ru, vidara.to, bysezejataos...) -> loadExtractor
 * - /d/{hash}/video.mp4 = the direct download (referer d.myseed.tv)
 */
class ArabSeedProvider : MainAPI() {

    override var name = "عرب سيد"
    override var mainUrl = "https://m.myseed.pics"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)

    companion object {
        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }

    private suspend fun get(url: String) = try {
        val res = app.get(url, headers = mapOf("User-Agent" to UA))
        if (res.code != 200) null else res.text
    } catch (_: Exception) {
        null
    }

    /** the csrf token from any page's inline script */
    private fun csrfOf(html: String?): String? =
        html?.let { Regex("csrf__token[\"':\\s]+([a-f0-9]{6,})").find(it)?.groupValues?.get(1) }

    private data class Card(val url: String, val title: String, val poster: String?)

    private fun cardsFrom(html: String?): List<Card> {
        if (html == null) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("a[title]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            val title = a.attr("title").trim()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            // only content posts (فيلم- / مسلسل- / انمي- / عرض-)
            val decoded = java.net.URLDecoder.decode(href, "UTF-8")
            if (Regex("(فيلم|مسلسل|انمي|anime|movie|serie)-").find(decoded.substringAfter("m.myseed.pics/")) == null) return@mapNotNull null
            if (decoded.containsAny("/watch", "#", "?")) return@mapNotNull null
            val img = a.selectFirst("img")?.attr("data-src")?.ifBlank { a.selectFirst("img")?.attr("src") }
            Card(href, title, img)
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    private fun isMovieUrl(url: String): Boolean =
        java.net.URLDecoder.decode(url, "UTF-8").contains("فيلم-")

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "main/" to "مضاف حديثاً",
        "movies/" to "أفلام",
        "series/" to "مسلسلات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cards = cardsFrom(get("$mainUrl/${request.data}"))
        val items = cards.map { c ->
            val isMovie = isMovieUrl(c.url)
            newTvSeriesSearchResponse(
                c.title,
                "arabseed://post/${c.url}",
                if (isMovie) TvType.Movie else TvType.TvSeries,
            ) {
                this.posterUrl = c.poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val homeHtml = get("$mainUrl/main/") ?: return emptyList()
        val csrf = csrfOf(homeHtml) ?: return emptyList()
        val res = try {
            app.post(
                "$mainUrl/find__posts/",
                headers = mapOf(
                    "User-Agent" to UA,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to "$mainUrl/",
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                ),
                data = mapOf("search" to q, "search_type" to "", "csrf_token" to csrf),
            ).text
        } catch (_: Exception) {
            return emptyList()
        }
        // {"type":"success","html":"<ul class="res__ul">..."}
        val htmlStr = Regex("\"html\":\"(.*)\"").find(res)?.groupValues?.get(1) ?: return emptyList()
        val unescaped = htmlStr.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "\n")
        val doc = Jsoup.parse(unescaped)
        return doc.select("a.search__item").mapNotNull { a ->
            val href = a.attr("href").ifBlank { a.absUrl("href") }
            val title = a.attr("title").ifBlank {
                a.selectFirst("img")?.attr("alt") ?: a.text().trim()
            }
            if (href.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")?.attr("data-src")?.ifBlank { a.selectFirst("img")?.attr("src") }
            newTvSeriesSearchResponse(
                title.trim(),
                "arabseed://post/$href",
                if (isMovieUrl(href)) TvType.Movie else TvType.TvSeries,
            ) {
                this.posterUrl = img
            }
        }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // NOTE: the url may be mangled by CloudStream's fixUrl; check "arabseed://" first
        if (!url.contains("arabseed://")) return null
        val postUrl = url.substringAfter("arabseed://post/", "").trim()
        if (!postUrl.startsWith("http")) return null

        val watchUrl = postUrl.trimEnd('/') + "/watch/"
        val html = get(watchUrl) ?: return null
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst(".as-hero-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: java.net.URLDecoder.decode(postUrl.substringAfterLast("/"), "UTF-8")
                .substringBeforeLast("-").replace("-", " ")
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst(".as-hero-image")?.attr("src")
        val plot = doc.selectFirst(".as-hero-story")?.text()?.trim()

        // the episodes list (series pages)
        val epItems = doc.select(".episodes__list a[href]").mapNotNull { a ->
            val href = a.attr("href").ifBlank { a.absUrl("href") }
            if (href.isBlank()) return@mapNotNull null
            val epNumText = a.selectFirst(".epi__num")?.text()?.trim() ?: ""
            val epNum = Regex("(\\d+)").find(epNumText)?.value?.toIntOrNull()
            href to epNum
        }

        if (epItems.isEmpty()) {
            // a movie: the servers live on this page, loadLinks fetches the watch page
            return newMovieLoadResponse(title, url, TvType.Movie, "arabseed://post/$postUrl") {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        // a series (this post is one of the series' episodes)
        val seriesTitle = title.substringBefore(" الحلقة").trim().ifBlank { title }
        // fetch all the episodes' watch pages is too heavy here; the episodes' data
        // = the post urls, loadLinks resolves each on play
        val episodes = epItems.map { (href, epNum) ->
            newEpisode(
                url = "arabseed://post/$href",
                initializer = {
                    this.name = "الحلقة ${epNum ?: ""}".trim()
                    this.episode = epNum
                },
                fix = false,
            )
        }
        return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
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
        if (data.contains("arabseed://post/")) {
            val postUrl = data.substringAfter("arabseed://post/").trim()
            if (!postUrl.startsWith("http")) return false
            val watchUrl = postUrl.trimEnd('/') + "/watch/"
            val html = get(watchUrl) ?: return false
            val doc = Jsoup.parse(html)
            val links = doc.select("[data-link]").map { it.attr("data-link") }
            var got = false
            for (l in links) {
                val link = l.trim()
                if (link.isBlank()) continue
                got = resolveLink(link, subtitleCallback, callback) || got
            }
            return got
        }
        if (data.startsWith("http")) {
            return resolveLink(data, subtitleCallback, callback)
        }
        return false
    }

    /** one data-link: vid/?id={base64} -> decode; else the hoster embed -> loadExtractor */
    private suspend fun resolveLink(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val url = if (link.contains("/vid/?id=")) {
                val b64 = link.substringAfter("id=").substringBefore("&").trim()
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            } else link
            if (!url.startsWith("http")) return false
            loadExtractor(url, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)
        } catch (_: Exception) {
            false
        }
    }
}

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
        val html = get(mainUrl.trimEnd('/') + "/" + request.data.trimStart('/')) ?: return newHomePageResponse(request.name, emptyList())
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
        // the mangled form keeps the payload prefix: ahwak://watch/https://... -> "watch/https://..."
        val raw = url.substringAfter("ahwak://", "").trim()
        val postUrl = if (raw.startsWith("http")) raw else {
            raw.substringAfter("watch/", "").ifBlank { raw.substringAfter("serie/", "") }.trim()
        }
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
            val vid = vidOf(postUrl) ?: return false
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
                // 1. the packed-eval hosters (the vidhide family: 1vid/vidspeed/uqload/vidhideplus):
                //    fetch the embed page, unpack the packed eval, take the m3u8 directly
                val packed = get(l) ?: ""
                val direct = extractPackedM3u8(packed)
                if (direct != null) {
                    // verify first: the 1vid embeds are often dead CDN-side (404
                    // even in the original app) — don't emit dead links
                    try {
                        val vr = app.head(direct, headers = mapOf("User-Agent" to UA, "Referer" to "$l/"))
                        if (vr.code in 200..299) {
                            callback(
                                newExtractorLink(name, "اهواك تي في", direct, ExtractorLinkType.M3U8) {
                                    this.referer = l.substringBeforeLast("/")
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            got = true
                            continue
                        }
                    } catch (_: Exception) {}
                }
                // 2. the VK embeds + anything else -> the universal extractor
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

    /**
     * The vidhide-family embeds pack the player js in an eval(p,a,c,k,e,d):
     * the m3u8 url (the 'file'/'sources' key) is inside. Unpack it and
     * return the first m3u8/mp4 found (from Krmzy's deobfuscation).
     */
    private fun extractPackedM3u8(pageText: String): String? {
        val evalRegex = Regex("""eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""")
        val paramsString = evalRegex.find(pageText)?.groupValues?.getOrNull(1) ?: return null
        val paramsRegex = Regex("""['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""")
        val pm = paramsRegex.find(paramsString) ?: return null
        val (packedCode, baseStr, countStr, dictStr) = pm.destructured
        val base = baseStr.toIntOrNull() ?: return null
        val count = countStr.toIntOrNull() ?: return null
        val keywords = dictStr.split('|')

        fun toBase(num: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % radix])
                n /= radix
            }
            return sb.reverse().toString()
        }
        val replaceMap = mutableMapOf<String, String>()
        for (i in 0 until count) {
            val keyword = keywords.getOrNull(i)
            if (!keyword.isNullOrEmpty()) replaceMap[toBase(i, base)] = keyword
        }
        val unpacked = Regex("""\b\w+\b""").replace(packedCode) { mr ->
            replaceMap[mr.value] ?: mr.value
        }
        // the m3u8/mp4 urls (with the query tokens)
        return Regex("""(https?://[^"'\s]*\.(?:m3u8|mp4)[^"'\s]*)""").find(unpacked)?.groupValues?.get(1)
    }
}

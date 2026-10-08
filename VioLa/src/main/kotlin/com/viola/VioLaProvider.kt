package com.viola

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

/**
 * Vio-La (hq.vio-la.com) — plain PHP Motion template (watch.php/play.php).
 *
 * From the caputre.har + live verification:
 * - Catalog: movies.php / all-series.php / episodes.php / category.php?cat=.. / topvideos.php (?page=N)
 * - Search:  search.php?keywords={q}&video-id=
 * - watch.php?vid=ID: og:title/image/description + SeasonsBox (series seasons->episodes) + play link
 * - play.php?vid=ID: <li data-embed> server list (ok.ru / vkvideo / forafile / 1vid / liiivideo ...)
 *
 * Server resolvers (all live-verified):
 * - ok.ru/videoembed/{id}: page embeds JSON -> videos[mobile/lowest/low/sd/hd] + hlsManifestUrl
 * - vkvideo.ru/video_ext.php?oid=&id=: POST login.vk.ru?act=get_anonym_token ->
 *   POST api.vkvideo.ru/method/video.get -> files{mp4_144..720, hls}
 * - forafile/1vid.xyz/liiivideo (and any embed with packed-eval): dean.edwards unpack -> jwplayer file
 * - 71stream.one / ult4vid.one: mawdhou3.com scrapers (71stream.php / ult4vid.php ?api=)
 * - rubyvidhub: origin dead (CF 522) — skipped. youtube embeds: skipped.
 */
class VioLaProvider : MainAPI() {

    override var name = "Vio-La"
    override var mainUrl = "https://hq.vio-la.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private val HDRS = mapOf("User-Agent" to CHROME_UA)

        // VK anonymous video token flow (from the HAR: login.vk.ru?act=get_anonym_token)
        private const val VK_CLIENT_ID = "52461373"
        private const val VK_SECRET = "o557NLIkAErNhakXrQ7A"
        private const val VK_APP_ID = "6287487"
        @Volatile private var vkToken: String? = null

        private val json = Json { ignoreUnknownKeys = true }
    }

    // ---------- helpers ----------

    private fun vidOf(u: String): String? =
        Regex("vid=([a-z0-9]+)").find(u)?.groupValues?.getOrNull(1)

    /** the video cards: <a href="...watch.php?vid=ID" title="TITLE"> with an <img> inside */
    private fun parseCards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        return doc.select("a[href*='watch.php?vid=']").mapNotNull { a ->
            val vid = vidOf(a.attr("href")) ?: return@mapNotNull null
            val title = a.attr("title").takeIf { it.isNotBlank() } ?: a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            val poster = img?.attr("data-echo")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("templates") }
            newMovieSearchResponse(title, "viola://w/$vid", TvType.Movie) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }
    }

    // ---------- catalog ----------

    override val mainPage = mainPageOf(
        "viola://page/index.php" to "الرئيسية",
        "viola://page/movies.php" to "أفلام",
        "viola://page/all-series.php" to "مسلسلات",
        "viola://page/episodes.php" to "أحدث الحلقات",
        "viola://page/topvideos.php" to "الأكثر مشاهدة",
        "viola://page/category.php?cat=aflam" to "أفلام أجنبية",
        "viola://page/category.php?cat=aflamarabic" to "أفلام عربية",
        "viola://page/category.php?cat=moslslatarabic" to "مسلسلات عربية",
        "viola://page/category.php?cat=animemotrjm" to "أنمي مترجم",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.removePrefix("viola://page/")
        val sep = if (path.contains("?")) "&" else "?"
        val url = "$mainUrl/$path" + if (page > 1) "${sep}page=$page" else ""
        val items = try {
            parseCards(app.get(url, headers = HDRS).text)
        } catch (_: Exception) {
            emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return try {
            parseCards(app.get("$mainUrl/search.php?keywords=${Uri.encode(q)}&video-id=", headers = HDRS).text)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("viola://")) return null
        val vid = url.substringAfter("viola://w/", "").substringBefore("/").trim()
        if (vid.isBlank()) return null

        val html = app.get("$mainUrl/watch.php?vid=$vid", headers = HDRS).text
        val title = Regex("""property="og:title" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: return null
        val poster = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val plot = Regex("""property="og:description" content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        val year = Regex("""datePublished" content="(\d{4})""").find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // series page: SeasonsBox with season tabs -> episode links
        if (html.contains("SeasonsBox")) {
            val doc = Jsoup.parse(html, mainUrl)
            val episodes = mutableListOf<Episode>()
            doc.select("div[id^=Season]").forEachIndexed { sIdx, tab ->
                val season = sIdx + 1
                tab.select("a[href*='watch.php?vid=']").forEach { a ->
                    val epid = vidOf(a.attr("href")) ?: return@forEach
                    val t = a.attr("title").ifBlank { a.text().trim() }
                    val epNum = Regex("الحلقة\\s*([0-9\u0660-\u0669]+)")
                        .find(t)?.groupValues?.getOrNull(1)
                        ?.map { ch -> if (ch in '\u0660'..'\u0669') ('0' + (ch - '\u0660')) else ch }
                        ?.joinToString("")?.toIntOrNull()
                        ?: (episodes.count { it.season == season } + 1)
                    episodes.add(
                        newEpisode(
                            url = "viola://p/$epid",
                            initializer = {
                                this.name = t
                                this.season = season
                                this.episode = epNum
                            },
                            fix = false,
                        )
                    )
                }
            }
            if (episodes.isEmpty()) return null
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        // movie / episode page -> the play link carries the servers
        val playVid = vidOf(Regex("play\\.php\\?vid=[a-z0-9]+").find(html)?.value ?: "") ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, "viola://p/$playVid") {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.contains("viola://p/")) return false
        val vid = data.substringAfter("viola://p/", "").substringBefore("/").trim()
        if (vid.isBlank()) return false

        val html = app.get("$mainUrl/play.php?vid=$vid", headers = HDRS).text
        val doc = Jsoup.parse(html, mainUrl)
        val servers = doc.select("li[data-embed]").mapNotNull { li ->
            val m = Regex("src='([^']+)'").find(li.attr("data-embed")) ?: return@mapNotNull null
            val label = li.text().trim().ifBlank { Uri.parse(m.groupValues[1]).host ?: "" }
            label to m.groupValues[1]
        }.distinctBy { it.second }
        if (servers.isEmpty()) return false

        var got = false
        for ((label, link) in servers) {
            try {
                when {
                    link.contains("youtube.com") -> { /* skip */ }

                    // OK.ru: the embed page carries the videos JSON + HLS manifest inline
                    link.contains("ok.ru") -> {
                        resolveOk(link)?.forEach { (u, q) ->
                            emit(callback, u, "$label $q".trim()); got = true
                        }
                    }

                    // VK: anonymous token -> video.get -> mp4_144..720 + hls
                    link.contains("vkvideo.ru") || link.contains("vk.com/video_ext") -> {
                        resolveVk(link)?.forEach { (u, q) ->
                            emit(callback, u, "$label $q".trim()); got = true
                        }
                    }

                    else -> {
                        // native extractors first (dood, vidhide...)
                        if (loadExtractor(link, "$mainUrl/", subtitleCallback, callback)) {
                            got = true
                            continue
                        }
                        // mawdhou3 scrapers (71stream / ult4vid)
                        val scraped = when {
                            link.contains("71stream") -> scrape("https://mawdhou3.com/scrapefinal/71stream.php?api=", link)
                            link.contains("ult4vid") -> scrape("https://mawdhou3.com/scrapefinal/ult4vid.php?api=", link)
                            else -> null
                        }
                        if (scraped != null) {
                            scraped.forEach { (u, q) -> emit(callback, u, "$label $q".trim()); got = true }
                            continue
                        }
                        // generic packed-eval family (forafile, 1vid, liiivideo, ...)
                        val page = app.get(link, headers = mapOf("User-Agent" to CHROME_UA, "Referer" to "$mainUrl/")).text
                        val deob = unpack(page)
                        val file = deob?.let {
                            Regex("""["']?file["']?\s*[:=]\s*["'](https?[^"']+)["']""").find(it)?.groupValues?.getOrNull(1)
                        }
                        if (file != null) {
                            emit(callback, file, label); got = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return got
    }

    private suspend fun emit(callback: suspend (ExtractorLink) -> Unit, url: String, label: String) {
        // ambiguous URLs (no extension) get a HEAD content-type probe
        val type = when {
            url.contains(".m3u8") || url.contains("/hls/") || url.contains("playlist.m3u8") ||
                url.contains("videoPlayerCdn") -> ExtractorLinkType.M3U8
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            else -> {
                val ct = try {
                    app.head(url, headers = mapOf("User-Agent" to CHROME_UA)).headers["content-type"] ?: ""
                } catch (_: Exception) { "" }
                if (ct.contains("mpegurl")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
        }
        callback(
            newExtractorLink(name, label.ifBlank { name }, url, type) {
                this.headers = mapOf("User-Agent" to CHROME_UA)
                this.quality = Qualities.Unknown.value
            }
        )
    }

    // ---------- server resolvers ----------

    /** ok.ru/videoembed/{id} -> html-escaped JSON with videos[] + hlsManifestUrl */
    private suspend fun resolveOk(link: String): List<Pair<String, String>>? {
        val t = app.get(link, headers = mapOf("User-Agent" to CHROME_UA)).text
        if (t.contains("copyrightsRestricted")) return null
        val dec = t.replace("&quot;", "\"").replace("\\u0026", "&")
        val out = mutableListOf<Pair<String, String>>()
        Regex(""""name"\s*:\s*"([^"]*)"\s*,\s*"url"\s*:\s*"(https?[^"]+)"""").findAll(dec).forEach {
            out.add(it.groupValues[2] to it.groupValues[1])
        }
        Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)"""").find(dec)?.groupValues?.getOrNull(1)?.let {
            out.add(it to "HLS")
        }
        return out.ifEmpty { null }
    }

    /** vkvideo.ru/video_ext.php?oid=..&id=.. -> anon token -> video.get -> files{} */
    private suspend fun resolveVk(link: String): List<Pair<String, String>>? {
        val oid = Regex("oid=(-?\\d+)").find(link)?.groupValues?.getOrNull(1) ?: return null
        val id = Regex("[?&]id=(\\d+)").find(link)?.groupValues?.getOrNull(1) ?: return null

        if (vkToken == null) {
            val tokResp = app.post(
                "https://login.vk.ru?act=get_anonym_token",
                headers = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to "https://vkvideo.ru",
                    "Referer" to "https://vkvideo.ru/",
                ),
                data = mapOf(
                    "client_secret" to VK_SECRET,
                    "client_id" to VK_CLIENT_ID,
                    "scopes" to "audio_anonymous,video_anonymous,photos_anonymous,profile_anonymous",
                    "isApiOauthAnonymEnabled" to "false",
                    "version" to "1",
                    "app_id" to VK_APP_ID,
                ),
            ).text
            vkToken = try {
                json.parseToJsonElement(tokResp).jsonObject["data"]?.jsonObject?.get("access_token")?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            } ?: return null
        }

        val body = app.post(
            "https://api.vkvideo.ru/method/video.get?v=5.289&client_id=$VK_CLIENT_ID",
            headers = mapOf(
                "User-Agent" to CHROME_UA,
                "Content-Type" to "application/x-www-form-urlencoded",
                "Origin" to "https://vkvideo.ru",
                "Referer" to "https://vkvideo.ru/",
            ),
            data = mapOf(
                "owner_id" to "",
                "videos" to "${oid}_$id",
                "extended" to "0",
                "is_embed" to "true",
                "track_code" to "",
                "ref_domain" to "hq.vio-la.com",
                "partner_name" to "",
                "access_token" to (vkToken ?: return null),
            ),
        ).text
        val dec = body.replace("\\/", "/").replace("\\u0026", "&")
        val out = mutableListOf<Pair<String, String>>()
        Regex(""""(mp4_\d+|hls)"\s*:\s*"(https?[^"]+)"""").findAll(dec).forEach { m ->
            val q = if (m.groupValues[1] == "hls") "HLS" else m.groupValues[1].replace("mp4_", "") + "p"
            out.add(m.groupValues[2] to q)
        }
        return out.ifEmpty { null }
    }

    /** mawdhou3.com scrapers: GET {endpoint}?api={embed url} -> file:"url"(,label:"q") */
    private suspend fun scrape(endpoint: String, link: String): List<Pair<String, String>>? {
        val raw = try {
            app.get(endpoint + Uri.encode(link), headers = mapOf("User-Agent" to CHROME_UA)).text
        } catch (_: Exception) {
            return null
        }
        return Regex("""file:"(https[^"]+)"(?:\s*,\s*label:"([^"]*)")?""").findAll(raw).mapNotNull { m ->
            m.groupValues.getOrNull(1)?.takeIf { it.startsWith("http") }
                ?.let { it to (m.groupValues.getOrNull(2) ?: "") }
        }.toList().ifEmpty { null }
    }

    /** dean.edwards packed-eval unpacker (proven in Krmzy/Wecima) */
    private fun unpack(pageText: String): String? {
        val evalMatch = Regex("eval\\s*\\(\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}\\s*\\((.*)\\)\\s*\\)", setOf(RegexOption.DOT_MATCHES_ALL)).find(pageText) ?: return null
        val pm = Regex("['\"](.*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"](.*?)['\"]\\.split\\s*\\(['\"]\\|['\"]\\)", setOf(RegexOption.DOT_MATCHES_ALL)).find(evalMatch.groupValues[1]) ?: return null
        val p = pm.groupValues[1]
        val a = pm.groupValues[2].toIntOrNull() ?: return null
        val c = pm.groupValues[3].toIntOrNull() ?: return null
        val k = pm.groupValues[4].split('|')
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun toBase(num: Int, radix: Int): String {
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.insert(0, chars[n % radix])
                n /= radix
            }
            return sb.toString()
        }
        val map = HashMap<String, String>()
        for (i in 0 until c) {
            val kw = k.getOrNull(i)
            if (!kw.isNullOrEmpty()) map[toBase(i, a)] = kw
        }
        return Regex("\\b\\w+\\b").replace(p) { m -> map[m.value] ?: m.value }
    }
}

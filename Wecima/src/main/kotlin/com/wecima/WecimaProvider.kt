package com.wecima

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wecima (و سينما) — the Wecima Android app API (EasyPlex backend).
 *
 * From the hrrejgh.com HAR + live verification:
 * - All catalog endpoints are plain GET JSON under {API}/{path}/{TOK}.
 * - Static auth headers: Bearer + packagename + userdata/datadata (the "data"/"sizato"
 *   anti-tamper headers are NOT required).
 * - Search:   search/{query}%7Cvide/{TOK}   (mixed movies/series/animes, type field)
 * - Movie:    media/detail/{id}/{TOK}       -> videos[]
 * - Series:   series/show/{id}/{TOK}        -> seasons[].episodes[].videos[]
 *   Each video: {server, link, useragent, header(referer), hls, youtubelink}
 * - The API domain rotates (vidtube.one/flech.tn mirrors are already dead) — bump API if it 404s.
 * - Links: most hosts resolve natively via loadExtractor (streamwish, upstream, uqload...);
 *   egybestvid + seriesmp4 resolve locally (inline file:"..." / hidden iframe -> Yandex mp4);
 *   vidtube/updown/uqload/ukrcdn via mawdhou3.com GET scrapers: ?api={embed} ->
 *   "file:\"url\"" or {"status":"success","filtered_content":[...],"Quality":[...]}.
 */
class WecimaProvider : MainAPI() {

    override var name = "Wecima"
    override var mainUrl = API
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        private const val API = "https://hrrejgh.com/wecima15/public/api"
        private const val TOK = "p2lbgWkFrykA4QyUmpHihzmc5BNzIABq"
        private val HDRS = mapOf(
            "accept" to "application/json",
            "authorization" to "Bearer AuHLIRR82MvrdTTeaQKUxdA7mlNuk0WD6NnX2ffpn0wqeMP5zwkCClOHClRIbCFf",
            "packagename" to "com.radiotn.tunisie",
            "userdata" to "dmlkZQ==",
            "datadata" to "dmlkZQ==",
            "user-agent" to "EasyPlex (Android 14; 23043RP34G; Xiaomi pipa; en)",
        )
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

        // mawdhou3.com server-side scrapers (GET ?api={embed url}), by host substring
        private val SCRAPERS = listOf(
            "vidtube" to "https://mawdhou3.com/scrapefinal/vidtubepost.php?api=",
            "updown" to "https://mawdhou3.com/scrapefinal/updown.php?api=",
            "uqload" to "https://mawdhou3.com/scrapefinal/uqload.php?api=",
            "ukrcdn" to "https://mawdhou3.com/scrapefinal/ukrcdn.php?api=",
            "test-stream" to "https://mawdhou3.com/scrapefinal/stream.developer.php?api=",
            "vidoba" to "https://mawdhou3.com/scrapefinal/turki.php?api=",
            "vidroba" to "https://mawdhou3.com/scrapefinal/turki.php?api=",
            "mwdy" to "https://mawdhou3.com/scrapefinal/turki.php?api=",
            "miravd" to "https://mawdhou3.com/scrapefinal/turki.php?api=",
            "film77" to "https://mawdhou3.com/scrapefinal/turki.php?api=",
            "okhd" to "https://mawdhou3.com/scrapefinal/turki.php?api=",
            "hdup20" to "https://mawdhou3.com/scrapefinal/hdup20.php?api=",
            "anafast" to "https://mawdhou3.com/test.php?api=",
            "mp4plus" to "https://mawdhou3.com/test.php?api=",
            "pluss24" to "https://mawdhou3.com/scrapefinal/vidsp/post.php?api=",
            "googlefas" to "https://mawdhou3.com/aminegoogle/extract_qualite.php?url=",
        )
        // two-step recipes (from the app hosts/config urlsite "steps")
        private const val STEP1_MZFI = "https://flech.tn/amine/movibox/step1_prepare_movibox.php"
        private const val STEP1_OSTV = "https://flech.tn/scrapefinal/dramabox/step1_prepare_oscar.php"

        private val json = Json { ignoreUnknownKeys = true }
    }

    // ---------- helpers ----------

    private suspend fun api(path: String): JsonObject? = try {
        json.parseToJsonElement(app.get("$API/$path", headers = HDRS).text).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return null
    }

    private fun JsonObject.int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

    // ---------- catalog ----------

    override val mainPage = mainPageOf(
        "wecima://home/latest" to "أحدث الإضافات",
        "wecima://home/trending" to "الأكثر رواجاً",
        "wecima://home/top10" to "الأفضل 10",
        "wecima://home/popularSeries" to "مسلسلات شائعة",
        "wecima://home/anime" to "أنمي",
        "wecima://home/latest_episodes" to "أحدث الحلقات",
        "wecima://cat/movies" to "أفلام",
        "wecima://cat/series" to "مسلسلات",
        "wecima://cat/anime" to "أحدث الأنمي",
    )

    /** typeHint: "movie" | "serie" | "anime" — rails are single-type, items carry no type */
    private fun item(o: JsonObject, typeHint: String): SearchResponse? {
        val id = o.int("id") ?: return null
        val title = o.str("title", "name") ?: return null
        val isAnime = typeHint == "anime" || o.int("is_anime") == 1 || o.str("type")?.contains("anime", true) == true
        val isMovie = typeHint == "movie" || o.str("type")?.contains("movie", true) == true
        val url = if (isMovie) "wecima://movie/$id" else "wecima://${if (isAnime) "anime" else "serie"}/$id"
        val poster = o.str("poster_path")
        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.removePrefix("wecima://")
        val items: List<SearchResponse> = when {
            data.startsWith("home/") -> {
                if (page > 1) emptyList() else {
                    val j = api("media/homecontent/$TOK") ?: return newHomePageResponse(request.name, emptyList())
                    val rail = data.substringAfter("home/")
                    val arr = j[rail]
                    val objs = when (arr) {
                        is JsonArray -> arr.mapNotNull { it as? JsonObject }
                        is JsonObject -> listOf(arr)
                        else -> emptyList()
                    }
                    if (rail == "latest_episodes") {
                        // episode items: {id=serie id, name, episode_name, still_path}
                        objs.mapNotNull o@{ o ->
                            val id = o.int("id") ?: return@o null
                            val name = o.str("name") ?: return@o null
                            val epName = o.str("episode_name") ?: ""
                            newTvSeriesSearchResponse("$name — $epName", "wecima://serie/$id", TvType.TvSeries) {
                                this.posterUrl = o.str("still_path")
                            }
                        }
                    } else {
                        objs.mapNotNull { item(it, "") }
                    }
                }
            }
            data == "cat/movies" -> {
                (api("genres/movies/all/$TOK?page=$page") ?: return newHomePageResponse(request.name, emptyList()))
                    .arrData().mapNotNull { item(it, "movie") }
            }
            data == "cat/series" -> {
                (api("genres/series/all/$TOK?page=$page") ?: return newHomePageResponse(request.name, emptyList()))
                    .arrData().mapNotNull { item(it, "serie") }
            }
            data == "cat/anime" -> {
                (api("animes/latestadded/$TOK?page=$page") ?: return newHomePageResponse(request.name, emptyList()))
                    .arrData().mapNotNull { item(it, "anime") }
            }
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    private fun JsonObject.arrData(): List<JsonObject> =
        (this["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val j = api("search/${Uri.encode(q)}%7Cvide/$TOK") ?: return emptyList()
        return (j["search"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> item(o, "") } } ?: emptyList()
    }

    // ---------- load ----------

    /** pack videos into a compact data string for loadLinks */
    private fun pack(videos: List<JsonObject>): String = videos.mapNotNull { v ->
        if (v.int("youtubelink") == 1) return@mapNotNull null
        val link = v.str("link") ?: return@mapNotNull null
        if (!link.startsWith("http")) return@mapNotNull null
        listOf(
            v.str("server") ?: "",
            link,
            v.str("useragent") ?: "",
            v.str("header")?.takeIf { it.startsWith("http") } ?: "",
        ).joinToString("::")
    }.distinctBy { it.substringAfter("::").substringBefore("::") }.joinToString("||")

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("wecima://")) return null
        val id = Regex("wecima://(?:movie|serie|anime)/(\\d+)").find(url)?.groupValues?.get(1) ?: return null

        return when {
            url.contains("movie/") -> {
                val j = api("media/detail/$id/$TOK") ?: return null
                val title = j.str("title") ?: return null
                val videos = (j["videos"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                newMovieLoadResponse(title, url, TvType.Movie, "wecima://links/" + pack(videos)) {
                    this.posterUrl = j.str("poster_path")
                    this.plot = j.str("overview")
                    this.year = j.str("release_date")?.take(4)?.toIntOrNull()
                    this.backgroundPosterUrl = j.str("backdrop_path")
                    this.tags = (j["genreslist"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                }
            }
            else -> {
                val j = api("series/show/$id/$TOK") ?: return null
                val title = j.str("name") ?: return null
                val isAnime = url.contains("anime/")
                val seasons = (j["seasons"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                val episodes = seasons.flatMap { s ->
                    val sNum = s.int("season_number") ?: 1
                    (s["episodes"] as? JsonArray)?.mapNotNull e@{ el ->
                        val e = el as? JsonObject ?: return@e null
                        val eNum = e.int("episode_number")
                        val videos = (e["videos"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                        val data = "wecima://links/" + pack(videos)
                        if (data == "wecima://links/") return@e null
                        newEpisode(
                            url = data,
                            initializer = {
                                this.name = e.str("name") ?: "الحلقة ${eNum ?: ""}"
                                this.season = sNum
                                this.episode = eNum
                                this.posterUrl = e.str("still_path")
                            },
                            fix = false,
                        )
                    } ?: emptyList()
                }
                newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, episodes) {
                    this.posterUrl = j.str("poster_path")
                    this.plot = j.str("overview")
                    this.year = j.str("first_air_date")?.take(4)?.toIntOrNull()
                    this.backgroundPosterUrl = j.str("backdrop_path")
                    this.tags = (j["genreslist"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                }
            }
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.contains("wecima://links/")) return false
        val entries = data.substringAfter("wecima://links/").split("||")
        var got = false
        for (entry in entries) {
            try {
                val p = entry.split("::")
                if (p.size < 2) continue
                val server = p[0]
                val link = p[1]
                val ua = p.getOrNull(2)?.takeIf { it.isNotBlank() } ?: CHROME_UA
                val referer = p.getOrNull(3)?.takeIf { it.isNotBlank() }
                    ?: ("https://" + (Uri.parse(link).host ?: "") + "/")
                if (!link.startsWith("http")) continue

                // 1) direct stream links (seriesmp4 .mp4 links are HTML wrappers -> resolveEmbed)
                if (!link.contains("seriesmp4") &&
                    (link.contains(".m3u8") || link.contains(".mp4") || link.contains("urlset"))
                ) {
                    emit(callback, link, server, ua, referer)
                    got = true
                    continue
                }
                // 2) native CloudStream extractors (streamwish, upstream, uqload, ukrcdn...)
                if (loadExtractor(link, referer, subtitleCallback, callback)) {
                    got = true
                    continue
                }
                // 3) local + mawdhou3 resolvers (egybestvid, seriesmp4, vidtube, updown, uqload...)
                resolveEmbed(link)?.forEach { (u, label) ->
                    emit(callback, u, if (label.isBlank()) server else "$server $label", ua, referer)
                    got = true
                }
            } catch (_: Exception) {
            }
        }
        return got
    }

    private suspend fun emit(callback: suspend (ExtractorLink) -> Unit, url: String, label: String, ua: String, referer: String) {
        val type = when {
            url.contains(".mp4") -> ExtractorLinkType.VIDEO
            url.contains(".m3u8") || url.contains("urlset") -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(name, label.ifBlank { name }, url, type) {
                this.headers = if (referer.isBlank()) mapOf("User-Agent" to ua)
                else mapOf("User-Agent" to ua, "Referer" to referer)
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /** all the custom resolvers (from the app hosts/config recipes + live-verified) */
    private suspend fun resolveEmbed(link: String): List<Pair<String, String>>? = try {
        when {
            link.contains("egybestvid") -> {
                // the embed page carries the master playlist inline: file:"https://.../master.m3u8?..."
                val t = app.get(link, headers = mapOf("User-Agent" to CHROME_UA)).text
                Regex("file:\\s*\"(https[^\"]+)\"").findAll(t).mapNotNull { m ->
                    m.groupValues.getOrNull(1)?.takeIf { it.contains(".m3u8") }?.let { it to "" }
                }.toList().ifEmpty { null }
            }
            link.contains("seriesmp4") -> {
                // the .mp4 link is actually an HTML wrapper with a hidden iframe (Yandex direct mp4)
                val h = app.head(link, headers = mapOf("User-Agent" to CHROME_UA))
                if ((h.headers["content-type"] ?: "").contains("video")) listOf(link to "")
                else {
                    val t = app.get(link, headers = mapOf("User-Agent" to CHROME_UA)).text
                    Regex("iframe[^>]+src=\"([^\"]+)\"").find(t)?.groupValues?.get(1)
                        ?.replace("&" + "amp;", "&")?.takeIf { it.startsWith("http") }?.let { listOf(it to "") }
                }
            }
            link.contains("developer-pro.workers.dev") -> {
                // get-links 302s straight to the OK.ru CDN file -> direct
                listOf(link to "")
            }
            link.contains("vidaraa.") -> {
                // POST /api/stream {filecode, device} -> streaming_url
                val code = link.trimEnd('/').substringAfterLast('/')
                val host = Uri.parse(link).host ?: return null
                val r = app.post(
                    "https://$host/api/stream",
                    headers = mapOf("User-Agent" to CHROME_UA, "Content-Type" to "application/json"),
                    json = org.json.JSONObject(mapOf("filecode" to code, "device" to "android")),
                ).text
                json.parseToJsonElement(r).jsonObject.str("streaming_url")?.let { listOf(it to "") }
            }
            link.contains("mzfi.me") || link.contains("ostvapp") -> {
                // two-step: step1 returns the fetch headers, step2 returns the streams JSON
                val step1 = if (link.contains("mzfi")) STEP1_MZFI else STEP1_OSTV
                val j = json.parseToJsonElement(
                    app.post(step1, headers = mapOf("User-Agent" to CHROME_UA), data = mapOf("url" to link)).text
                ).jsonObject
                val hdrs = (j["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v.jsonPrimitive.contentOrNull)?.let { k to it }
                }?.toMap() ?: emptyMap()
                val t = app.get(j.str("url") ?: link, headers = hdrs).text
                val streams = runCatching {
                    ((json.parseToJsonElement(t).jsonObject["data"] as? JsonObject)?.get("streams") as? JsonArray)
                }.getOrNull()
                val out = streams?.mapNotNull s@{ s ->
                    val o = s as? JsonObject ?: return@s null
                    o.str("url")?.let { it to (o.str("resolutions") ?: "") }
                } ?: Regex("https?://[^\"\\s]+?(?:\\.m3u8|\\.mp4)[^\"\\s]*")
                    .findAll(t.replace("\\/", "/"))
                    .map { m -> m.value to (Regex("quality=(\\w+)").find(m.value)?.groupValues?.getOrNull(1) ?: "") }
                    .distinctBy { it.first }.toList()
                out.ifEmpty { null }
            }
            link.contains("hanerix") || link.contains("vidspeed") || link.contains("arabveturk") -> {
                // packed-eval pages -> unpack (Krmzy deobfuscator) -> master.txt/m3u8
                val deob = krmzyUnpack(app.get(link, headers = mapOf("User-Agent" to CHROME_UA)).text) ?: return null
                Regex("[\"'](https?://[^\"']+)[\"']").findAll(deob).map { it.groupValues[1] }
                    .firstOrNull { it.contains("master.txt") || it.contains(".m3u8") }?.let { listOf(it to "") }
                        ?: Regex("file:\\s*[\"'](https[^\"']+)[\"']").findAll(deob).mapNotNull { m ->
                        m.groupValues.getOrNull(1)?.takeIf { it.contains(".m3u8") }?.let { it to "" }
                    }.toList().ifEmpty { null }
            }
            else -> {
                val scraper = SCRAPERS.firstOrNull { (h, _) -> link.contains(h, true) }?.second
                    ?: return null
                parseScrape(app.get(scraper + Uri.encode(link), headers = mapOf("User-Agent" to CHROME_UA)).text)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** dean.edwards packed-eval unpacker (proven in the Krmzy provider) */
    private fun krmzyUnpack(pageText: String): String? {
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

    /** mawdhou3 responses: JSON {filtered_content[], Quality[]} or raw file:"url",label:"quality" */
    private fun parseScrape(raw: String?): List<Pair<String, String>>? {
        if (raw.isNullOrBlank()) return null
        val parsed = try { json.parseToJsonElement(raw).jsonObject } catch (_: Exception) { null }
        if (parsed != null) {
            val urls = parsed["filtered_content"] as? JsonArray ?: return null
            val quals = parsed["Quality"] as? JsonArray
            return urls.mapIndexedNotNull { i, u ->
                val s = try { u.jsonPrimitive.contentOrNull } catch (_: Exception) { null }
                s?.takeIf { it.startsWith("http") }?.let {
                    it to (quals?.getOrNull(i)?.let { q -> try { q.jsonPrimitive.contentOrNull } catch (_: Exception) { null } } ?: "")
                }
            }.ifEmpty { null }
        }
        val out = Regex("file:\"(https[^\"]+)\"(?:\\s*,\\s*label:\"([^\"]*)\")?").findAll(raw).mapNotNull { m ->
            m.groupValues.getOrNull(1)?.takeIf { it.startsWith("http") }?.let { it to (m.groupValues.getOrNull(2) ?: "") }
        }.toList()
        return out.ifEmpty { null }
    }
}
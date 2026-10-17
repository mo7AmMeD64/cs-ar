package com.animeslayer

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class AnimeSlayer : MainAPI() {
    override var mainUrl = "https://anslayer.com"
    override var name = "AnimeSlayer"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.Cartoon,
        TvType.TvSeries, TvType.Movie, TvType.OVA
    )

    private val apiBase = "$mainUrl/anime/public"
    private val fwUrl = "$mainUrl/la/public/api/fw"
    private val otherBase = "https://a-reslayer.com"
    private val TAG = "AnimeSlayer"

    private val browserUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val apiHeaders = mapOf(
        "client-id" to "android-app2",
        "client-secret" to "7befba6263cc14c90d2f1d6da2c5cf9b251bfbbd",
        "User-Agent" to "okhttp/5.3.2",
        "Accept" to "application/json, text/plain, */*"
    )

    // =========================================================================
    // Main page catalogs
    // =========================================================================

    override val mainPage = mainPageOf(
        "latest_episodes"      to "أحدث الحلقات",
        "currently_airing"     to "يُعرض الآن",
        "top_anime"            to "الأكثر شعبية",
        "top_currently_airing" to "أعلى المُعروض الآن",
        "top_movie"            to "أفضل الأفلام",
        "last_added_tv"        to "أحدث المسلسلات",
        "last_added_movie"     to "أحدث الأفلام",
        "featured"             to "مميزة",
        "top_upcoming"         to "قادمة قريباً"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val items = fetchPublished(request.data)
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = false
        )
    }

    // =========================================================================
    // Search
    // =========================================================================

    override suspend fun search(query: String): List<SearchResponse> =
        fetchPublished("filter", mapOf("anime_name" to query))

    private suspend fun fetchPublished(
        listType: String,
        extra: Map<String, Any?> = emptyMap()
    ): List<SearchResponse> {
        val json = JSONObject().apply {
            put("list_type", listType)
            put("just_info", "Yes")
            put("order", "desc")
            extra.forEach { (k, v) -> put(k, v) }
        }
        val url = "$apiBase/animes/get-published-animes?json=${json.toString().encode()}"
        val body = runCatching { app.get(url, headers = apiHeaders).text }.getOrNull()
            ?: return emptyList()
        val arr = runCatching {
            JSONObject(body).getJSONObject("response").getJSONArray("data")
        }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toSearchResponse() }
    }

    private fun JSONObject.toSearchResponse(): SearchResponse? {
        val id = optString("anime_id").ifBlank { return null }
        val title = optString("anime_name").ifBlank { return null }
        val poster = optString("anime_cover_image_url").ifBlank { null }
        val type = parseType(optString("anime_type"))
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", type) {
            this.posterUrl = poster
        }
    }

    // =========================================================================
    // Details + episodes
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val animeId = Regex("""/anime/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: url.substringAfterLast("/").toIntOrNull()
            ?: return newAnimeLoadResponse(url, url, TvType.Anime) {}

        val resp = runCatching {
            val body = app.get("$apiBase/anime/get-anime-details?anime_id=$animeId", headers = apiHeaders).text
            JSONObject(body).getJSONObject("response")
        }.getOrNull() ?: return newAnimeLoadResponse(url, url, TvType.Anime) {}

        val title = resp.optString("anime_name").ifBlank { "AnimeSlayer" }
        val poster = resp.optString("anime_cover_image_url").ifBlank { null }
        val plot = resp.optString("anime_description").ifBlank { null }
        val year = resp.optString("anime_release_year").toIntOrNull()
        val type = parseType(resp.optString("anime_type"))
        val tags = resp.optString("anime_genres")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val episodes = parseEpisodes(resp)

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    private fun parseEpisodes(detail: JSONObject): List<com.lagradost.cloudstream3.Episode> {
        val episodesObj = detail.optJSONObject("episodes") ?: return emptyList()
        val arr = episodesObj.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<com.lagradost.cloudstream3.Episode>()
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            val epId = ep.optString("episode_id").ifBlank { continue }
            val number = Regex("""\d+""").find(ep.optString("episode_number"))?.value?.toIntOrNull() ?: 0
            val fwParam = resolveFwParam(ep) ?: continue
            val epName = ep.optString("episode_name").ifBlank { "الحلقة $number" }
            out.add(newEpisode(fwParam) {
                this.name = epName
                this.episode = number
            })
        }
        return out.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    /**
     * Derive the `n` value (`<slug>\<episode>`) accepted by the fw resolver from
     * any of the episode's server URLs. The `muilt`/`a-reslayer` server exposes
     * it directly as `?n=`, while the `cdn` server exposes `?f=<slug>&e=<ep>`.
     */
    private fun resolveFwParam(ep: JSONObject): String? {
        val urls = ep.optJSONArray("episode_urls") ?: return null
        var cdnFallback: String? = null
        for (i in 0 until urls.length()) {
            val u = urls.optJSONObject(i) ?: continue
            val link = u.optString("episode_url").replace("\\/", "/")
            if (link.isBlank()) continue
            Regex("""[?&]n=([^&]+)""").find(link)?.groupValues?.get(1)?.let {
                return normalizeFwParam(it)
            }
            if (cdnFallback == null) {
                val slug = Regex("""[?&]f=([^&]+)""").find(link)?.groupValues?.get(1)
                val epNum = Regex("""[?&]e=([^&]+)""").find(link)?.groupValues?.get(1)
                if (slug != null && epNum != null) cdnFallback = "$slug\\$epNum"
            }
        }
        return cdnFallback
    }

    /**
     * Accepts whatever the app hands over and reduces it to the `<slug>\<ep>`
     * token the resolver expects. Handles a plain token, a full resolver URL
     * (`...?n=slug\ep` / `...?f=slug&e=ep`), and a site-prefixed token such as
     * `https://anslayer.com/slug\ep` (seen when a stale episode list is reused).
     */
    private fun normalizeFwParam(raw: String): String {
        var s = runCatching { java.net.URLDecoder.decode(raw.trim(), "UTF-8") }
            .getOrDefault(raw.trim())
            .replace("\\/", "/")

        Regex("""[?&]n=([^&]+)""").find(s)?.let {
            return it.groupValues[1].replace("\\/", "/")
        }
        val slug = Regex("""[?&]f=([^&]+)""").find(s)?.groupValues?.get(1)
        val ep = Regex("""[?&]e=([^&]+)""").find(s)?.groupValues?.get(1)?.substringBefore("|")
        if (slug != null && ep != null) return "$slug\\$ep"

        s = s.replace(Regex("""^[a-zA-Z]+://[^/]+/"""), "")
        s = s.removePrefix("anime/public/").removePrefix("anime/").removePrefix("la/public/api/f/")
        return s
    }

    // =========================================================================
    // loadLinks: fw -> direct links -> extractors
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = data.trim()
        val fwParam = normalizeFwParam(raw)
        Log.i(TAG, "loadLinks data=$raw -> n=$fwParam")
        if (fwParam.isBlank()) return false

        val links = fetchLinks(fwParam)
        if (links.isEmpty()) return false

        var found = false
        for (link in links.distinct()) {
            val ok = handleLink(link, subtitleCallback, callback)
            Log.i(TAG, "  ${if (ok) "OK  " else "FAIL"} $link")
            if (ok) found = true
        }
        return found
    }

    /**
     * Resolve the direct links for `<slug>\<episode>`. The official app uses
     * `POST /la/public/api/fw`, but that endpoint answers with an empty array
     * when the POST body is dropped (seen on some carriers). The equivalent
     * `GET /la/public/api/f` always answers correctly, so it is tried first.
     */
    private suspend fun fetchLinks(fwParam: String): List<String> {
        val enc = URLEncoder.encode(fwParam, "UTF-8")

        for (url in listOf(
            "$mainUrl/la/public/api/f?n=$enc",
            "$otherBase/la/public/api/f?n=$enc"
        )) {
            val body = runCatching {
                app.get(url, headers = apiHeaders, cacheTime = 0).text
            }.getOrNull()
            Log.i(TAG, "GET $url -> $body")
            parseLinks(body).takeIf { it.isNotEmpty() }?.let { return it }
        }

        val body = runCatching {
            app.post(
                fwUrl,
                headers = apiHeaders,
                data = mapOf("n" to fwParam, "inf" to ""),
                cacheTime = 0
            ).text
        }.getOrNull()
        Log.i(TAG, "POST $fwUrl -> $body")
        return parseLinks(body)
    }

    private fun parseLinks(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }
            .map { it.replace("\\/", "/") }
    }

    private suspend fun handleLink(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val link = when {
            url.startsWith("http://ok.ru") -> url.replaceFirst("http://", "https://")
            else -> url
        }

        // Direct media files served straight from the fw response.
        if (looksLikeMedia(link)) {
            emitMedia(link, mainUrl, callback)
            return true
        }

        val extracted = try {
            loadExtractor(link, mainUrl, subtitleCallback) { l -> callback(l) }
        } catch (_: Exception) {
            false
        }
        if (extracted) return true

        // StreamTape is handled manually so we never clash with the built-in
        // extractor (which is tried first above) and still cover `.to` mirrors.
        if (link.contains("streamtape")) return streamtapeExtract(link, callback)

        return tryProviderExtract(link, callback)
    }

    /**
     * Port of the `streamtape()` routine in AnimeSlayer's own `ol6.js`: the
     * direct `get_video` URL lives in the hidden `#ideoooolink` div, and the
     * final token is assigned to `#norobotlink` via a split/`substring` trick.
     */
    private suspend fun streamtapeExtract(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
            ?: return false
        val id = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (id.isBlank()) return false

        val page = runCatching {
            app.get("https://$host/v/$id", headers = mapOf("User-Agent" to browserUA)).text
        }.getOrNull() ?: return false

        val norobot = Regex("""document\.getElementById\('norobotlink'\)\.innerHTML\s*=\s*(.*?);""")
            .find(page)?.groupValues?.get(1) ?: return false
        val token = Regex("""token=([^&'"]+)""").find(norobot)?.groupValues?.get(1) ?: return false
        val hidden = Regex("""<div id="ideoooolink"[^>]*>(.*?)</div>""")
            .find(page)?.groupValues?.get(1) ?: return false

        val path = "/" + hidden.replace("\\/", "/").substringAfter(host, "").trimStart('/')
        if (path == "/") return false
        val finalUrl = "https://$host$path" + "&token=$token&dl=1"

        emitMedia(finalUrl, "https://$host/", callback)
        return true
    }

    /**
     * Generic embed extractor ported from AnimeSlayer's own `ol6.js` runtime:
     * decode the P.A.C.K.E.R. payload when present, then collect the player's
     * `file:` / `src:` sources (goodstream, lulustream, highstream, streamhub,
     * guccihide, streamvid) or any bare m3u8/mp4 URL.
     */
    private suspend fun tryProviderExtract(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to browserUA), referer = mainUrl).text
        }.getOrNull() ?: return false

        val decoded = decodePacker(html) ?: runCatching { JsUnpacker(html).unpack() }.getOrNull()
        val pages = listOfNotNull(html, decoded)

        val m3u8 = LinkedHashSet<String>()
        val video = LinkedHashSet<String>()

        for (page in pages) {
            Regex("""(?:file|src)\s*:\s*"([^"]+)"""").findAll(page).forEach { m ->
                addMedia(m.groupValues[1], m3u8, video)
            }
            Regex("""(?:file|src)\s*:\s*'([^']+)'""").findAll(page).forEach { m ->
                addMedia(m.groupValues[1], m3u8, video)
            }
            // streamvid: src:"host,path" -> <host><path>/index-v1-a1.m3u8
            Regex("""src:\s*"([^",]+),([^"]+)""").findAll(page).forEach { m ->
                val host = m.groupValues[1].replace("\\/", "/")
                val path = m.groupValues[2].replace("\\/", "/")
                if (host.startsWith("http")) m3u8.add(host + path + "/index-v1-a1.m3u8")
            }
            Regex("""https?://[^"'<>\s\\]+\.(?:m3u8|mp4|mkv|webm)[^"'<>\s\\]*""").findAll(page).forEach {
                addMedia(it.value, m3u8, video)
            }
        }

        val chosen = m3u8.firstOrNull() ?: video.firstOrNull() ?: return false
        emitMedia(chosen, url, callback)
        return true
    }

    private fun addMedia(raw: String, m3u8: MutableSet<String>, video: MutableSet<String>) {
        val url = raw.replace("\\/", "/").trim().removePrefix("\\")
        if (!url.startsWith("http")) return
        when {
            url.contains(".m3u8") -> m3u8.add(url)
            url.contains(".mp4") || url.contains(".mkv") || url.contains(".webm") -> video.add(url)
        }
    }

    private suspend fun emitMedia(mediaUrl: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = mediaUrl.contains(".m3u8")
        callback(
            newExtractorLink(
                source = this.name,
                name = "$name ${qualityName(mediaUrl)}",
                url = mediaUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = qualityValue(mediaUrl)
                this.headers = mapOf("User-Agent" to browserUA)
            }
        )
    }

    /** Dean Edwards packer decoder: eval(function(p,a,c,k,e,d){...})('..',a,c,'..'.split('|'),...) */
    private fun decodePacker(html: String): String? {
        val start = html.indexOf("function(p,a,c,k,e,d)")
        if (start < 0) return null
        val tail = html.substring(start)
        val matcher = Regex(
            """\((['"])(.*?)\1,\s*(\d+),(\d+),\s*['"]([^'"]*)['"]\.split\(['"]\|['"]\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(tail) ?: return null

        val payload = matcher.groupValues[2]
        val radix = matcher.groupValues[3].toIntOrNull() ?: return null
        val count = matcher.groupValues[4].toIntOrNull() ?: return null
        val keys = matcher.groupValues[5].split("|")
        if (payload.isEmpty() || radix !in 2..36 || count < 1) return null

        var out = payload
        for (i in (count - 1) downTo 0) {
            val symbol = packerSymbol(i, radix)
            val key = keys.getOrNull(i) ?: continue
            out = Regex("\\b" + Regex.escape(symbol) + "\\b").replace(out) { key }
        }
        return out
    }

    private fun packerSymbol(value: Int, radix: Int): String {
        if (value < radix) return Integer.toString(value, radix)
        return packerSymbol(value / radix, radix) + Integer.toString(value % radix, radix)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun parseType(raw: String): TvType = when (raw.trim().lowercase()) {
        "movie" -> TvType.AnimeMovie
        "ova" -> TvType.OVA
        else -> TvType.Anime
    }

    private fun looksLikeMedia(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#")
        return clean.endsWith(".m3u8") || clean.endsWith(".mp4") ||
            clean.endsWith(".mkv") || clean.endsWith(".webm")
    }

    private fun qualityValue(url: String): Int = when {
        url.contains("hh.mp4") || url.contains("1080") -> Qualities.P1080.value
        url.contains("h.mp4") || url.contains("720") -> Qualities.P720.value
        url.contains("s.mp4") || url.contains("480") -> Qualities.P480.value
        url.contains("m.mp4") || url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun qualityName(url: String): String = when {
        url.contains("hh.mp4") || url.contains("1080") -> "1080p"
        url.contains("h.mp4") || url.contains("720") -> "720p"
        url.contains("s.mp4") || url.contains("480") -> "480p"
        url.contains("m.mp4") || url.contains("360") -> "360p"
        else -> "auto"
    }

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")
}

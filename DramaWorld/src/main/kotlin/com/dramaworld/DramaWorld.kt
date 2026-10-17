package com.dramaworld

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * DramaWorld (عالم الدراما) provider.
 *
 * Backend: a Laravel-style JSON API behind a dynamic host (resolved at runtime by
 * the official app through Firebase Remote Config + a `/validate.php` AES-GCM
 * handshake). The captured host is `test.arabypros.com`; every request goes to
 * `/api/{endpoint}/4F5A9C3D9A86FA54EACEDDD635185/d506abfd-9fe2-4b71-b979-feff21bcad13/`.
 *
 * The sources endpoints (`movie/source/by/{id}` and `episode/source/by/{id}`)
 * return `<junk-prefix><base64(JSON array)>`; the payload is recovered by
 * locating the base64 of `[{` ("W3s") and decoding from there, exactly like the
 * official client. Each source holds a host label (`title`) and an embed page
 * (`url`); embedded pages are handed to CloudStream's global [loadExtractor],
 * while direct `.m3u8`/`.mp4`/`.webm`/`.mkv` URLs are emitted as-is.
 *
 * Live TV channels carry their (direct) stream URLs inline.
 */
class DramaWorld : MainAPI() {
    companion object {
        private const val TAG = "DramaWorld"

        private const val KEY = "4F5A9C3D9A86FA54EACEDDD635185"
        private const val UUID = "d506abfd-9fe2-4b71-b979-feff21bcad13"
        private const val UA = "okhttp/4.12.0"

        // The capture's HTTP/2 `:authority` was `dwapp.arabypros.com` (the `url`
        // field was a placeholder). Both are the same Cloudflare zone; we probe
        // candidates and stick with the first that returns JSON.
        private val BASE_CANDIDATES = listOf(
            "https://dwapp.arabypros.com/api",
            "https://test.arabypros.com/api"
        )

        private const val MOVIE_DATA = "dramaworld:movie:"
        private const val EPISODE_DATA = "dramaworld:episode:"
        private const val CHANNEL_DATA = "dramaworld:channel:"

        private const val MAIN_HOST = "https://dwapp.arabypros.com"
        private const val DETAIL_URL = "$MAIN_HOST/detail/"

        // While resolving the embed list every source runs in parallel; a dead or hung host is
        // dropped after this budget instead of stalling the whole player load.
        private const val SOURCE_TIMEOUT_MS = 12_000L

        // Embeds that are JS-rendered or no longer resolve. They are squeezed to the end of the
        // queue so working servers surface first, but they are still attempted (never removed).
        private val DEAD_OR_SLOW_HOSTS = setOf(
            "streamhls", "uqload", "cimanow", "cimanowtv", "upns", "tuktuk", "megamax", "megatuktuk"
        )
    }

    override var name = "DramaWorld"
    override var lang = "ar"
    override var mainUrl = MAIN_HOST

    @Volatile
    private var resolvedBase: String? = null

    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    private val headers = mapOf("User-Agent" to UA)

    // ---------------------------------------------------------------- helpers

    /** Resolves the live API host by probing the candidates once. */
    private suspend fun base(): String {
        resolvedBase?.let { return it }
        for (candidate in BASE_CANDIDATES) {
            val ok = try {
                val text = app.get("$candidate/first/$KEY/$UUID/", headers = headers).text.trim()
                text.startsWith("{")
            } catch (e: Throwable) {
                android.util.Log.i(TAG, "base probe FAILED $candidate -> $e")
                false
            }
            if (ok) {
                synchronized(this) { if (resolvedBase == null) resolvedBase = candidate }
                return candidate
            }
        }
        android.util.Log.i(TAG, "base probe: no candidate returned JSON, defaulting to ${BASE_CANDIDATES.first()}")
        return BASE_CANDIDATES.first()
    }

    private suspend fun api(path: String): String = "${base()}/$path/$KEY/$UUID/"

    private suspend fun getJson(url: String): JSONObject? = try {
        val text = app.get(url, headers = headers).text.trim()
        if (text.startsWith("{")) {
            JSONObject(text)
        } else {
            logUnexpected(url, text)
            null
        }
    } catch (e: Throwable) {
        android.util.Log.i(TAG, "getJson FAILED $url -> $e")
        null
    }

    private suspend fun getArray(url: String): JSONArray? = try {
        val text = app.get(url, headers = headers).text.trim()
        if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            logUnexpected(url, text)
            null
        }
    } catch (e: Throwable) {
        android.util.Log.i(TAG, "getArray FAILED $url -> $e")
        null
    }

    private fun logUnexpected(url: String, text: String) {
        val cf = if (text.contains("Just a moment", true) || text.contains("cf-mitigated", true) ||
            text.contains("challenge-platform", true)
        ) " [CLOUDFLARE CHALLENGE]" else ""
        android.util.Log.i(TAG, "non-JSON ($cf) $url -> ${text.take(160)}")
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * The `season/by/serie` endpoint returns seasons with titles that are either
     * numeric (`الموسم 13`) or Arabic ordinals (`الموسم الثالث`). The order of the
     * array is not guaranteed to match the season number, so we derive the number
     * from the title itself rather than from the array index.
     */
    private val arabicOrdinals = linkedMapOf(
        "الحادي عشر" to 11,
        "الثاني عشر" to 12,
        "الثالث عشر" to 13,
        "الرابع عشر" to 14,
        "الخامس عشر" to 15,
        "السادس عشر" to 16,
        "السابع عشر" to 17,
        "الثامن عشر" to 18,
        "التاسع عشر" to 19,
        "العشرون" to 20,
        "العشرين" to 20,
        "الأول" to 1,
        "الاول" to 1,
        "الثاني" to 2,
        "الثالث" to 3,
        "الرابع" to 4,
        "الخامس" to 5,
        "السادس" to 6,
        "السابع" to 7,
        "الثامن" to 8,
        "التاسع" to 9,
        "العاشر" to 10
    )

    private fun parseSeasonNumber(title: String): Int? {
        Regex("\\d+").find(title)?.value?.toIntOrNull()?.let { return it }
        for ((word, number) in arabicOrdinals) {
            if (title.contains(word)) return number
        }
        return null
    }

    /**
     * Recovers the JSON array hidden in a sources response. The official client
     * looks for the base64 of `[{` ("W3s") and decodes from that offset.
     */
    private fun decodeSources(text: String): String? {
        val t = text.trim()
        if (t.startsWith("[")) return t
        val idx = t.indexOf("W3s")
        if (idx == -1) return null
        return try {
            String(Base64.decode(t.substring(idx), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "decodeSources failed -> $e")
            null
        }
    }

    private fun normalizeType(raw: String?): String =
        if (raw.equals("serie", true) || raw.equals("series", true) || raw.equals("tv", true)) {
            "serie"
        } else {
            "movie"
        }

    private fun posterSearch(item: JSONObject): SearchResponse? {
        val id = item.optInt("id", 0)
        if (id <= 0) return null
        val isSerie = normalizeType(item.optString("type")) == "serie"
        return newMovieSearchResponse(
            item.optString("title").ifBlank { "بدون عنوان" },
            "$DETAIL_URL${if (isSerie) "serie" else "movie"}/$id",
            if (isSerie) TvType.TvSeries else TvType.Movie
        ) {
            this.posterUrl = item.optString("image").ifBlank { item.optString("cover") }.ifBlank { null }
            this.year = item.optString("year").take(4).toIntOrNull()
        }
    }

    private fun channelSearch(item: JSONObject): SearchResponse? {
        val id = item.optInt("id", 0)
        if (id <= 0) return null
        return newMovieSearchResponse(
            item.optString("title").ifBlank { "قناة" },
            "$DETAIL_URL" + "channel/$id",
            TvType.Live
        ) {
            this.posterUrl = item.optString("image").ifBlank { null }
        }
    }

    private fun parsePosters(array: JSONArray?): List<SearchResponse> {
        if (array == null) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { posterSearch(it)?.let { r -> out.add(r) } }
        }
        return out
    }

    // ---------------------------------------------------------------- home

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val root = getJson(api("first")) ?: return newHomePageResponse(emptyList())
        val lists = mutableListOf<HomePageList>()

        val channels = root.optJSONArray("channels")
        if (channels != null) {
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until channels.length()) {
                channels.optJSONObject(i)?.let { channelSearch(it)?.let { r -> items.add(r) } }
            }
            if (items.isNotEmpty()) {
                lists.add(HomePageList("قنوات البث المباشر", items, isHorizontalImages = true))
            }
        }

        val slides = root.optJSONArray("slides")
        if (slides != null) {
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until slides.length()) {
                val poster = slides.optJSONObject(i)?.optJSONObject("poster") ?: continue
                posterSearch(poster)?.let { items.add(it) }
            }
            if (items.isNotEmpty()) {
                lists.add(HomePageList("مميز", items, isHorizontalImages = true))
            }
        }

        val genres = root.optJSONArray("genres")
        if (genres != null) {
            for (i in 0 until genres.length()) {
                val genre = genres.optJSONObject(i) ?: continue
                val items = parsePosters(genre.optJSONArray("posters"))
                if (items.isNotEmpty()) {
                    lists.add(
                        HomePageList(
                            genre.optString("title").ifBlank { "قائمة" },
                            items,
                            isHorizontalImages = true
                        )
                    )
                }
            }
        }

        return newHomePageResponse(lists)
    }

    // ---------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        try {
            val text = app.get(api("search/${enc(query)}/0"), headers = headers).text.trim()
            if (!text.startsWith("{")) {
                android.util.Log.i(TAG, "search non-JSON -> ${text.take(120)}")
                return out
            }
            val root = JSONObject(text)
            out.addAll(parsePosters(root.optJSONArray("posters")))
            val channels = root.optJSONArray("channels")
            if (channels != null) {
                for (i in 0 until channels.length()) {
                    channels.optJSONObject(i)?.let { channelSearch(it)?.let { r -> out.add(r) } }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "search FAILED -> $e")
        }
        return out
    }

    // ---------------------------------------------------------------- load

    override suspend fun load(url: String): LoadResponse {
        val id = url.trimEnd('/').substringAfterLast('/').takeWhile { it.isDigit() }
        val isSerie = url.contains("/serie/")
        val isChannel = url.contains("/channel/")
        if (id.isEmpty()) return errorResponse("معرّف غير صالح.")

        if (isChannel) {
            val channel = getJson(api("channel/by/$id"))
                ?: return errorResponse("تعذّر تحميل القناة.")
            return newMovieLoadResponse(
                channel.optString("title").ifBlank { "قناة" },
                url,
                TvType.Live,
                "$CHANNEL_DATA$id"
            ) {
                this.posterUrl = channel.optString("image").ifBlank { null }
                this.plot = channel.optString("description").ifBlank { null }
            }
        }

        val poster = getJson(api("movie/by/$id")) ?: return errorResponse("تعذّر تحميل التفاصيل.")
        val title = poster.optString("title").ifBlank { "بدون عنوان" }
        val image = poster.optString("image").ifBlank { poster.optString("cover") }.ifBlank { null }
        val plot = poster.optString("description").ifBlank { null }
        val year = poster.optString("year").take(4).toIntOrNull()
        val tags = poster.optString("classification")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val serie = isSerie || normalizeType(poster.optString("type")) == "serie"

        if (!serie) {
            return newMovieLoadResponse(title, url, TvType.Movie, "$MOVIE_DATA$id") {
                this.posterUrl = image
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = getArray(api("season/by/serie/$id")) ?: JSONArray()
        for (s in 0 until seasons.length()) {
            val season = seasons.optJSONObject(s) ?: continue
            // API array order can differ from the real season number, so trust the
            // season's own title; fall back to counting down from the newest.
            val seasonNumber = parseSeasonNumber(season.optString("title"))
                ?: (seasons.length() - s)
            val eps = season.optJSONArray("episodes") ?: continue
            for (e in 0 until eps.length()) {
                val ep = eps.optJSONObject(e) ?: continue
                val epId = ep.optInt("id", 0)
                if (epId <= 0) continue
                episodes.add(
                    newEpisode("$DETAIL_URL$EPISODE_DATA$epId") {
                        this.name = ep.optString("title").ifBlank { "الحلقة ${e + 1}" }
                        this.season = seasonNumber
                        this.episode = e + 1
                        this.posterUrl = image
                        // The API sends the literal string "null" for episodes without a
                        // description, which would otherwise render as "null" in the UI.
                        this.description = ep.optString("description")
                            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                    }
                )
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = image
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun errorResponse(message: String): LoadResponse =
        newMovieLoadResponse(message, mainUrl, TvType.Movie, "") { this.plot = message }

    // ---------------------------------------------------------------- links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.substringAfterLast(':').takeWhile { it.isDigit() }
        if (id.isEmpty()) {
            android.util.Log.i(TAG, "loadLinks unrecognized data=$data")
            return false
        }

        // Sources are resolved in parallel, so guard the shared dedup set and serialise the
        // framework callbacks (they are not documented as thread-safe).
        val seen = HashSet<String>()
        val lock = Any()
        val emit: (ExtractorLink) -> Unit = { link ->
            synchronized(lock) { if (seen.add(link.url)) callback(link) }
        }
        val emitSub: (SubtitleFile) -> Unit = { sub ->
            synchronized(lock) { subtitleCallback(sub) }
        }

        val handled = when {
            data.contains(CHANNEL_DATA) -> {
                val channel = getJson(api("channel/by/$id"))
                val sources = channel?.optJSONArray("sources") ?: JSONArray()
                val list = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
                extractSources(list, emitSub, emit)
                true
            }
            data.contains(MOVIE_DATA) -> {
                loadSources(api("movie/source/by/$id"), emitSub, emit)
                loadSubtitles(api("subtitles/by/movie/$id"), emitSub)
                true
            }
            data.contains(EPISODE_DATA) -> {
                loadSources(api("episode/source/by/$id"), emitSub, emit)
                loadSubtitles(api("subtitles/by/episode/$id"), emitSub)
                true
            }
            else -> {
                android.util.Log.i(TAG, "loadLinks unknown data=$data")
                false
            }
        }
        if (!handled) return false
        return seen.isNotEmpty()
    }

    private suspend fun loadSources(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val body = try {
            app.get(url, headers = headers).text
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "loadSources FAILED $url -> $e")
            return
        }
        val json = decodeSources(body) ?: run {
            android.util.Log.i(TAG, "loadSources undecodable -> ${body.take(120)}")
            return
        }
        val array = try {
            JSONArray(json)
        } catch (e: Throwable) {
            JSONArray()
        }
        val list = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        extractSources(list, subtitleCallback, callback)
    }

    /**
     * Resolves every source concurrently. Each one gets its own [SOURCE_TIMEOUT_MS] budget so a
     * dead/Cloudflare-blocked embed fails on its own instead of holding the player host; working
     * servers are queued first so playable links surface immediately. Results are streamed to the
     * callbacks as each source lands (never batched), and no source is ever silently dropped.
     */
    private suspend fun extractSources(
        sources: List<JSONObject>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (sources.isEmpty()) return
        val ordered = sources.sortedBy { sourcePriority(it.optString("url")) }
        supervisorScope {
            ordered.map { src ->
                async(Dispatchers.IO) {
                    val link = src.optString("url")
                    try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            emitSource(src, subtitleCallback, callback)
                        }
                    } catch (e: Throwable) {
                        android.util.Log.i(TAG, "extract FAILED $link -> $e")
                    }
                }
            }.awaitAll()
        }
    }

    /** Working hosts first, known JS-rendered/dead embeds last (stable, preserves API order). */
    private fun sourcePriority(url: String): Int =
        if ((hostLabelOf(url) ?: "") in DEAD_OR_SLOW_HOSTS) 1 else 0

    private suspend fun emitSource(
        src: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val link = src.optString("url").trim()
        if (link.isEmpty()) return
        // `src.title` is the stream type (m3u8/webm/mov/embed), not the server; use the embed host.
        val label = hostLabelOf(link) ?: src.optString("title").ifBlank { "سيرفر" }
        if (isDirect(link)) {
            val type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback(
                newExtractorLink("DramaWorld - $label", "DramaWorld", link, type) {
                    this.referer = "$mainUrl/"
                }
            )
            return
        }
        // First resolve the ecosystem embeds the official app handles via mawdhou3.com
        // (okhd, vidspeed, morencius, vidaraa, upzur, luluvdo, vidoba, film77, anafast, mp4plus …).
        val resolved = try {
            DramaWorldHostExtractor.extract(link, "DramaWorld - $label", null, null, null, callback)
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "DramaWorldHostExtractor FAILED $link -> $e")
            false
        }
        if (resolved) return
        // Anything else: fall back to CloudStream's global extractor registry.
        try {
            loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "loadExtractor FAILED $link -> $e")
        }
    }

    /** Registrable label for the embed host, e.g. `mp5.okhd.site` -> `okhd`. */
    private fun hostLabelOf(url: String): String? = runCatching {
        val host = java.net.URI(url).host?.substringBefore(':')?.lowercase() ?: return null
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size >= 2) parts[parts.size - 2] else parts.firstOrNull()
    }.getOrNull()

    private fun isDirect(url: String): Boolean =
        Regex("\\.(m3u8|mp4|webm|mkv|mpd)(\\?|#|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private suspend fun loadSubtitles(url: String, callback: (SubtitleFile) -> Unit) {
        val array = getArray(url) ?: return
        for (i in 0 until array.length()) {
            val lang = array.optJSONObject(i) ?: continue
            val language = lang.optString("language")
            val subs = lang.optJSONArray("subtitles") ?: continue
            for (j in 0 until subs.length()) {
                val sub = subs.optJSONObject(j) ?: continue
                val subUrl = sub.optString("url").trim()
                if (subUrl.isEmpty()) continue
                callback(newSubtitleFile(sub.optString("language").ifBlank { language }, subUrl) { })
            }
        }
    }
}

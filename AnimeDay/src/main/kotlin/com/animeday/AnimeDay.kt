package com.animeday

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.anime.day.Utils.NativeLib
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
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

@SuppressLint("HardwareIds")
class AnimeDay : MainAPI() {
    companion object {
        @Volatile
        var appContext: Context? = null

        private const val UA = "okhttp/4.10.0"
        private const val REFERER = "https://www.anime-day.com/"
        private const val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val DEFAULT_API = "https://20042026.site/v3.8/api"
        private const val CONFIG_API = "https://backup-animeday.animegateshorts.workers.dev"

        // Each server is resolved in parallel; a dead or hung one is dropped after this budget
        // instead of stalling the whole player load.
        private const val SOURCE_TIMEOUT_MS = 12_000L

        internal fun randomString(len: Int): String {
            val sb = StringBuilder(len)
            for (i in 0 until len) sb.append(ALPHANUM[Random.nextInt(ALPHANUM.length)])
            return sb.toString()
        }

        private fun safeJson(text: String?): JSONObject? {
            return try {
                if (text.isNullOrBlank()) return null
                val t = text.trim()
                if (!t.startsWith("{")) return null
                JSONObject(t)
            } catch (_: Throwable) {
                null
            }
        }
    }

    override var lang = "ar"
    override var mainUrl = DEFAULT_API
    override var name = "AnimeDay"
    override val usesWebView = false
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.AnimeMovie
    )

    @Volatile
    private var resolvedApi: String? = null

    private suspend fun resolveApi(): String {
        resolvedApi?.let { return it }
        val fetched = tryFetchCloudday()
        if (fetched != null) {
            synchronized(this) {
                if (resolvedApi == null) resolvedApi = fetched
            }
            return fetched
        }
        return DEFAULT_API
    }

    private suspend fun tryFetchCloudday(): String? {
        return try {
            val body = app.get(CONFIG_API).text
            val obj = safeJson(body) ?: return null
            val data = obj.optJSONArray("data")
            val first = data?.optJSONObject(0)
            val base = first?.optString("cloudday").orEmpty()
                .ifEmpty { obj.optJSONObject("data")?.optString("cloudday").orEmpty() }
            base.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { it.trimEnd('/') + "/v3.8/api" }
        } catch (_: Throwable) {
            null
        }
    }

    private fun plainHeaders() = AnimeDayExtractors.plainHeaders()

    private fun androidId(): String {
        val ctx = appContext
        val id = try {
            if (ctx != null) Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) else null
        } catch (_: Throwable) {
            null
        }
        return if (id.isNullOrEmpty()) randomString(16) else id
    }

    private fun freshToken(): String? {
        val ctx = appContext ?: return null
        return try {
            android.util.Log.i("AnimeDayNative", "freshToken: calling buildSecure")
            val t = NativeLib.secureId(ctx)
            android.util.Log.i("AnimeDayNative", "freshToken: got token len=" + (t?.length ?: -1) + " prefix=" + t?.take(24))
            t
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayNative", "freshToken: threw " + e)
            null
        }
    }

    /** Headers used for the encrypted /servers endpoints. Returns the cloudflare-id too. */
    private fun serverHeaders(): Pair<Map<String, String>, String> {
        val cf = randomString(15) + androidId()
        val headers = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "cloudflare-id" to cf
        )
        freshToken()?.let { headers["firebase_id"] = it }
        return headers to cf
    }

    private fun toAsciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** The API does not expose episode numbers, so parse them from the title (e.g. "الحلقة 8"). */
    private fun episodeNumber(title: String?, fallback: Int): Int {
        if (title.isNullOrBlank()) return fallback
        val match = Regex("(\\d{1,4})").find(toAsciiDigits(title)) ?: return fallback
        return match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: fallback
    }

    private fun normalizeType(t: String?): TvType = when (t) {
        "serie", "series", "tv", "tvshow" -> TvType.TvSeries
        "anime" -> TvType.Anime
        "cartoon" -> TvType.Cartoon
        else -> TvType.Movie
    }

    private suspend fun detailPath(type: String?, id: String): String =
        if (normalizeType(type) == TvType.Movie) "${resolveApi()}/movie/$id" else "${resolveApi()}/series/$id"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pages = mutableListOf<com.lagradost.cloudstream3.HomePageList>()
        try {
            val json = safeJson(app.get("${resolveApi()}/home", headers = plainHeaders()).text)
            val sections = json?.optJSONArray("sections") ?: JSONArray()
            for (i in 0 until sections.length()) {
                val sec = sections.optJSONObject(i) ?: continue
                val items = sec.optJSONArray("section_items") ?: JSONArray()
                val list = mutableListOf<SearchResponse>()
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val type = item.optString("type", "movie")
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    if (type == "episode") {
                        val showId = item.optString("tv_show_id")
                        if (showId.isEmpty()) continue
                        list.add(
                            newMovieSearchResponse(
                                name = item.optString("tv_show_name").ifEmpty { item.optString("name") },
                                url = "${resolveApi()}/series/$showId",
                                type = TvType.TvSeries
                            ) {
                                this.posterUrl = item.optString("poster").ifEmpty { null }
                            }
                        )
                    } else {
                        val tvType = normalizeType(type)
                        list.add(
                            newMovieSearchResponse(
                                name = item.optString("name"),
                                url = detailPath(type, id),
                                type = tvType
                            ) {
                                this.posterUrl = item.optString("poster").ifEmpty { null }
                                this.year = item.optString("release_date").take(4).toIntOrNull()
                            }
                        )
                    }
                }
                if (list.isNotEmpty()) pages.add(com.lagradost.cloudstream3.HomePageList(sec.optString("section_name"), list))
            }
        } catch (_: Throwable) {
        }
        return newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val json = safeJson(
                app.post(
                    "${resolveApi()}/search",
                    headers = plainHeaders() + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    data = mapOf("title" to query, "type" to "0", "sort" to "1", "page" to "1")
                ).text
            ) ?: return emptyList()
            val arr = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val id = item.optString("id")
                if (id.isEmpty()) continue
                val tvType = normalizeType(type)
                results.add(
                    newMovieSearchResponse(
                        name = item.optString("name"),
                        url = detailPath(type, id),
                        type = tvType
                    ) {
                        this.posterUrl = item.optString("poster").ifEmpty { null }
                        this.year = item.optString("year", "0").toIntOrNull()
                    }
                )
            }
        } catch (_: Throwable) {
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = url.trimEnd('/')
        val id = clean.substringAfterLast("/").substringBefore("?")
        val isSeries = clean.contains("/series/") || clean.contains("/serie/") ||
                clean.contains("/tv/") || clean.contains("/episode/")

        return if (isSeries) loadSeries(clean, id) else loadMovie(clean, id)
    }

    private suspend fun loadSeries(url: String, id: String): LoadResponse {
        var showId = id
        if (url.contains("/episode/")) {
            // legacy episode detail URL: resolve its series id when possible
            val ep = fetchJson("${resolveApi()}/episode/$id", withToken = true)
            showId = ep?.optJSONObject("episode")?.optJSONObject("series")?.optString("id").orEmpty().ifEmpty { id }
        }

        val epsJson = fetchJson("${resolveApi()}/series/$showId/episodes", withToken = false)
            ?: fetchJson("${resolveApi()}/serie/$showId/episodes", withToken = false)

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        val seasons = epsJson?.optJSONArray("seasons") ?: JSONArray()
        for (s in 0 until seasons.length()) {
            val season = seasons.optJSONObject(s) ?: continue
            val seasonNum = season.optString("season_number", "1").toIntOrNull() ?: (s + 1)
            val eps = season.optJSONArray("episodes") ?: JSONArray()
            for (e in 0 until eps.length()) {
                val ei = eps.optJSONObject(e) ?: continue
                val epId = ei.optString("id")
                if (epId.isEmpty()) continue
                val title = ei.optString("title").ifEmpty { ei.optString("name") }
                val explicit = ei.optString("episode_number", "").toIntOrNull()
                episodes.add(
                    newEpisode("${resolveApi()}/episode/$epId/servers") {
                        this.name = title
                        this.season = seasonNum
                        this.episode = explicit?.takeIf { it > 0 } ?: episodeNumber(title, e + 1)
                        this.posterUrl = ei.optString("image").ifEmpty { ei.optString("cover") }.ifEmpty { null }
                    }
                )
            }
        }
        episodes.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        val detail = fetchJson("${resolveApi()}/series/$showId", withToken = true)?.optJSONObject("series")
        val name = detail?.optString("name").orEmpty().ifEmpty { "مسلسل $showId" }
        val poster = detail?.optString("poster").orEmpty()
        val backdrop = detail?.optString("backdrop").orEmpty()
        val plot = detail?.optString("overview").orEmpty()

        return newTvSeriesLoadResponse(name, "${resolveApi()}/series/$showId", TvType.TvSeries, episodes) {
            this.posterUrl = poster.ifEmpty { backdrop }.ifEmpty { null }
            this.plot = plot.ifEmpty { null }
        }
    }

    private suspend fun loadMovie(url: String, id: String): LoadResponse {
        val movie = fetchJson("${resolveApi()}/movie/$id", withToken = true)?.optJSONObject("movie")
        val name = movie?.optString("name").orEmpty().ifEmpty { "فيلم $id" }
        val poster = movie?.optString("poster").orEmpty()
        val backdrop = movie?.optString("backdrop").orEmpty()
        val plot = movie?.optString("overview").orEmpty()
        return newMovieLoadResponse(name, "${resolveApi()}/movie/$id", TvType.Movie, "${resolveApi()}/movie/$id/servers") {
            this.posterUrl = poster.ifEmpty { backdrop }.ifEmpty { null }
            this.plot = plot.ifEmpty { null }
        }
    }

    private suspend fun fetchJson(endpoint: String, withToken: Boolean): JSONObject? {
        return try {
            val headers = if (withToken) {
                val (h, _) = serverHeaders()
                h
            } else plainHeaders()
            val res = app.get(endpoint, headers = headers)
            val body = res.text
            android.util.Log.i("AnimeDay", "fetchJson GET $endpoint token=$withToken -> ${res.code} len=${body.length} body=${body.take(240)}")
            safeJson(body)?.takeIf { !it.optBoolean("blocked", false) }
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDay", "fetchJson GET $endpoint FAILED: $e")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        try {
            val (headers, cf) = serverHeaders()
            android.util.Log.i("AnimeDay", "loadLinks GET $data cf=$cf")
            val res = app.get(data, headers = headers, allowRedirects = true)
            val raw = res.text
            android.util.Log.i("AnimeDay", "loadLinks -> ${res.code} len=${raw.length} body=${raw.take(240)}")
            if (raw.isBlank()) return false

            val decrypted = if (raw.trim().startsWith("{") || raw.trim().startsWith("[")) {
                raw
            } else {
                val ctx = appContext
                val keyArg = cf.substring(15)
                val out = if (ctx != null) NativeLib.decrypt(ctx, raw.trim(), keyArg) else null
                out ?: raw
            }
            android.util.Log.i("AnimeDay", "loadLinks decrypted len=${decrypted.length} head=${decrypted.take(240)}")

            val json = safeJson(decrypted) ?: run {
                android.util.Log.i("AnimeDay", "loadLinks: JSON parse failed")
                return false
            }
            val servers = json.optJSONArray("servers") ?: JSONArray()
            android.util.Log.i("AnimeDay", "loadLinks: status=${json.optString("status")} servers=${servers.length()} keys=${json.keys().asSequence().toList()}")
            val seen = HashSet<String>()
            val lock = Any()
            val emit: (ExtractorLink) -> Unit = { link ->
                synchronized(lock) { if (seen.add(link.url)) callback(link) }
            }
            val emitSub: (SubtitleFile) -> Unit = { sub -> synchronized(lock) { subtitleCallback(sub) } }

            // Pre-parse every server, then resolve them concurrently. A dead embed fails on its
            // own budget (SOURCE_TIMEOUT_MS) instead of holding up the rest; the framework
            // callbacks are serialised because they are not documented as thread-safe.
            val parsed = (0 until servers.length()).mapNotNull { i ->
                val srv = servers.optJSONObject(i) ?: return@mapNotNull null
                val rawLink = srv.optString("link").trim().replace("\\s+".toRegex(), "")
                if (rawLink.isEmpty()) return@mapNotNull null

                val link = if (rawLink.startsWith("http")) {
                    rawLink
                } else {
                    REFERER.trimEnd('/') + "/" + rawLink.trimStart('/')
                }
                val referer = srv.optString("referer")
                    .ifEmpty { srv.optString("origin") }
                    .ifEmpty { REFERER }
                val label = srv.optString("title")
                    .ifEmpty { srv.optString("name") }
                    .ifEmpty { name }
                val quality = srv.optString("height").toIntOrNull()
                Server(link, referer, label, quality)
            }

            val results = supervisorScope {
                parsed.map { s ->
                    async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                AnimeDayExtractors.emit(s.link, s.referer, s.label, s.quality, emitSub, emit)
                            } ?: false
                        } catch (e: Throwable) {
                            android.util.Log.i("AnimeDay", "emit FAILED ${s.link.take(100)} -> $e")
                            false
                        }
                    }
                }.awaitAll()
            }
            if (results.any { it }) found = true
        } catch (_: Throwable) {
        }
        return found
    }

    private data class Server(val link: String, val referer: String, val label: String, val quality: Int?)
}

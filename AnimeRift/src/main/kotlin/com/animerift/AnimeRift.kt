package com.animerift

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * AnimeRift — واجهة عربية كاملة لتطبيق الأنمي الرسمي.
 *
 * تعتمد الإضافة على نفس بوابة الـ API التي يستخدمها التطبيق الأصلي
 * (`gateway.anime-rift.com/api/v4`)، وتعرض لكل حلقة كل المصادر المتاحة مع
 * التمييز الواضح بين **المترجم** و**المدبلج** العربية وبجودة كل مصدر.
 */
class AnimeRift(private val context: android.content.Context) : MainAPI() {

    override var mainUrl = "https://www.anime-rift.com"
    override var name = "أنمي رِفت (AnimeRift)"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.Cartoon,
        TvType.TvSeries, TvType.Movie, TvType.OVA
    )

    private val api = RiftApi(context)

    companion object {
        const val TAG = "AnimeRift"

        /** أقصى عدد روابط تُعرض من المصادر غير العربية. */
        private const val MAX_FOREIGN_LINKS = 6

        /** مدة بقاء روابط الحلقة في الذاكرة (ms). */
        private const val LINK_CACHE_TTL = 10 * 60_000L
    }

    /** روابط آخر فتحات للحلقات — لتفادي طلبات متكررة من البوابة. */
    private val linkCache = LinkedHashMap<String, Pair<Long, List<Resolved>>>()

    override val mainPage = mainPageOf(
        "$mainUrl/home" to "الرئيسية",
        "$mainUrl/browse" to "تصفح المكتبة",
    )

    // =====================================================================
    // Search
    // =====================================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val json = api.search(query) ?: return emptyList()
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<SearchResponse>(items.length())
        for (i in 0 until items.length()) {
            toSearchResponse(items.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    // =====================================================================
    // Main page
    // =====================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sets = ArrayList<HomePageList>()

        if (request.data.endsWith("/browse")) {
            val json = api.browse(page)
            val items = json?.optJSONArray("items")
            if (items != null) {
                val list = ArrayList<SearchResponse>(items.length())
                for (i in 0 until items.length()) {
                    toSearchResponse(items.optJSONObject(i))?.let { list.add(it) }
                }
                if (list.isNotEmpty()) {
                    sets.add(HomePageList("المكتبة — صفحة ${page + 1}", list))
                }
            }
            return newHomePageResponse(sets, json?.optBoolean("hasNext") == true)
        }

        val home = api.homeContent()
        val sections = home?.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val title = section.optString("sectionTitle").ifBlank { continue }
                val items = section.optJSONArray("items") ?: continue
                val list = ArrayList<SearchResponse>(items.length())
                for (j in 0 until items.length()) {
                    toSearchResponse(items.optJSONObject(j))?.let { list.add(it) }
                }
                if (list.isNotEmpty()) sets.add(HomePageList(title, list))
            }
        }
        return newHomePageResponse(sets, false)
    }

    // =====================================================================
    // Series details + episode list
    // =====================================================================

    override suspend fun load(url: String): LoadResponse? {
        val animeId = animeIdFromUrl(url) ?: return null

        val details = api.details(animeId)?.optJSONObject("item") ?: return null
        val episodesJson = api.episodes(animeId)?.optJSONArray("items")

        val title = details.optJSONObject("title_translations")?.optString("ar")?.ifBlank { null }
            ?: details.optString("title")
        val poster = details.optString("main_picture").ifBlank {
            details.optString("large_picture").ifBlank { details.optString("medium_picture") }
        }
        val plot = details.optString("synopsis").trim()

        val episodes = ArrayList<com.lagradost.cloudstream3.Episode>()
        if (episodesJson != null) {
            for (i in 0 until episodesJson.length()) {
                val ep = episodesJson.optJSONObject(i) ?: continue
                val episodeId = ep.optString("_id")
                if (episodeId.isBlank()) continue
                val number = ep.optInt("episode_number", i + 1)
                val translations = ep.optJSONObject("title_translations")
                val arName = translations?.optString("ar").orEmpty().ifBlank { "" }
                val enName = translations?.optString("en").orEmpty().ifBlank { "" }
                val epName = arName.ifBlank { enName }.ifBlank { "الحلقة $number" }

                episodes.add(
                    newEpisode(episodeUrl(animeId, episodeId, number)) {
                        this.name = epName
                        this.episode = number
                        this.posterUrl = ep.optString("thumbnail")
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, contentType(details), episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun contentType(details: JSONObject): TvType =
        when (details.optString("content_type").lowercase()) {
            "movie" -> TvType.Movie
            "ova" -> TvType.OVA
            "tv" -> TvType.TvSeries
            "ona" -> TvType.Anime
            else -> TvType.Anime
        }

    // =====================================================================
    // Watch links
    // =====================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseEpisodeUrl(data) ?: return false

        // نفس الحلقة لا تُفتح مرتين: النتيجة تُخزَّن مؤقتاً.
        val cached = linkCache[parsed.cacheKey]
        if (cached != null && cached.second.isNotEmpty()) {
            if (System.currentTimeMillis() - cached.first > LINK_CACHE_TTL) {
                linkCache.remove(parsed.cacheKey)
            } else {
                android.util.Log.i(TAG, "loadLinks ${parsed.episodeId} -> من الذاكرة (${cached.second.size})")
                for (entry in cached.second) callback(entry.link)
                return true
            }
        }

        val sources = api.sources(parsed.animeId, parsed.episodeId, parsed.episodeNumber)
            ?: return false

        val items = sources.optJSONArray("items") ?: return false
        val configs = parseConfigs(sources)

        // اختيار المصادر بميزانية طلبات (SourceSelector) — فتح كل المصادر
        // دفعة واحدة هو ما يتجاوز سقف البوابة (429 ثم حظر 403).
        val selected = SourceSelector.select(items)

        // المرحلة 1: طلبات البوابة (can_play + direct_link) — مُبعثرة داخلياً
        // بواسطة مُخمِّد البوابة نفسه فلا تتجاوز السقف أبداً.
        val pending = coroutineScope {
            selected.map { choice ->
                async(Dispatchers.IO) { resolveSource(choice, parsed, configs) }
            }.awaitAll()
        }.flatten()

        // المرحلة 2: تذاكر الستريم تيب تُستبدل بروابطها بعد `wait_time`
        // واحد فقط لكل التذاكر (وليس 5 ثوانٍ لكل تذكرة).
        val tickets = pending.filter { it.ticket != null }
        if (tickets.isNotEmpty()) {
            val wait = tickets.maxOf { it.ticket!!.waitTime }
            if (wait > 0) kotlinx.coroutines.delay(wait * 1000L)
            coroutineScope {
                tickets.map { entry ->
                    async(Dispatchers.IO) {
                        val t = entry.ticket!!
                        val resolved = api.resolveTicket(t.fileId, t.ticket, 0)
                        if (resolved != null) {
                            entry.url = resolved.first
                            entry.fileName = resolved.second
                        }
                    }
                }.awaitAll()
            }
        }

        val resolved = pending.mapNotNull { it.build() }

        if (resolved.isEmpty()) {
            android.util.Log.i(TAG, "loadLinks ${parsed.episodeId} -> no source")
            return false
        }

        // نفس الرابط قد يعود أكثر من مرة (لأن الـ API لا تلتزم دائماً
        // بالجودة المطلوبة) — نُبقي أول نسخة فقط.
        val seen = HashSet<String>()
        var foreign = 0
        var emitted = 0
        val out = ArrayList<Resolved>(resolved.size)
        for (entry in resolved) {
            val key = "${entry.url}|${entry.label}"
            if (!seen.add(key)) continue
            if (!entry.isArabic && foreign >= MAX_FOREIGN_LINKS) continue
            if (!entry.isArabic) foreign++
            out.add(entry)
            callback(entry.link)
            emitted++
        }
        if (out.isNotEmpty()) {
            linkCache[parsed.cacheKey] = System.currentTimeMillis() to out
        }
        android.util.Log.i(TAG, "loadLinks ${parsed.episodeId} -> $emitted links")
        return emitted > 0
    }

    private data class Resolved(
        val link: ExtractorLink,
        val isArabic: Boolean,
        val url: String,
        val label: String
    )

    private class Ticket(val fileId: String, val ticket: String, val waitTime: Int)

    /** مصدر واحد بعد مرحلته الأولى: إما رابط مباشر أو تذكرة ستريم تيب. */
    private inner class Pending(
        val provider: String,
        val server: String,
        val requested: String,
        val fallbackTrack: String,
        val downloadAllowed: Boolean,
        val response: JSONObject
    ) {
        var ticket: Ticket? = null
        var url: String = ""
        var fileName: String = ""

        suspend fun build(): Resolved? {
            if (url.isBlank()) return null

            // >>> التسمية تُشتق من الرابط نفسه وليس من الجودة المطلوبة: <<<
            // البوابة لا تلتزم دائماً بالجودة المطلوبة وتعيد رابطاً بجودة
            // أخرى، فالـ Link هو الحقيقة الوحيدة. مثال:
            //   .../1/ar_dub/720p/v1/master.mp4      -> مدبلج عربي 720p
            //   .../naruto_ep_1_360p_ar_dub.m3u8    -> مدبلج عربي 360p
            //   "... مدبلج عربي [720p].mp4"          -> مدبلج عربي 720p
            val info = trackInfo(url, fileName, requested, fallbackTrack)
            val track = trackLabel(info.track)
            val quality = info.quality.ifBlank { requested }
            val label = if (quality.isBlank()) "$track • $server" else "$track • $server • $quality"
            val arabic = info.track.startsWith("ar_")

            val isHls = url.contains(".m3u8")

            // بعض المصادر (مثل rift-streamer) ترفض الطلب إلا مع الترويسات
            // التي تعيدها البوابة (x-player / x-device-id).
            val linkHeaders = buildMap {
                put(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                val extra = response.optJSONObject("http_headers")
                if (extra != null) {
                    for (key in extra.keys()) {
                        val value = extra.optString(key)
                        if (value.isNotBlank()) put(key, value)
                    }
                }
            }

            val main = newExtractorLink(
                source = name,
                name = label,
                url = url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = qualityValue(quality, url)
                this.headers = linkHeaders
            }

            val result = ArrayList<Resolved>(2)
            result.add(Resolved(main, arabic, url, label))

            // رابط التحميل متاح فقط للخوادم التي يسمح التطبيق بالتحميل منها.
            if (downloadAllowed && !isHls) {
                result.add(
                    Resolved(
                        newExtractorLink(
                            source = name,
                            name = "$label — تحميل",
                            url = url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = qualityValue(quality, url)
                            this.headers = linkHeaders
                        },
                        arabic,
                        url,
                        "$label — تحميل"
                    )
                )
            }
            return result.first()
        }
    }

    /**
     * يطلب رابط المشاهدة لمصدر واحد: يخبر الخادم أن الحلقة مطلوبة
     * (`can_play`) ثم يطلب الرابط المباشر (`direct_link`).
     */
    private suspend fun resolveSource(
        choice: SourceSelector.Choice,
        episode: EpisodeRef,
        configs: Map<String, JSONObject>
    ): List<Pending> {
        val item = choice.item
        val hostId = item.optString("_id")
        if (hostId.isBlank()) return emptyList()
        val provider = item.optString("provider")
        val server = item.optString("server_name").ifBlank { provider.uppercase() }

        // السيرفرات من نوع "realtime" لا تُحدَّد لها جودة، وتقبل الطلب بدون
        // حقل الجودة إطلاقاً.
        val qualityList = if (choice.qualities.isEmpty()) listOf("") else choice.qualities

        val config = configs[provider]
        val downloadAllowed = config?.optBoolean("in_app_download_disabled") != true

        val out = ArrayList<Pending>(qualityList.size)
        for (requested in qualityList) {
            // كل جودة تحتاج جلسة خاصة: الـ sessionId الذي تعيده `can_play`
            // يُستهلك في أول طلب `direct_link`، وتعيد البوابة بعده
            // SESSION_ID_INVALID.
            val play = api.canPlay(episode.episodeId, hostId) ?: continue
            val sessionId = play.optString("sessionId").ifBlank { null }
            val link = api.directLink(hostId, requested.ifBlank { null }, sessionId) ?: continue

            val pending = Pending(
                provider = provider,
                server = server,
                requested = requested,
                fallbackTrack = item.optString("sub_title"),
                downloadAllowed = downloadAllowed,
                response = link
            )
            pending.url = link.optString("videoUrl")
            if (pending.url.isBlank() && link.optBoolean("ticket_response")) {
                pending.ticket = Ticket(
                    link.optString("fileId"),
                    link.optString("ticket"),
                    link.optInt("wait_time", 5)
                )
            }
            if (pending.url.isBlank() && pending.ticket == null) continue
            out.add(pending)
        }
        return out
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private data class EpisodeRef(
        val animeId: String,
        val episodeId: String,
        val episodeNumber: Int
    ) {
        val cacheKey: String get() = "$animeId|$episodeId|$episodeNumber"
    }

    private fun animeIdFromUrl(url: String): String? {
        val id = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        return if (id.isNotBlank() && id.all { it.isLetterOrDigit() }) id else null
    }

    private fun episodeUrl(animeId: String, episodeId: String, number: Int): String =
        "$mainUrl/watch/$animeId/$episodeId?ep=$number"

    private fun parseEpisodeUrl(url: String): EpisodeRef? {
        val path = url.substringAfter("$mainUrl/watch/").substringBefore('?')
        val parts = path.split('/')
        if (parts.size < 2) return null
        val animeId = parts[0]
        val episodeId = parts[1]
        if (animeId.isBlank() || episodeId.isBlank()) return null
        val number = Regex("ep=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return EpisodeRef(animeId, episodeId, number)
    }

    private fun parseConfigs(json: JSONObject): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        val array = json.optJSONArray("configs") ?: return out
        for (i in 0 until array.length()) {
            val cfg = array.optJSONObject(i) ?: continue
            val key = cfg.optString("server_key")
            if (key.isNotBlank()) out[key] = cfg
        }
        return out
    }

    private fun toSearchResponse(item: JSONObject?): SearchResponse? {
        if (item == null) return null
        val id = item.optString("_id")
        if (id.isBlank()) return null
        val title = item.optJSONObject("title_translations")?.optString("ar")?.ifBlank { null }
            ?: item.optString("title")
        val poster = item.optString("main_picture").ifBlank {
            item.optString("large_picture").ifBlank { item.optString("medium_picture") }
        }
        val type = when (item.optString("content_type").lowercase()) {
            "movie" -> TvType.Movie
            "ova" -> TvType.OVA
            "ona" -> TvType.Anime
            "tv" -> TvType.TvSeries
            else -> TvType.Anime
        }
        return newAnimeSearchResponse(title, "$mainUrl/anime/$id", type) {
            this.posterUrl = poster
        }
    }

    private fun trackLabel(track: String): String = when (track) {
        "ar_sub" -> "مترجم عربي"
        "ar_dub" -> "مدبلج عربي"
        "en_sub" -> "مترجم إنجليزي"
        "en_dub" -> "مدبلج إنجليزي"
        "it_sub" -> "مترجم إيطالي"
        "it_dub" -> "مدبلج إيطالي"
        "fr_sub" -> "مترجم فرنسي"
        "fr_dub" -> "مدبلج فرنسي"
        "de_sub" -> "مترجم ألماني"
        "de_dub" -> "مدبلج ألماني"
        "sp_sub" -> "مترجم إسباني"
        "sp_dub" -> "مدبلج إسباني"
        "" -> "مصدر"
        else -> track
    }

    private fun qualityValue(label: String, url: String): Int {
        val s = (label + " " + url).lowercase()
        return when {
            s.contains("2160") || s.contains("4k") -> Qualities.P2160.value
            s.contains("1080") || s.contains("hevc") -> Qualities.P1080.value
            s.contains("720") -> Qualities.P720.value
            s.contains("480") -> Qualities.P480.value
            s.contains("360") -> Qualities.P360.value
            s.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private data class TrackInfo(val track: String, val quality: String)

    /**
     * يستخرج (نوع الصوت/الترجمة + الجودة) من الرابط الحقيقي واسم الملف.
     * البوابة لا تحترم دائماً الجودة المطلوبة، لذا نقرأ من الـ URL:
     *   - "/1/ar_dub/720p/v1/master.mp4"        => ar_dub / 720p
     *   - "/naruto_ep_1_360p_ar_dub.m3u8"      => ar_dub / 360p
     *   - "... مدبلج عربي [720p].mp4"           => ar_dub / 720p
     *   - "NarutoShippuden_Ep_001_SUB_ITA.mp4"  => it_sub
     */
    private fun trackInfo(
        url: String,
        fileName: String,
        requested: String,
        fallbackTrack: String
    ): TrackInfo {
        val path = url.substringBefore('?')
        val tail = path.substringAfterLast('/').lowercase()
        val name = fileName.lowercase()
        var track = ""
        var quality = ""

        // 1) الصيغة العربية داخل اسم الملف.
        if (name.contains("مدبلج عربي")) track = "ar_dub"
        else if (name.contains("مترجم عربي")) track = "ar_sub"

        // 2) "<quality>_<track>" في اسم الملف (مثال: naruto_ep_1_360p_ar_dub).
        Regex("(\\d{3,4}p)_(ar|en|it|de|fr|sp)_(sub|dub)").find(tail)?.let {
            quality = it.groupValues[1]
            if (track.isBlank()) track = "${it.groupValues[2]}_${it.groupValues[3]}"
        }

        // 3) "/<track>/<quality>/" في المسار.
        Regex("/(ar|en|it|de|fr|sp)_(sub|dub)/(\\d{3,4}p)/").find(path)?.let {
            if (track.isBlank()) track = "${it.groupValues[1]}_${it.groupValues[2]}"
            if (quality.isBlank()) quality = it.groupValues[3]
        }

        // 4) الجودة من اسم الملف ([720p]) أو من آخر مقطع.
        if (quality.isBlank()) {
            Regex("(\\d{3,4})p").find(name)?.let { quality = "${it.groupValues[1]}p" }
        }
        if (quality.isBlank()) {
            Regex("(\\d{3,4})p").find(tail)?.let { quality = "${it.groupValues[1]}p" }
        }

        // 5) نوع المسار من آخر مقطع أو المسار.
        if (track.isBlank()) {
            Regex("(ar|en|it|de|fr|sp)_(sub|dub)").find(path)?.let { track = it.value }
        }
        if (track.isBlank()) {
            when {
                tail.contains("_ita") || tail.contains("ita.") -> track = "it_sub"
                tail.contains("_arb") || tail.contains("_ara") -> track = "ar_sub"
                tail.contains("_dub") || tail.contains("-dub") -> track = "ar_dub"
                tail.contains("_sub") || tail.contains("-sub") -> track = "ar_sub"
            }
        }

        // لم نتعرّف على النوع من الرابط: نعود إلى نوع المسار كما ذكره المصدر.
        if (track.isBlank()) track = fallbackTrack
        if (quality.isBlank()) quality = requested
        return TrackInfo(track, quality.uppercase())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val v = optString(i)
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }
}

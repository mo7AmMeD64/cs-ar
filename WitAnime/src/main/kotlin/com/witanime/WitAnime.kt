package com.witanime

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WitAnime : MainAPI() {
    override var mainUrl: String
        get() = WitAnimeNetwork.mainUrl
        set(_) {}

    override var name = "ويت أنمي (WitAnime)"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Cartoon
    )

    companion object {
        private val WATCH_EP_REGEX = Regex("""/watch/(?!movie/)([^/?#]+)/([^/?#]+)""")
        private val WATCH_MOVIE_REGEX = Regex("""/watch/movie/([^/?#]+)""")
        private val ANIME_SLUG_REGEX = Regex("""/anime/([^/?#]+)""")
        private val MOVIE_SLUG_REGEX = Regex("""/movie/([^/?#]+)""")
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val SCORE_REGEX = Regex("""\b(\d\.\d{1,2}|10\.0)\b""")
        private val DURATION_MIN_REGEX = Regex("""(\d+)\s*دقيق""")
        private val ISO_DURATION_REGEX = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""", RegexOption.IGNORE_CASE)

        private fun normalizeArabicDigits(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                if (ch in '٠'..'٩') {
                    sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun urlDecodeSafe(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }

    override val mainPage = mainPageOf(
        "/new-episodes" to "أحدث الحلقات المضافة",
        "" to "الأكثر مشاهدة والمختارات",
        "/seasonal" to "أنميات الموسم الحالي",
        "/browse" to "قائمة الأنمي",
        "/movies" to "أفلام الأنمي",
        "/schedule" to "جدول مواعيد العرض"
    )

    // ── Card & Listing Parsers ───────────────────────────────────────────────

    private fun Element.extractCardPoster(): String? {
        val img = selectFirst("img:not([aria-hidden=true])[src], img[src]") ?: return null
        val src = img.attr("src").trim().takeIf { it.isNotEmpty() && !it.startsWith("data:") }
            ?: img.attr("data-src").trim().takeIf { it.isNotEmpty() }
            ?: return null
        return WitAnimeNetwork.resolveUrl(src, mainUrl)
    }

    private fun Element.extractCardTitle(fallbackSlug: String? = null): String? {
        val candidates = listOfNotNull(
            selectFirst("h3, h2")?.text()?.trim(),
            selectFirst("img[alt!=''][alt]")?.attr("alt")?.trim(),
            attr("title").trim().takeIf { it.isNotEmpty() },
            selectFirst("span.line-clamp-2, p.line-clamp-2, span.truncate")?.text()?.trim()
        )
        val picked = candidates.firstOrNull {
            it.isNotBlank() &&
                it != "شاهد الآن" &&
                it != "التفاصيل" &&
                !it.startsWith("الحلقة ")
        }
        if (!picked.isNullOrBlank()) return picked.replace(Regex("""\s+"""), " ").trim()

        return fallbackSlug
            ?.let { urlDecodeSafe(it).replace('-', ' ').trim() }
            ?.takeIf { it.isNotBlank() }
    }

    private fun Element.toSearchCard(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val rawHref = anchor.attr("href").trim()
        if (rawHref.isEmpty()) return null
        val fullHref = WitAnimeNetwork.resolveUrl(rawHref, mainUrl) ?: return null
        val path = runCatching { URI(fullHref).path.orEmpty() }.getOrDefault(fullHref)

        // Skip navigation / auth / static links
        if (path == "/movies" || path == "/browse" || path == "/seasonal" || path == "/schedule" || path == "/login") {
            return null
        }

        val poster = anchor.extractCardPoster() ?: extractCardPoster()
        val cardText = normalizeArabicDigits(anchor.text())
        val scoreVal = SCORE_REGEX.find(cardText)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it in 1.0..10.0 }
        val yearVal = YEAR_REGEX.find(cardText)?.groupValues?.get(1)?.toIntOrNull()
        val isDub = cardText.contains("مدبلج")

        // Case 1: Episode watch card (`/watch/<slug>/<ep>`) -> canonicalize to `/anime/<slug>` + ep badge!
        WATCH_EP_REGEX.find(path)?.let { m ->
            val animeSlug = m.groupValues[1]
            val epRaw = normalizeArabicDigits(urlDecodeSafe(m.groupValues[2]))
            val epInt = epRaw.toIntOrNull() ?: epRaw.substringBefore('.').toIntOrNull()
            val title = anchor.extractCardTitle(animeSlug) ?: return null
            val animeUrl = "$mainUrl/anime/$animeSlug"
            return newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                if (yearVal != null) this.year = yearVal
                if (scoreVal != null) this.score = Score.from10(scoreVal)
                addDubStatus(isDub, epInt)
            }
        }

        // Case 2: Movie watch or detail card (`/movie/<slug>` or `/watch/movie/<slug>`)
        val movieSlug = MOVIE_SLUG_REGEX.find(path)?.groupValues?.get(1)
            ?: WATCH_MOVIE_REGEX.find(path)?.groupValues?.get(1)
        if (!movieSlug.isNullOrBlank()) {
            val title = anchor.extractCardTitle(movieSlug) ?: return null
            val movieUrl = "$mainUrl/movie/$movieSlug"
            return newMovieSearchResponse(title, movieUrl, TvType.AnimeMovie) {
                this.posterUrl = poster
                if (yearVal != null) this.year = yearVal
                if (scoreVal != null) this.score = Score.from10(scoreVal)
            }
        }

        // Case 3: Anime series card (`/anime/<slug>`)
        ANIME_SLUG_REGEX.find(path)?.let { m ->
            val animeSlug = m.groupValues[1]
            // Skip hero CTA buttons ("التفاصيل" / "شاهد الآن") without poster/title context
            if (poster == null && anchor.selectFirst("h2, h3, span.line-clamp-2") == null) return null
            val title = anchor.extractCardTitle(animeSlug) ?: return null
            val tvType = when {
                cardText.contains("OVA", ignoreCase = true) || cardText.contains("ONA", ignoreCase = true) ||
                    cardText.contains("Special", ignoreCase = true) -> TvType.OVA
                cardText.contains("فيلم") || cardText.contains("Movie", ignoreCase = true) -> TvType.AnimeMovie
                else -> TvType.Anime
            }
            val animeUrl = "$mainUrl/anime/$animeSlug"
            return newAnimeSearchResponse(title, animeUrl, tvType) {
                this.posterUrl = poster
                if (yearVal != null) this.year = yearVal
                if (scoreVal != null) this.score = Score.from10(scoreVal)
                if (isDub) addDubStatus(true)
            }
        }

        return null
    }

    private fun Document.collectCards(selector: String = "a[href*='/anime/'], a[href*='/movie/'], a[href*='/watch/']"): List<SearchResponse> {
        val seen = LinkedHashMap<String, SearchResponse>()
        for (el in select(selector)) {
            val card = el.toSearchCard() ?: continue
            val existing = seen[card.url]
            if (existing == null || (existing.posterUrl.isNullOrBlank() && !card.posterUrl.isNullOrBlank())) {
                seen[card.url] = card
            }
        }
        return seen.values.toList()
    }

    // ── Home Page & Paginated Shelves ────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val route = request.data.trim()

        // 1. Paginated Latest Episodes (`/` for page 1, `/new-episodes?page=N` JSON for page >= 2)
        if (route == "/new-episodes") {
            if (page == 1) {
                val doc = WitAnimeNetwork.fetchDocument("$mainUrl/", referer = null)
                    ?: return newHomePageResponse(request.name, emptyList(), false)
                // Section containing `[x-ref=grid]` holds the 24 latest episodes
                val grid = doc.selectFirst("[x-ref=grid]")
                val items = (grid?.select("a[href*='/watch/']") ?: doc.select("a[href*='/watch/']"))
                    .mapNotNull { it.toSearchCard() }
                    .distinctBy { it.url }
                return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
            } else {
                val (fragDoc, hasOlder) = WitAnimeNetwork.fetchNewEpisodesPage(page)
                    ?: return newHomePageResponse(request.name, emptyList(), false)
                val items = fragDoc.collectCards("a[href*='/watch/'], a[href*='/anime/'], a[href*='/movie/']")
                return newHomePageResponse(HomePageList(request.name, items), hasNext = hasOlder && items.isNotEmpty())
            }
        }

        // 2. Curated Home Highlights (`الأكثر مشاهدة`, `مختارات`, `أحدث الأفلام`) on page 1
        if (route.isEmpty()) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val doc = WitAnimeNetwork.fetchDocument("$mainUrl/", referer = null)
                ?: return newHomePageResponse(request.name, emptyList(), false)

            // Exclude `[x-ref=grid]` (already shown in "أحدث الحلقات المضافة") so shelves don't duplicate
            val grid = doc.selectFirst("[x-ref=grid]")
            val items = LinkedHashMap<String, SearchResponse>()
            for (a in doc.select("main a[href*='/anime/'], main a[href*='/movie/'], main a[href*='/watch/']")) {
                if (grid != null && a.parents().any { it === grid }) continue
                val card = a.toSearchCard() ?: continue
                val prev = items[card.url]
                if (prev == null || (prev.posterUrl.isNullOrBlank() && !card.posterUrl.isNullOrBlank())) {
                    items[card.url] = card
                }
            }
            return newHomePageResponse(HomePageList(request.name, items.values.toList()), hasNext = false)
        }

        // 3. `/browse` and `/movies` support `/page/<N>` pagination; `/seasonal` and `/schedule` are single-page
        val supportsPagination = route == "/browse" || route == "/movies"
        if (!supportsPagination && page > 1) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val pageUrl = if (page <= 1) "$mainUrl$route" else "$mainUrl$route/page/$page"
        val doc = WitAnimeNetwork.fetchDocument(pageUrl, referer = "$mainUrl/")
            ?: return newHomePageResponse(request.name, emptyList(), false)

        val cards = doc.select("main").firstOrNull()?.let { mainEl ->
            val seen = LinkedHashMap<String, SearchResponse>()
            for (a in mainEl.select("a[href*='/anime/'], a[href*='/movie/']")) {
                val card = a.toSearchCard() ?: continue
                val prev = seen[card.url]
                if (prev == null || (prev.posterUrl.isNullOrBlank() && !card.posterUrl.isNullOrBlank())) {
                    seen[card.url] = card
                }
            }
            seen.values.toList()
        } ?: doc.collectCards("a[href*='/anime/'], a[href*='/movie/']")

        val nextPageNum = page + 1
        val hasNext = supportsPagination && cards.isNotEmpty() &&
            doc.selectFirst("a[href*='$route/page/$nextPageNum'], a[rel=next]") != null

        return newHomePageResponse(HomePageList(request.name, cards), hasNext = hasNext)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val searchUrl = "$mainUrl/search?q=$encoded"
        val doc = WitAnimeNetwork.fetchDocument(searchUrl, referer = "$mainUrl/") ?: return emptyList()

        val mainScope = doc.selectFirst("main") ?: doc
        val results = LinkedHashMap<String, SearchResponse>()
        for (a in mainScope.select("a[href*='/anime/'], a[href*='/movie/'], a[href*='/watch/']")) {
            val card = a.toSearchCard() ?: continue
            val existing = results[card.url]
            if (existing == null || (existing.posterUrl.isNullOrBlank() && !card.posterUrl.isNullOrBlank())) {
                results[card.url] = card
            }
        }
        return results.values.toList()
    }

    // ── Load Anime Series / Movie Details & Full Episode List ────────────────

    private fun parsePrimaryLdJson(doc: Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val t = obj.optString("@type")
            if (t == "TVSeries" || t == "Movie" || t == "TVEpisode") {
                return obj
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        var targetUrl = WitAnimeNetwork.resolveUrl(url, mainUrl) ?: return null
        val path = runCatching { URI(targetUrl).path.orEmpty() }.getOrDefault(targetUrl)

        // Normalize `/watch/movie/<slug>` -> `/movie/<slug>` and `/watch/<slug>/<ep>` -> `/anime/<slug>`
        WATCH_MOVIE_REGEX.find(path)?.groupValues?.get(1)?.let { mSlug ->
            targetUrl = "$mainUrl/movie/$mSlug"
        }
        WATCH_EP_REGEX.find(path)?.groupValues?.get(1)?.let { aSlug ->
            targetUrl = "$mainUrl/anime/$aSlug"
        }

        var doc = WitAnimeNetwork.fetchDocument(targetUrl, referer = "$mainUrl/") ?: return null
        var ldJson = parsePrimaryLdJson(doc)

        // If a TVEpisode page was somehow loaded, jump to partOfSeries.url
        if (ldJson?.optString("@type") == "TVEpisode") {
            val seriesUrl = ldJson.optJSONObject("partOfSeries")?.optString("url")
                ?.let { WitAnimeNetwork.resolveUrl(it, mainUrl) }
            if (!seriesUrl.isNullOrBlank() && seriesUrl != targetUrl) {
                WitAnimeNetwork.fetchDocument(seriesUrl, referer = targetUrl)?.let { seriesDoc ->
                    targetUrl = seriesUrl
                    doc = seriesDoc
                    ldJson = parsePrimaryLdJson(seriesDoc)
                }
            }
        }

        val isMoviePage = targetUrl.contains("/movie/") || ldJson?.optString("@type") == "Movie"

        val title = doc.selectFirst("main h1, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: ldJson?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val altName = ldJson?.optString("alternateName")?.trim()?.takeIf { it.isNotEmpty() && it != title }
        val rawDesc = ldJson?.optString("description")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("main p.leading-relaxed, meta[name=description]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()
        val plot = buildString {
            if (!altName.isNullOrBlank()) append(altName).append("\n\n")
            if (!rawDesc.isNullOrBlank()) append(rawDesc)
        }.trim().ifEmpty { null }

        val posterUrl = doc.selectFirst("main img[src*='/posters/']")?.attr("src")
            ?.let { WitAnimeNetwork.resolveUrl(it, mainUrl) }
            ?: ldJson?.optString("image")?.takeIf { it.contains("/posters/") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val bannerUrl = ldJson?.optString("image")?.takeIf { it.contains("/banners/") }
            ?: doc.selectFirst("main img[src*='/banners/']")?.attr("src")
                ?.let { WitAnimeNetwork.resolveUrl(it, mainUrl) }

        // Genres / tags
        val genres = LinkedHashSet<String>()
        ldJson?.optJSONArray("genre")?.let { arr: JSONArray ->
            for (i in 0 until arr.length()) {
                arr.optString(i)?.trim()?.takeIf { it.isNotEmpty() }?.let(genres::add)
            }
        }
        doc.select("main span.rounded-full, main a[href*='genre=']").forEach { el ->
            val g = el.text().trim()
            if (g.length in 2..28 && !g.any { it.isDigit() }) genres.add(g)
        }

        val heroText = normalizeArabicDigits(doc.selectFirst("main")?.text().orEmpty())

        val year = ldJson?.optString("datePublished")?.take(4)?.toIntOrNull()
            ?: doc.select("main p.text-white, main span").mapNotNull { el ->
                val t = normalizeArabicDigits(el.text().trim())
                if (t.length == 4) t.toIntOrNull()?.takeIf { it in 1960..2035 } else null
            }.firstOrNull()
            ?: YEAR_REGEX.find(heroText)?.groupValues?.get(1)?.toIntOrNull()

        val scoreVal = doc.select("main span[dir=ltr]").mapNotNull { el ->
            el.text().trim().toDoubleOrNull()?.takeIf { it in 1.0..10.0 }
        }.firstOrNull()

        val durationMin = ldJson?.optString("duration")?.let { iso ->
            ISO_DURATION_REGEX.find(iso)?.let { m ->
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val min = m.groupValues[2].toIntOrNull() ?: 0
                (h * 60 + min).takeIf { it > 0 }
            }
        } ?: DURATION_MIN_REGEX.find(heroText)?.groupValues?.get(1)?.toIntOrNull()

        val showStatus = when {
            doc.select("main span").any { it.text().trim() == "مستمر" || it.text().contains("يعرض") } -> ShowStatus.Ongoing
            doc.select("main span").any { it.text().trim() == "مكتمل" || it.text().contains("منتهي") } -> ShowStatus.Completed
            else -> null
        }

        // Recommendations (`ذات صلة` & `قد يعجبك أيضًا`)
        val recommendations = LinkedHashMap<String, SearchResponse>()
        for (a in doc.select("main a[href*='/anime/'], main a[href*='/movie/']")) {
            val card = a.toSearchCard() ?: continue
            if (card.url == targetUrl) continue
            val prev = recommendations[card.url]
            if (prev == null || (prev.posterUrl.isNullOrBlank() && !card.posterUrl.isNullOrBlank())) {
                recommendations[card.url] = card
            }
        }

        // ── Movie Response ───────────────────────────────────────────────────
        if (isMoviePage) {
            val movieSlug = MOVIE_SLUG_REGEX.find(targetUrl)?.groupValues?.get(1).orEmpty()
            val watchMovieUrl = doc.selectFirst("a[href*='/watch/movie/']")?.attr("href")
                ?.let { WitAnimeNetwork.resolveUrl(it, mainUrl) }
                ?: "$mainUrl/watch/movie/$movieSlug"

            return newMovieLoadResponse(title, targetUrl, TvType.AnimeMovie, watchMovieUrl) {
                this.posterUrl = posterUrl ?: bannerUrl
                if (!bannerUrl.isNullOrBlank()) this.backgroundPosterUrl = bannerUrl
                this.plot = plot
                this.year = year
                this.duration = durationMin
                this.tags = genres.toList()
                if (scoreVal != null) this.score = Score.from10(scoreVal)
                if (recommendations.isNotEmpty()) {
                    this.recommendations = recommendations.values.toList()
                }
            }
        }

        // ── Anime Series / OVA Episode Extraction ────────────────────────────
        data class RawEpEntry(
            val watchUrl: String,
            val sortKey: Double,
            val intNumber: Int?,
            val displayName: String,
            val thumb: String?
        )

        val epMap = LinkedHashMap<String, RawEpEntry>()
        val epAnchors = doc.select("[data-episode-list] a[href*='/watch/']").ifEmpty {
            doc.select("main a[href*='/watch/']").filterNot { a ->
                a.text().trim() == "شاهد الآن"
            }
        }

        for (a in epAnchors) {
            val epUrl = WitAnimeNetwork.resolveUrl(a.attr("href"), mainUrl) ?: continue
            val epPath = runCatching { URI(epUrl).path.orEmpty() }.getOrDefault(epUrl)
            val m = WATCH_EP_REGEX.find(epPath) ?: continue
            val rawSeg = normalizeArabicDigits(urlDecodeSafe(m.groupValues[2])).trim()
            if (epMap.containsKey(epUrl)) continue

            val numericSort = rawSeg.toDoubleOrNull()
                ?: Regex("""(\d+(?:\.\d+)?)""").find(rawSeg)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: (epMap.size + 1).toDouble()

            val isFractional = rawSeg.contains('.')
            val intEp = if (!isFractional) rawSeg.toIntOrNull() else null

            val rawLabel = normalizeArabicDigits(a.text().replace(Regex("""\s+"""), " ").trim())
            val epName = when {
                rawLabel.isNotBlank() && rawLabel != "شاهد الآن" -> rawLabel
                else -> "الحلقة $rawSeg"
            }
            val thumb = a.selectFirst("img[src]")?.attr("src")?.let { WitAnimeNetwork.resolveUrl(it, mainUrl) }
                ?: posterUrl

            epMap[epUrl] = RawEpEntry(
                watchUrl = epUrl,
                sortKey = numericSort,
                intNumber = intEp,
                displayName = epName,
                thumb = thumb
            )
        }

        // Fallback if only a hero CTA `/watch/<slug>/1` button exists
        if (epMap.isEmpty()) {
            doc.selectFirst("main a[href*='/watch/']")?.attr("href")
                ?.let { WitAnimeNetwork.resolveUrl(it, mainUrl) }
                ?.let { ctaUrl ->
                    val seg = ctaUrl.trimEnd('/').substringAfterLast('/')
                    epMap[ctaUrl] = RawEpEntry(
                        watchUrl = ctaUrl,
                        sortKey = seg.toDoubleOrNull() ?: 1.0,
                        intNumber = seg.toIntOrNull() ?: 1,
                        displayName = "الحلقة $seg",
                        thumb = posterUrl
                    )
                }
        }

        val sortedEpisodes: List<Episode> = epMap.values
            .sortedBy { it.sortKey }
            .map { entry ->
                newEpisode(entry.watchUrl) {
                    this.name = entry.displayName
                    if (entry.intNumber != null) {
                        this.episode = entry.intNumber
                    }
                    this.posterUrl = entry.thumb
                }
            }

        val typeBadge = doc.selectFirst("main span.bg-white")?.text()?.trim().orEmpty()
        val tvType = when {
            typeBadge.equals("OVA", true) || typeBadge.equals("ONA", true) || typeBadge.equals("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }
        val isDubSeries = title.contains("مدبلج") || genres.any { it.contains("مدبلج") }

        return newAnimeLoadResponse(title, targetUrl, tvType) {
            this.posterUrl = posterUrl
            if (!bannerUrl.isNullOrBlank()) this.backgroundPosterUrl = bannerUrl
            this.plot = plot
            this.year = year
            this.duration = durationMin
            this.tags = genres.toList()
            this.showStatus = showStatus
            if (scoreVal != null) this.score = Score.from10(scoreVal)
            if (recommendations.isNotEmpty()) {
                this.recommendations = recommendations.values.toList()
            }
            addEpisodes(if (isDubSeries) DubStatus.Dubbed else DubStatus.Subbed, sortedEpisodes)
        }
    }

    // ── Load Video Links (Streaming Pipelined Gate Unlock + Parallel Extractors) ──

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startMs = System.currentTimeMillis()
        val watchUrl = WitAnimeNetwork.resolveUrl(data, mainUrl) ?: return false
        Log.i("WitAnime", "loadLinks start: $watchUrl")

        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val emittedCount = AtomicInteger(0)
        val cbLock = Any()
        val dedupCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                val idx = emittedCount.incrementAndGet()
                Log.i("WitAnime", "Link #$idx ready in ${System.currentTimeMillis() - startMs}ms: ${link.name} (${link.quality}p)")
                synchronized(cbLock) {
                    callback(link)
                }
            }
        }

        val emittedSubs = ConcurrentHashMap.newKeySet<String>()
        val subLock = Any()
        val dedupSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (emittedSubs.add(sub.url)) {
                synchronized(subLock) {
                    subtitleCallback(sub)
                }
            }
        }

        // Hard 34s cap ensures we NEVER approach CloudStream's 120,000ms TimeoutCancellationException
        withTimeoutOrNull(34_000L) {
            supervisorScope {
                val sem = Semaphore(6)
                val extractorJobs = CopyOnWriteArrayList<Deferred<Unit>>()

                WitAnimeNetwork.streamWatchPageSources(
                    rawWatchUrl = watchUrl,
                    allowOverflowProxies = false,
                    shouldContinue = { unlockedCount ->
                        when {
                            unlockedCount >= 9 -> false
                            unlockedCount >= 6 && emittedCount.get() >= 5 -> false
                            else -> true
                        }
                    },
                    onSourceUnlocked = { src ->
                        val job = async(Dispatchers.IO) {
                            sem.withPermit {
                                withTimeoutOrNull(11_000L) {
                                    WitAnimeExtractors.extractWitSource(
                                        src = src,
                                        subtitleCallback = dedupSubtitleCallback,
                                        callback = dedupCallback
                                    )
                                }
                                Unit
                            }
                        }
                        extractorJobs.add(job)
                    }
                )

                val waitBudgetMs = if (emittedCount.get() >= 4) 4_500L else 11_500L
                withTimeoutOrNull(waitBudgetMs) {
                    extractorJobs.awaitAll()
                }
                for (job in extractorJobs) {
                    if (job.isActive) job.cancel()
                }
            }
        }

        Log.i("WitAnime", "loadLinks completed in ${System.currentTimeMillis() - startMs}ms with ${emittedUrls.size} playable links")
        return emittedUrls.isNotEmpty()
    }
}

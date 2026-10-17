package com.dramaworld

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/**
 * Resolves the third-party embeds that the DramaWorld API lists for each episode/movie.
 *
 * The official app (`com.alam.aldrama3`) never parses the embed itself: it fetches the embed
 * page with a desktop browser UA and hands the raw HTML to the same server-side scraper at
 * `mawdhou3.com` (host -> `scrapefinal/<name>.php`) that Egywatch uses. The two apps share the
 * video ecosystem (okhd, vidspeed, morencius, vidaraa, upzur, luluvdo, vidoba, film77, ...).
 * A handful of embeds answer with a plain `sources: [{file: "…"}]` block (anafast, mp4plus) and
 * can be read directly, so those are short-circuited before hitting the scraper.
 */
object DramaWorldHostExtractor {
    private const val SCRAPER = "https://mawdhou3.com/"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
    private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

    private enum class Method { GET, POST }

    private data class Rule(val endpoint: String, val method: Method)

    /**
     * registrable-label -> scraper. Paths and methods mirror the captured app traffic
     * (the same endpoints Egywatch uses for this host family).
     */
    private val RULES: Map<String, Rule> = mapOf(
        // earnvids family (POST content+url)
        "earnvids" to Rule("scrapefinal/earnvids.php", Method.POST),
        "minochinos" to Rule("scrapefinal/earnvids.php", Method.POST),
        "callistanise" to Rule("scrapefinal/earnvids.php", Method.POST),
        "hanerix" to Rule("scrapefinal/earnvids.php", Method.POST),
        "morencius" to Rule("scrapefinal/earnvids.php", Method.POST),
        "streamwish" to Rule("scrapefinal/earnvids.php", Method.POST),
        "mp4plus" to Rule("scrapefinal/earnvids.php", Method.POST),
        "stmruby" to Rule("scrapefinal/earnvids.php", Method.POST),
        "streamruby" to Rule("scrapefinal/earnvids.php", Method.POST),
        "vidspeed" to Rule("scrapefinal/earnvids.php", Method.POST),
        "vidspeeds" to Rule("scrapefinal/earnvids.php", Method.POST),
        // turki family (GET ?api=)
        "vidoba" to Rule("scrapefinal/turki.php", Method.GET),
        "vidobahd" to Rule("scrapefinal/turki.php", Method.GET),
        "film77" to Rule("scrapefinal/turki.php", Method.GET),
        "okhd" to Rule("scrapefinal/turki.php", Method.GET),
        "laroza" to Rule("scrapefinal/turki.php", Method.GET),
        "turkiiiijdid" to Rule("scrapefinal/turki.php", Method.GET),
        // other POST scrapers
        "upzur" to Rule("scrapefinal/upzur.php", Method.POST),
        "vidaraa" to Rule("scrapefinal/vidaara.php", Method.POST),
        "vidara" to Rule("scrapefinal/vidaara.php", Method.POST),
        "luluvdo" to Rule("scrapefinal/extractm3u8.php", Method.POST),
        "lulustream" to Rule("scrapefinal/extractm3u8.php", Method.POST),
        // GET scrapers
        "uqload" to Rule("scrapefinal/uqload.php", Method.GET)
    )

    /** Query key the scraper expects when the embed is passed via the URL instead of `content`. */
    private fun queryKeyFor(endpoint: String): String =
        if (endpoint.contains("extract_qualite")) "url" else "api"

    /** Registrable label, e.g. `mp5.okhd.site` -> `okhd`, `rty1.film77.xyz` -> `film77`. */
    private fun label(host: String): String {
        val h = host.substringBefore(':').lowercase()
        val parts = h.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return h
        return if (parts.size >= 2) parts[parts.size - 2] else parts[0]
    }

    private fun matchRule(host: String): Rule? {
        val lbl = label(host)
        RULES[lbl]?.let { return it }
        RULES[host.lowercase()]?.let { return it }
        return null
    }

    private fun buildGetUrl(endpoint: String, link: String): String {
        val encoded = URLEncoder.encode(link, "UTF-8")
        return SCRAPER + endpoint + "?" + queryKeyFor(endpoint) + "=" + encoded
    }

    suspend fun extract(
        link: String,
        server: String,
        referer: String?,
        origin: String?,
        userAgent: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = hostOf(link) ?: return false

        // The embed page mints the CDN token bound to the exact User-Agent + Accept-Language of
        // the request that fetched it (e.g. luluvdo -> *.tnmr.org answers 403 the moment these
        // differ), so the SAME header set must be used to fetch the page and play the link.
        val cdnHeaders = buildMap {
            put("User-Agent", userAgent ?: BROWSER_UA)
            put("Accept-Language", ACCEPT_LANGUAGE)
            if (!origin.isNullOrBlank()) put("Origin", origin)
        }
        val embedOrigin = "https://$host/"
        val playReferer = when {
            referer.isNullOrBlank() -> embedOrigin
            label(hostOf(referer) ?: "") == label(host) -> referer
            else -> embedOrigin
        }

        val rule = matchRule(host)

        val embed = runCatching {
            app.get(link, headers = cdnHeaders, referer = referer, allowRedirects = true)
        }.getOrNull()?.let { if (it.code in 200..299) it.text else null }

        // Directly readable embeds (anafast / mp4plus ship `sources: [{file: "…"}]`). Fast path
        // for hosts without a scraper rule, and a fallback below for those whose scraper fails.
        suspend fun tryDirect(): Boolean {
            if (embed == null) return false
            val direct = parseLinks(embed)
            return direct.isNotEmpty() && emit(direct, server, playReferer, cdnHeaders, callback)
        }

        if (rule == null) return tryDirect()

        if (rule.method == Method.GET) {
            val body = runCatching { app.get(buildGetUrl(rule.endpoint, link), referer = SCRAPER).text }.getOrNull()
            if (emit(parseLinks(body), server, playReferer, cdnHeaders, callback)) return true
            return tryDirect()
        }

        // POST: mirror the app by posting the embed HTML, then fall back to the scraper's own
        // fetch (GET ?api=) which is more resilient when the embed markup is token-bound.
        if (embed != null) {
            val body = runCatching {
                app.post(
                    SCRAPER + rule.endpoint,
                    data = mapOf("content" to embed, "url" to link),
                    referer = SCRAPER
                ).text
            }.getOrNull()
            if (emit(parseLinks(body), server, playReferer, cdnHeaders, callback)) return true
        }
        val body = runCatching { app.get(buildGetUrl(rule.endpoint, link), referer = SCRAPER).text }.getOrNull()
        if (emit(parseLinks(body), server, playReferer, cdnHeaders, callback)) return true
        return tryDirect()
    }

    private suspend fun emit(
        links: List<Pair<String, Int?>>,
        server: String,
        referer: String?,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (links.isEmpty()) return false
        val seen = HashSet<String>()
        var found = false
        for ((url, height) in links) {
            val key = if (url.contains(".m3u8")) url.substringBefore('?') else url
            if (!seen.add(key)) continue
            // HLS: enumerate variants through M3u8Helper so the (token-bound) request headers
            // reach master, variants and segments. Fall back to the raw master with headers.
            if (url.contains(".m3u8") && !referer.isNullOrBlank()) {
                val fetchHeaders = headers + mapOf("Referer" to referer)
                val generated = runCatching {
                    M3u8Helper.generateM3u8(server, url, referer, headers = fetchHeaders)
                }.getOrNull()
                if (!generated.isNullOrEmpty()) {
                    generated.forEach { callback(it) }
                    found = true
                    continue
                }
            }
            callback(
                newExtractorLink(
                    server,
                    server,
                    url,
                    if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    if (!referer.isNullOrBlank()) this.referer = referer
                    if (headers.isNotEmpty()) this.headers = headers.toMutableMap()
                    if (height != null) this.quality = height
                }
            )
            found = true
        }
        return found
    }

    private fun parseLinks(body: String?): List<Pair<String, Int?>> {
        if (body.isNullOrBlank()) return emptyList()
        // JSON responses escape slashes (`https:\/\/…`); unescape once so both the JSON and the
        // raw-text scanners below see plain URLs.
        val trimmed = body.replace("\\/", "/").trim()
        val out = mutableListOf<Pair<String, Int?>>()

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            if (json != null) {
                parseJson(json, out)
                if (out.isNotEmpty()) return out.distinctBy { it.first }
                val file = json.optString("file", json.optString("url"))
                if (file.startsWith("http")) return listOf(file to null)
            }
        }

        // Raw jwplayer-ish: file:"…",label:"1080p" (turki / uqload …)
        Regex("""file\s*:\s*"(https?://[^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(trimmed).forEach { m ->
            val url = m.groupValues[1]
            val rest = trimmed.substring(m.range.last + 1)
            val lbl = Regex("""label\s*:\s*"([^"]*)"""").find(rest)?.groupValues?.get(1)
            out += url to heightOf(lbl)
        }
        // `sources: [{file: "…"}]` blocks (anafast / mp4plus)
        Regex("""(https?://[^\s"'\\,]+?\.(?:m3u8|mp4|webm|mkv)[^\s"'\\,]*)""").findAll(trimmed).forEach { m ->
            out += m.groupValues[1] to null
        }
        return out.distinctBy { it.first }
    }

    private fun parseJson(json: JSONObject, out: MutableList<Pair<String, Int?>>) {
        json.optJSONArray("availableQualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.startsWith("http")) out += url to heightOf(o.optString("quality"))
            }
        }
        json.optJSONArray("filtered_content")?.let { content ->
            val quality = json.optJSONArray("Quality") ?: json.optJSONArray("qualities")
            addContent(content, quality, out)
        }
        json.optJSONArray("sources")?.let { src -> addContent(src, null, out) }
        json.optJSONArray("links")?.let { src -> addContent(src, null, out) }
    }

    private fun addContent(content: JSONArray, quality: JSONArray?, out: MutableList<Pair<String, Int?>>) {
        for (i in 0 until content.length()) {
            val item = content.opt(i)
            val url = when {
                item is String -> item
                item is JSONObject -> item.optString("file").ifEmpty { item.optString("url") }
                else -> null
            }?.takeIf { it.startsWith("http") } ?: continue
            val label = when {
                quality?.opt(i) is String -> quality.optString(i)
                item is JSONObject -> item.optString("label").ifEmpty { item.optString("quality") }
                else -> null
            }
            out += url to heightOf(label)
        }
    }

    private fun heightOf(label: String?): Int? =
        label?.replace(Regex("[^0-9]"), "")?.takeIf { it.isNotBlank() }?.toIntOrNull()

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()
}

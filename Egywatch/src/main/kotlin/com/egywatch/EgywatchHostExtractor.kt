package com.egywatch

import android.util.Log
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
 * Resolves the third-party embeds that the Egywatch API lists inside each episode/movie.
 *
 * The original Android app never parses the embed itself: it fetches the embed page with a
 * desktop browser UA and then hands the raw HTML to its own server-side scraper at
 * `mawdhou3.com` (host -> `scrapefinal/<name>.php`). A handful of embeds answer with a plain
 * `sources: [{file: "…"}]` block (e.g. anafast) and can be read directly, so those are short
 * circuited before hitting the scraper.
 */
object EgywatchHostExtractor {
    private const val TAG = "EgywatchHost"
    private const val SCRAPER = "https://mawdhou3.com/"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

    private val DEAD_HOSTS = listOf("ostvapp.cam", "admin.dramaramadan.net")

    private enum class Method { GET, POST }

    private data class Rule(val endpoint: String, val method: Method)

    /**
     * host registrable-label -> scraper. Paths and methods are taken verbatim from the captured
     * app traffic. `earnvids.php` stores its embed HTML in `content`/`url`; the same is true for
     * every POST rule here.
     */
    private val RULES: Map<String, Rule> = mapOf(
        // earnvids family (POST content+url)
        "earnvids" to Rule("scrapefinal/earnvids.php", Method.POST),
        "earnvids.xyz" to Rule("scrapefinal/earnvids.php", Method.POST),
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
        // other POST scrapers
        "upzur" to Rule("scrapefinal/upzur.php", Method.POST),
        "vidaraa" to Rule("scrapefinal/vidaara.php", Method.POST),
        "vidara" to Rule("scrapefinal/vidaara.php", Method.POST),
        "luluvdo" to Rule("scrapefinal/extractm3u8.php", Method.POST),
        "lulustream" to Rule("scrapefinal/extractm3u8.php", Method.POST),
        "vidtube" to Rule("scrapefinal/vidtubepost.php", Method.POST),
        "vidtubejdid" to Rule("scrapefinal/vidtubepost.php", Method.POST),
        "seriesmp4" to Rule("scrapefinal/dramaramadanPost.php", Method.POST),
        "seriemp4" to Rule("scrapefinal/dramaramadanPost.php", Method.POST),
        "forafile" to Rule("recuperepost.php", Method.POST),
        "okprime" to Rule("scrapefinal/okprimePost.php", Method.POST),
        "faselpost" to Rule("scrapefinal/faselpost.php", Method.POST),
        "firestream" to Rule("scrapefinal/fastream.php", Method.POST),
        "fastream" to Rule("scrapefinal/fastream.php", Method.POST),
        "vidobahd" to Rule("scrapefinal/turki.php", Method.POST),
        "vidoba" to Rule("scrapefinal/turki.php", Method.POST),
        "film77" to Rule("scrapefinal/turki.php", Method.POST),
        "okhd" to Rule("scrapefinal/turki.php", Method.POST),
        "laroza" to Rule("scrapefinal/turki.php", Method.POST),
        "turkiiiijdid" to Rule("scrapefinal/turki.php", Method.POST),
        // GET scrapers
        "uqload" to Rule("scrapefinal/uqload.php", Method.GET),
        "updown" to Rule("scrapefinal/updown.php", Method.GET),
        "fasel-hd" to Rule("scripttestfasel.php", Method.GET),
        "faselapikey" to Rule("scripttestfasel.php", Method.GET),
        "google" to Rule("aminegoogle/extract_qualite.php", Method.GET),
        // generic config hosts (best effort)
        "71stream" to Rule("scrapefinal/71stream.php", Method.GET),
        "ukrcdn" to Rule("scrapefinal/ukrcdn.php", Method.GET),
        "videasjdid" to Rule("scrapefinal/vedasjdid.php", Method.GET),
        "videas" to Rule("scrapefinal/vedas.php", Method.GET),
        "fdewsdcpro" to Rule("test.php", Method.GET),
        "videoland" to Rule("test.php", Method.GET),
        "egybestvid" to Rule("test.php", Method.GET),
        "vidhidepre" to Rule("test.php", Method.GET),
        "vudeo" to Rule("test.php", Method.GET),
        "liiivideo" to Rule("scrapefinal/scriptalvid.php", Method.GET),
        "aparat" to Rule("scriptapart.php", Method.GET),
        "dnscheckr" to Rule("fasel/geturl.php", Method.GET),
        "cartoony" to Rule("decrypt_cartoony.php", Method.GET),
        "hdup20" to Rule("scrapefinal/hdup20.php", Method.GET),
        "vidnest" to Rule("scrapefinal/vidnest.php", Method.GET),
        "turbovidhls" to Rule("scrapefinal/turbovidhls.php", Method.GET),
        "channel" to Rule("scrapefinal/channel.php", Method.GET)
    )

    /** Query key the scraper expects when the embed is passed via the URL instead of `content`. */
    private fun queryKeyFor(endpoint: String): String = when {
        endpoint.contains("extract_qualite") -> "url"
        endpoint.contains("channel") -> "url"
        endpoint.contains("scriptapart") -> "url"
        endpoint.contains("geturl") -> "api"
        endpoint.contains("decrypt_cartoony") -> "apiurl"
        else -> "api"
    }

    /** Registrable label, e.g. `mp5.okhd.site` -> `okhd`, `down.vidtube.one` -> `vidtube`. */
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
        // Fall back to an exact domain-suffix match so sub-labels are covered without the
        // substring false-positives (`vidsp` vs `vidspeed`) the old code suffered from.
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
        val host = hostOf(link)
        if (DEAD_HOSTS.any { host.contains(it) }) return false

        // Workers used by the current API: they are plain JSON/redirect endpoints.
        if (host.contains("test-stream.developer-pro.workers.dev")) {
            if (resolveJsonWorker(link, server, userAgent, callback)) return true
        }
        if (host.contains("link.developer-pro.workers.dev")) {
            if (resolveRedirectWorker(link, server, userAgent, callback)) return true
        }

        // The embed page mints the CDN token bound to the exact User-Agent +
        // Accept-Language of the request that fetched it (e.g. luluvdo -> *.tnmr.org
        // answers 403 the moment these differ), so the SAME header set must be used
        // to fetch the page and to play the emitted link.
        val cdnHeaders = buildMap {
            put("User-Agent", userAgent ?: BROWSER_UA)
            put("Accept-Language", ACCEPT_LANGUAGE)
            if (!origin.isNullOrBlank()) put("Origin", origin)
        }
        val embedOrigin = "https://$host/"
        // Playback referer: the embed origin, unless the API gave a referer on the
        // same registrable domain (some hosts hand out the embed domain while the
        // API header points at a different player page, e.g. updown.icu vs
        // topcinema.media — that mismatch is what triggered the 403).
        val playReferer = when {
            referer.isNullOrBlank() -> embedOrigin
            label(hostOf(referer)) == label(host) -> referer
            else -> embedOrigin
        }

        val rule = matchRule(host)

        val embed = runCatching {
            app.get(link, headers = cdnHeaders, referer = referer, allowRedirects = true)
        }.getOrNull()?.let { if (it.code in 200..299) it.text else null }

        // Directly readable embeds (e.g. anafast: `sources: [{file: "…m3u8"}]`). This is a fast
        // path only for hosts without a scraper rule; otherwise it is a fallback below.
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

    /** `…/video?url=` returns `{ "availableQualities": [{quality, url}] }`. */
    private suspend fun resolveJsonWorker(
        link: String,
        server: String,
        userAgent: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = runCatching {
            app.get(link, headers = mapOf("User-Agent" to (userAgent ?: BROWSER_UA))).text
        }.getOrNull() ?: return false
        return emit(parseLinks(body), server, null, emptyMap(), callback)
    }

    /** `…/get-links?id=` answers 302 with the final media URL in `Location`. */
    private suspend fun resolveRedirectWorker(
        link: String,
        server: String,
        userAgent: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(link, headers = mapOf("User-Agent" to (userAgent ?: BROWSER_UA)), allowRedirects = false)
        }.getOrNull() ?: return false
        val location = response.headers["Location"] ?: response.headers["location"] ?: return false
        if (!location.startsWith("http")) return false
        emit(listOf(location to null), server, null, emptyMap(), callback)
        return true
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
            // HLS: enumerate variants through M3u8Helper so the (token-bound) request
            // headers reach master, variants and segments. When the helper cannot parse
            // the playlist (e.g. fMP4-only) we still emit the raw master with headers.
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
        val trimmed = body.trim()
        val out = mutableListOf<Pair<String, Int?>>()

        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            parseJson(json, out)
            if (out.isNotEmpty()) return out.distinctBy { it.first }
            val file = json.optString("file", json.optString("url"))
            if (file.startsWith("http")) return listOf(file to null)
            return emptyList()
        }

        // Raw jwplayer-ish: file:"…",label:"1080p" (turki / uqload / updown …)
        Regex("""file\s*:\s*"(https?://[^"]+)"""", RegexOption.DOT_MATCHES_ALL).findAll(trimmed).forEach { m ->
            val url = m.groupValues[1]
            val rest = trimmed.substring(m.range.last + 1)
            val lbl = Regex("""label\s*:\s*"([^"]*)"""").find(rest)?.groupValues?.get(1)
            out += url to heightOf(lbl)
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

    private fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase() }.getOrNull() ?: url.lowercase()
}

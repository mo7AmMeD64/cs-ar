package com.animeday

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * ============================================================================
 *  AnimeDay — professional extractor suite (stable, self-contained).
 * ============================================================================
 *  Handles every source the AnimeDay backend can return:
 *
 *    1. Direct streams ....... .m3u8 (expanded), .mp4 / .mkv, .mpd
 *    2. Google Photos ........ lh3.googleusercontent.com/pw/... (+ wrappers)
 *    3. AnimeDay PHP chain ... extractor.php / html_extractor.php / advanced_extractor.php
 *    4. P.A.C.K.E.R. embeds ... vidspeed.org + uqload (JWPlayer pages, JsUnpacker)
 *    5. FaselHD .............. fasel-hd.com watch pages (enc: decrypt + loadExtractor)
 *    6. Fallback ............. CloudStream's global loadExtractor
 *
 *  Google Photos note: the backend exposes every quality through a single DASH
 *  manifest (`=mm,dash-vm-vf,...`). Its googlevideo segments are IP-pinned and
 *  answer HTTP 403 unless BOTH `Referer` and `Origin: https://photos.google.com`
 *  are sent, and they are pinned to the `User-Agent` that minted them — exactly
 *  the headers [PHOTO_HEADERS] carries. The probed progressive `=m37` / `=m22` /
 *  `=m18` MP4 renditions are the preferred sources (they seek reliably); the
 *  adaptive DASH manifest is emitted only as a last resort when no MP4 rendition
 *  exists, since its IP-pinned, short-lived segments make seeking unreliable.
 *
 *  This file is intentionally the single place where extraction lives; the main
 *  API only fetches/decrypts the server list and hands each link to [emit].
 */
object AnimeDayExtractors {

    const val UA = "okhttp/4.10.0"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
    private const val PHP_BASE = "https://cloud-day.online/cimacloud"
    private const val HEAD_BYTES = 128 * 1024

    private const val PHOTO_REFERER = "https://photos.google.com/"
    private const val PHOTO_ORIGIN = "https://photos.google.com"

    /** DASH option that lists every real rendition (audio + video), exactly like the apps' player. */
    private const val DASH_OPTION =
        "=mm,dash-vm-vf,dr.sdr,sdrCodec.vp9.h264?alr=true&mpd_version=5&pacing=0"

    /**
     * Google Photos googlevideo URLs are pinned to the `User-Agent` that minted
     * them, so the player must send the exact same UA the manifest/probe used.
     * Without this the player falls back to CloudStream's own UA and every
     * segment answers HTTP 403.
     */
    private val PHOTO_HEADERS = mapOf(
        "Origin" to PHOTO_ORIGIN,
        "User-Agent" to BROWSER_UA
    )

    private val PHP_ENDPOINTS = listOf(
        "$PHP_BASE/extractor.php",
        "$PHP_BASE/html_extractor.php",
        "$PHP_BASE/advanced_extractor.php"
    )
    private val SEEKABLE = listOf("=m37", "=m22", "=m18")

    private val PW_RE = Regex("""https://lh3\.googleusercontent\.com/pw/[^=?"'\s\\]+""")
    private val HTML_RE = Regex("""data-url="(https://lh3\.googleusercontent\.com/pw/[^\s"?]+)""")
    private val WRAP_RE = Regex("[?&]url=([^&]+)")

    fun plainHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*"
    )

    fun pageHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "ar,en;q=0.9",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    fun isGooglePhotosLink(link: String): Boolean =
        link.contains("photos.google.com") || link.contains("photos.app.goo.gl") ||
                link.contains("googleusercontent.com") || link.contains("googlefinal") ||
                link.contains("hrrejhp")

    // ------------------------------------------------- P.A.C.K.E.R. embeds (vidspeed / uqload)

    private val PACKER_EVAL_RE = Regex(
        """(?s)\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)"""
    )
    private val PACKER_WORD_RE = Regex("""\b[a-zA-Z0-9_]+\b""")
    private val PACKER_STREAM_RE =
        Regex("""[:=]\s*"([^"\s]+(?:\.m3u8|master\.txt)[^"\s]*)""")

    /**
     * vidspeed.org and uqload (uqload.is → uqload.vc) serve their real HLS master
     * inside a P.A.C.K.E.R.-obfuscated JWPlayer script:
     * `eval(function(p,a,c,k,e,d){...}('payload',36,N,'symtab'|...))`. The CDN
     * answers 403 unless the fetch is referer-pinned to the freshly fetched embed
     * page, so it must be re-fetched and its token re-decoded at play time.
     *
     * This is the faithful port of CloudStream's own JsUnpacker.unpack(): it walks
     * the payload's base-N words in order and substitutes each index that exists
     * and is non-empty in the symbol table (empty entries — which otherwise would
     * eat the URL's `t`/`s`/`e`/`v`/`i` keys — are skipped, exactly like the
     * packer's own `if(k[c])` guard).
     */
    private fun unPacker(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val match = PACKER_EVAL_RE.find(html) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val count = match.groupValues[3].toIntOrNull() ?: return null
        val symtab = match.groupValues[4].split("|")
        if (symtab.size != count) return null
        val decoded = StringBuilder(payload)
        var offset = 0
        PACKER_WORD_RE.findAll(payload).forEach { word ->
            val x = runCatching { word.value.toInt(radix) }.getOrNull() ?: return@forEach
            val value = symtab.getOrNull(x).takeIf { !it.isNullOrEmpty() } ?: return@forEach
            decoded.setRange(word.range.first + offset, word.range.last + 1 + offset, value)
            offset += value.length - word.value.length
        }
        return decoded.toString()
    }

    /** Pull the first HLS URL out of a decoded JWPlayer setup script. */
    private fun packerStreamUrl(script: String?): String? {
        if (script.isNullOrBlank()) return null
        return PACKER_STREAM_RE.find(script)?.groupValues?.get(1)
    }

    private fun hostContains(link: String, needle: String): Boolean =
        runCatching { Uri.parse(link.trim()).host?.contains(needle, ignoreCase = true) == true }
            .getOrDefault(false)

    /** True for any P.A.C.K.E.R. JWPlayer embed host handled by [emit]. */
    fun isPackerEmbed(link: String): Boolean =
        hostContains(link, "vidspeed") || hostContains(link, "uqload")

    /** Fetch the embed page, decode its P.A.C.K.E.R. and emit the real HLS master. */
    private suspend fun emitPackerEmbed(
        embedUrl: String,
        label: String,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try {
            app.get(embedUrl, headers = pageHeaders()).text
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "packer embed FAILED ${embedUrl.take(100)}: $e")
            return false
        }
        val script = unPacker(html)
        val m3u8 = packerStreamUrl(script)
            ?: return false
        var found = false
        for (v in M3u8Helper.generateM3u8(label, m3u8, embedUrl)) {
            found = true
            emit(v)
        }
        return found
    }

    // ------------------------------------------------------------------ FaselHD watch pages

    /** True for any FaselHD watch page handled by [emit]. */
    fun isFaselHDLink(link: String): Boolean = hostContains(link, "fasel-hd")

    private val FHD_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"
    private const val FHD_KEY1 = "V2@%YSU2B]G~"
    private const val FHD_KEY2 = "bv0fim4qf17"

    private fun fhdIndex(c: Char): Int = FHD_ALPHABET.indexOf(c).let { if (it < 0) 0 else it }

    private fun fhdChar(value: Int): Char = FHD_ALPHABET[value]

    /** Faithful port of the FaselHD plugin's two-key decrypt over the site alphabet. */
    private fun fhdDecryptString(enc: String, key: String): String {
        val sb = StringBuilder(enc.length)
        for (i in enc.indices) {
            val keyChar = key[i % (key.length - 1)]
            var diff = fhdIndex(enc[i]) - fhdIndex(keyChar)
            if (diff < 0) diff += 64
            sb.append(fhdChar(diff))
        }
        return sb.toString()
    }

    private fun fhdDecryptEncryptedUrl(enc: String): String {
        val value = enc.removePrefix("enc:")
        return fhdDecryptString(fhdDecryptString(value, FHD_KEY2), FHD_KEY1)
    }

    /** Player URLs embedded in a FaselHD watch page (onclick + internal player iframes). */
    private fun faselhdWatchUrls(html: String): List<String> {
        val out = linkedSetOf<String>()
        val blocked = listOf("recaptcha", "google.com/ads", "googlesyndication.com", "googletagmanager.com")
        fun add(u: String) {
            val clean = u.replace("&amp;", "&").trim()
            if (clean.isNotBlank() && blocked.none { clean.contains(it) }) out.add(clean)
        }
        Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .forEach { add(it.groupValues[1]) }
        Regex("""(?i)<iframe\b[^>]*\b(?:src|data-src)\s*=\s*["']([^"']+)["'][^>]*>""")
            .findAll(html)
            .forEach {
                val src = it.groupValues[1]
                if (src.contains("video_player") || src.contains("embed") || src.contains("player")) add(src)
            }
        return out.toList()
    }

    /** Resolve a FaselHD internal player page to its real m3u8 (enc: decrypt, then plain link). */
    private suspend fun fhdPlayerSource(playerUrl: String, referer: String): String? {
        return try {
            val html = app.get(playerUrl, headers = pageHeaders()).text
            for (m in Regex("""enc:[A-Za-z0-9+/=_%]+""").findAll(html)) {
                val decrypted = fhdDecryptEncryptedUrl(m.value)
                if (decrypted.startsWith("http")) return decrypted
            }
            for (m in Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""").findAll(html)) {
                return m.value.replace("&amp;", "&")
            }
            null
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "faselhd player FAILED ${playerUrl.take(100)}: $e")
            null
        }
    }

    /** Emit every stream of a FaselHD watch page (internal players + external embed hosts). */
    private suspend fun emitFaselHD(
        pageUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = if (!pageUrl.contains("?") && !pageUrl.endsWith("/") && !pageUrl.substringAfterLast("/").contains("."))
            "$pageUrl/"
        else pageUrl
        val html = try {
            app.get(clean, headers = pageHeaders()).text
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "faselhd page FAILED ${pageUrl.take(100)}: $e")
            return false
        }
        val playerUrls = faselhdWatchUrls(html)
        if (playerUrls.isEmpty()) return false

        var found = false
        for (raw in playerUrls) {
            val playerUrl = raw.replace("&amp;", "&")
            if (isFaselHDLink(playerUrl)) {
                val direct = fhdPlayerSource(playerUrl, pageUrl)
                if (direct != null) {
                    for (v in M3u8Helper.generateM3u8("$label (FaselHD)", direct, playerUrl)) {
                        found = true
                        emit(v)
                    }
                }
            } else {
                runCatching {
                    if (loadExtractor(playerUrl, pageUrl, subtitleCallback, emit)) found = true
                }
            }
        }
        return found
    }

    // ------------------------------------------------------------------ entry point

    /**
     * Single entry point: classify [link] and emit every playable stream it yields.
     *
     * NOTE: [emit] is the caller's callback and may itself deduplicate by URL, so
     * this suite must never pre-register a URL it is about to emit. Internal
     * de-duplication uses a private set ([done]) created per extraction.
     */
    suspend fun emit(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                link.contains(".m3u8") -> {
                    var found = false
                    for (v in M3u8Helper.generateM3u8(label, link, referer)) {
                        found = true
                        emit(v)
                    }
                    found
                }

                link.contains(".mp4") || link.contains(".mkv") || link.contains(".mpd") ->
                    emitDirect(link, referer, label, quality, emit)

                isPackerEmbed(link) ->
                    emitPackerEmbed(link, label, emit)

                isGooglePhotosLink(link) ->
                    emitGooglePhotosLink(link, referer, label, quality, subtitleCallback, emit)

                else ->
                    emitUnknown(link, referer, label, quality, subtitleCallback, emit)
            }
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "emit FAILED for ${link.take(120)}: $e")
            false
        }
    }

    // ------------------------------------------------------------------ direct

    private suspend fun emitDirect(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        emit(
            newExtractorLink(
                label,
                label,
                link,
                if (link.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                if (quality != null) this.quality = quality
            }
        )
        return true
    }

    // ------------------------------------------------------------------ generic fallback

    private suspend fun emitUnknown(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val done = mutableSetOf<String>()
        var found = false
        try {
            if (loadExtractor(link, referer, subtitleCallback, emit)) found = true
        } catch (_: Throwable) {
        }
        if (emitPhpChain(link, referer, label, quality, done, emit)) found = true
        if (!found && isFaselHDLink(link)) {
            if (emitFaselHD(link, label, subtitleCallback, emit)) found = true
        }
        return found
    }

    // ------------------------------------------------------------------ Google Photos

    private suspend fun emitGooglePhotosLink(
        link: String,
        referer: String,
        label: String,
        quality: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        val base = resolveGphotoBase(link)
        android.util.Log.i("AnimeDayExtractors", "gphotos base=${base != null} link=${link.take(140)}")
        if (base != null) return emitGooglePhotos(base, label, mutableSetOf(), emit)
        return emitUnknown(link, referer, label, quality, subtitleCallback, emit)
    }

    /** Bare `.../pw/<token>` base, stripped of any `=modifier` and query string. */
    internal fun gphotoBase(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return PW_RE.find(url.trim())?.value
    }

    private fun gphotoBaseFromHtml(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val raw = HTML_RE.find(html)?.groupValues?.get(1) ?: return null
        return gphotoBase(raw)
    }

    /** Resolve any wrapper (googlefinal.php, photos.google.com share page, direct lh3) to the pw base. */
    private suspend fun resolveGphotoBase(url: String): String? {
        val trimmed = url.trim()
        gphotoBase(trimmed)?.let { return it }

        WRAP_RE.find(trimmed)?.groupValues?.get(1)
            ?.let { runCatching { Uri.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotBlank() && it != trimmed }
            ?.let { return resolveGphotoBase(it) }

        if (trimmed.contains("photos.google.com") || trimmed.contains("photos.app.goo.gl")) {
            val html = try {
                app.get(trimmed, headers = pageHeaders()).text
            } catch (_: Throwable) {
                null
            }
            return gphotoBaseFromHtml(html)
        }
        return null
    }

    /**
     * Emit the stable Google Photos sources:
     *
     *   The progressive muxed MP4 renditions (`=m37`/`=m22`/`=m18`). Those
     *   suffixes are NOT fixed resolutions, so each one is probed and its real
     *   size is read straight from the MP4 `tkhd` box; renditions that do not
     *   exist (e.g. a 404 on `=m37`) are skipped. Only when *no* MP4 rendition
     *   exists is the adaptive DASH manifest emitted as a last resort, so an
     *   episode that offers nothing else still plays (its seek may be imperfect).
     *
     * Google Photos googlevideo URLs answer HTTP 403 unless BOTH the `Referer`
     * and `Origin: https://photos.google.com` are sent, and they are pinned to
     * the `User-Agent` that requested the manifest — hence [PHOTO_HEADERS] carries
     * the browser UA. CloudStream forwards an [ExtractorLink]'s `referer` +
     * `headers` to every segment request, so every emitted link plays.
     */
    internal suspend fun emitGooglePhotos(
        base: String,
        label: String,
        done: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        // Direct progressive MP4 qualities (muxed video+audio).
        for (suffix in SEEKABLE) {
            val url = base + suffix
            if (!done.add(url)) continue
            val size = probe(url) ?: continue
            found = true
            callback(
                newExtractorLink(label, "MP4 ${size.second}p", url, ExtractorLinkType.VIDEO) {
                    this.referer = PHOTO_REFERER
                    this.headers = PHOTO_HEADERS
                    this.quality = size.second
                }
            )
        }

        // Last-resort fallback: when Google Photos exposes no progressive MP4
        // rendition, fall back to the adaptive DASH manifest so the episode still
        // plays. Seeking past the buffered range may fail (the segments are
        // IP-pinned and short-lived), which is why MP4 is preferred.
        if (!found) {
            val adaptiveUrl = base + DASH_OPTION + "&_=" + System.currentTimeMillis().toString(36)
            if (done.add("ga:$adaptiveUrl")) {
                found = true
                callback(
                    newExtractorLink(label, "DASH تلقائي", adaptiveUrl, ExtractorLinkType.DASH) {
                        this.referer = PHOTO_REFERER
                        this.headers = PHOTO_HEADERS
                    }
                )
            }
        }
        return found
    }

    /** (width, height) when the rendition exists and its MP4 header is readable, otherwise null. */
    private suspend fun probe(url: String): Pair<Int, Int>? {
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Accept" to "*/*",
                    "Range" to "bytes=0-$HEAD_BYTES",
                    "Referer" to "https://photos.google.com/"
                ),
                allowRedirects = true
            )
            if (res.code != 200 && res.code != 206) return null
            val bytes = res.body?.byteStream()?.use { readCapped(it, HEAD_BYTES) }
            fromTkhd(bytes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** Read width/height out of the first non-empty `tkhd` (track header) box. */
    private fun fromTkhd(data: ByteArray?): Pair<Int, Int>? {
        if (data == null || data.size < 108) return null
        val needle = byteArrayOf('t'.code.toByte(), 'k'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
        var i = indexOf(data, needle)
        while (i != -1) {
            val ver = data[i + 4].toInt() and 0xFF
            val wOff = if (ver == 1) i + 92 else i + 80
            val hOff = wOff + 4
            if (hOff + 4 <= data.size) {
                val w = readUInt32(data, wOff) ushr 16
                val h = readUInt32(data, hOff) ushr 16
                if (w in 16..7680 && h in 16..4320) return w to h
            }
            i = indexOf(data, needle, i + 1)
        }
        return null
    }

    private fun readUInt32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
                ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or
                (b[o + 3].toInt() and 0xFF)

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..(data.size - needle.size)) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ------------------------------------------------------------------ PHP extractor chain

    /** Mirror the app's extractor chain for ANY source page; all three endpoints run and merge, deduped by URL. */
    private suspend fun emitPhpChain(
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        done: MutableSet<String>,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        for (endpoint in PHP_ENDPOINTS) {
            if (emitPhpServers(endpoint, pageUrl, referer, label, quality, done, emit)) found = true
        }
        return found
    }

    private suspend fun emitPhpServers(
        endpoint: String,
        pageUrl: String,
        referer: String,
        label: String,
        quality: Int?,
        done: MutableSet<String>,
        emit: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = plainHeaders() + mapOf("Referer" to referer, "Accept-Language" to "ar,en;q=0.9")
            val json = safeJson(app.get(endpoint + "?url=" + Uri.encode(pageUrl), headers = headers).text) ?: return false
            val servers = json.optJSONArray("servers") ?: return false
            var emitted = false
            val gpBases = linkedSetOf<String>()
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val u = s.optString("url").trim()
                val ref = s.optString("referer").ifEmpty { s.optString("origin") }.ifEmpty { referer }
                val labelName = s.optString("name").ifEmpty { label }
                val q = s.optString("height").toIntOrNull() ?: quality

                val gpBase = gphotoBase(u)
                if (gpBase != null) {
                    gpBases.add(gpBase)
                    continue
                }

                if (u.isNotEmpty() && done.add(u)) {
                    emitted = true
                    when {
                        u.contains(".m3u8") -> {
                            for (v in M3u8Helper.generateM3u8(labelName, u, ref)) {
                                emit(v)
                            }
                        }

                        u.contains(".mpd") -> emit(
                            newExtractorLink(labelName, labelName, u, ExtractorLinkType.DASH) {
                                this.referer = ref
                                if (q != null) this.quality = q
                            }
                        )

                        else -> if (isPackerEmbed(u)) {
                            if (!emitPackerEmbed(u, labelName, emit)) {
                                emit(
                                    newExtractorLink(labelName, labelName, u, ExtractorLinkType.VIDEO) {
                                        this.referer = ref
                                        if (q != null) this.quality = q
                                    }
                                )
                            }
                        } else if (isFaselHDLink(u)) {
                            if (!emitFaselHD(u, labelName, {}, emit)) {
                                emit(
                                    newExtractorLink(labelName, labelName, u, ExtractorLinkType.VIDEO) {
                                        this.referer = ref
                                        if (q != null) this.quality = q
                                    }
                                )
                            }
                        } else emit(
                            newExtractorLink(labelName, labelName, u, ExtractorLinkType.VIDEO) {
                                this.referer = ref
                                if (q != null) this.quality = q
                            }
                        )
                    }
                }

                val dl = s.optString("url_download").trim()
                if (dl.isNotEmpty() && done.add(dl)) {
                    emitted = true
                    emit(
                        newExtractorLink("$labelName (تحميل)", "$labelName (download)", dl, ExtractorLinkType.VIDEO) {
                            this.referer = ref
                        }
                    )
                }
            }
            for (base in gpBases) {
                if (emitGooglePhotos(base, label, done, emit)) emitted = true
            }
            emitted
        } catch (e: Throwable) {
            android.util.Log.i("AnimeDayExtractors", "emitPhpServers FAILED $endpoint : $e")
            false
        }
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

/**
 * Registered CloudStream extractor for raw Google Photos URLs
 * (lh3.googleusercontent.com/pw/...). Share pages are handled by the main API
 * before this is ever reached, but registering it makes direct links work too.
 */
class GooglePhotosExtractor : ExtractorApi() {
    override val name = "GooglePhotos"
    override val mainUrl = "https://lh3.googleusercontent.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val base = AnimeDayExtractors.gphotoBase(url) ?: return
        AnimeDayExtractors.emitGooglePhotos(base, name, mutableSetOf(), callback)
    }
}


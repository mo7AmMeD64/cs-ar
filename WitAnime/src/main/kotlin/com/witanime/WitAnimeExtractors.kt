package com.witanime

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Comprehensive, self-contained video & direct-download extractor suite for WitAnime v22.
 *
 * Covers 100% of active and legacy WitAnime providers:
 *  - Videas (`app.videas.com` 2-step visitor + playback token API, demuxed multi-audio HLS aware)
 *  - Videa (`videa.hu` `_xt` unscrambler + RC4 XML decryption, multi-quality MP4)
 *  - MEGA (`mega.nz` HTTPS API + local `127.0.0.1` AES-128-CTR HTTP Range MP4 proxy)
 *  - StreamHG / HGCloud (`hgcloud.to` -> `vibuxer.com` / `hanerix.com` packed HLS)
 *  - YonaPlay (`mid.yonaplay.net` session API + AES-256-GCM decryptor) -> DotPlay (`dotplay.net`)
 *  - Mp4Upload (`mp4upload.com` embed & download direct MP4)
 *  - OkRu (`ok.ru` metadata multi-quality MP4 + HLS)
 *  - FourShared (`4shared.com` normalized embed direct MP4)
 *  - GoFile (`gofile.io` guest token + wt content API, `.zip` archive filter)
 *  - WorkUpload (`workupload.com` SHA-256 PoW solver + session download API, `.zip` archive filter)
 *  - MediaFire (`mediafire.com` `data-scrambled-url` + direct anchor, `.zip` archive filter)
 *  - MailRu (`my.mail.ru` metadata JSON + `video_key` cookie) + universal CloudStream fallback.
 */
object WitAnimeExtractors {

    private val STREAMHG_MIRRORS = listOf(
        "https://vibuxer.com",
        "https://hanerix.com",
        "https://dintezuvio.com",
        "https://cavanhabg.com"
    )

    private const val VIDEA_SECRET =
        "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    fun qualityFromHint(hint: String?): Int {
        val h = hint?.uppercase()?.trim().orEmpty()
        return when {
            h.contains("2160") || h.contains("4K") || h.contains("UHD") || h == "ULTRA" -> Qualities.P2160.value
            h.contains("1440") || h.contains("2K") || h.contains("QHD") || h == "QUAD" -> Qualities.P1440.value
            h.contains("1080") || h == "FHD" || h == "FULL" -> Qualities.P1080.value
            h.contains("720") || h == "HD" -> Qualities.P720.value
            h.contains("480") || h == "SD" -> Qualities.P480.value
            h.contains("360") || h == "LOW" -> Qualities.P360.value
            h.contains("240") || h == "LOWEST" -> Qualities.P240.value
            h.contains("144") || h == "MOBILE" -> Qualities.P144.value
            else -> getQualityFromName(h).takeIf { it > 0 } ?: Qualities.Unknown.value
        }
    }

    private fun originOf(url: String): String {
        return try {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            url.substringBefore("/", url)
        }
    }

    private fun resolveRelative(href: String, baseUrl: String): String {
        val t = href.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.startsWith("//") -> "https:$t"
            t.startsWith("/") -> "${originOf(baseUrl)}$t"
            else -> "${baseUrl.substringBeforeLast('/')}/$t"
        }
    }

    private fun buildDisplayName(
        baseProvider: String,
        qualityHint: String?,
        version: String = "sub",
        isDownload: Boolean = false
    ): String {
        val parts = mutableListOf("WitAnime - $baseProvider")
        if (!qualityHint.isNullOrBlank()) parts.add("[${qualityHint.uppercase()}]")
        if (version.equals("dub", ignoreCase = true)) parts.add("[مدبلج]")
        if (isDownload) parts.add("[DL]")
        return parts.joinToString(" ")
    }

    /**
     * Emits HLS links smartly:
     *  - If the master playlist declares demuxed `#EXT-X-MEDIA:TYPE=AUDIO` with `URI=`,
     *    ExoPlayer MUST receive the master `.m3u8` URL directly so audio tracks are preserved!
     *  - If muxed (no separate audio renditions), splits `#EXT-X-STREAM-INF` variants by quality,
     *    and also emits the master `.m3u8` if parsing yields no variants.
     */
    private suspend fun emitSmartHls(
        masterUrl: String,
        referer: String,
        sourceLabel: String,
        fallbackQuality: Int,
        extraHeaders: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ): Int {
        val count = AtomicInteger(0)
        val fetched = WitAnimeNetwork.httpGet(masterUrl, referer = referer, extraHeaders = extraHeaders)
        val m3u8Text = fetched?.second.orEmpty()

        if (m3u8Text.contains("#EXTM3U")) {
            val hasDemuxedAudio = Regex("""#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*URI=""", RegexOption.IGNORE_CASE)
                .containsMatchIn(m3u8Text)

            var maxDetectedHeight = 0
            val variants = mutableListOf<Pair<Int, String>>()
            val lines = m3u8Text.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val height = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""NAME="(\d+)p?"""", RegexOption.IGNORE_CASE)
                            .find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 0
                    if (height > maxDetectedHeight) maxDetectedHeight = height
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) {
                        j++
                    }
                    if (j < lines.size) {
                        val variantHref = lines[j].trim()
                        val fullVariant = WitAnimeNetwork.encodeUrlIfNeeded(resolveRelative(variantHref, masterUrl))
                        variants.add((if (height > 0) height else fallbackQuality) to fullVariant)
                    }
                    i = j
                }
                i++
            }

            if (hasDemuxedAudio || variants.isEmpty()) {
                val bestQ = if (maxDetectedHeight > 0) maxDetectedHeight else fallbackQuality
                callback(
                    newExtractorLink(
                        source = sourceLabel,
                        name = sourceLabel,
                        url = WitAnimeNetwork.encodeUrlIfNeeded(masterUrl),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = bestQ
                        if (extraHeaders.isNotEmpty()) this.headers = extraHeaders
                    }
                )
                return 1
            }

            val seenVar = HashSet<String>()
            for ((q, vUrl) in variants.sortedByDescending { it.first }) {
                if (!seenVar.add(vUrl)) continue
                callback(
                    newExtractorLink(
                        source = sourceLabel,
                        name = sourceLabel,
                        url = vUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = q
                        if (extraHeaders.isNotEmpty()) this.headers = extraHeaders
                    }
                )
                count.incrementAndGet()
            }
            if (count.get() > 0) return count.get()
        }

        // Fallback: emit master URL directly if reachable / signed
        if (fetched == null || fetched.first in 200..399) {
            callback(
                newExtractorLink(
                    source = sourceLabel,
                    name = sourceLabel,
                    url = WitAnimeNetwork.encodeUrlIfNeeded(masterUrl),
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = fallbackQuality
                    if (extraHeaders.isNotEmpty()) this.headers = extraHeaders
                }
            )
            return 1
        }
        return 0
    }

    // ── Central Dispatcher for WitAnime Watch & Download Sources ─────────────

    suspend fun extractWitSource(
        src: WitAnimeNetwork.ResolvedWitSource,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = src.resolvedUrl.trim()
        if (url.isEmpty()) return
        val lower = url.lowercase()
        val sName = src.serverName.lowercase()
        val qVal = qualityFromHint(src.qualityLabel)
        val isDl = src.kind == "download"

        try {
            when {
                lower.contains("videas.com") || lower.contains("videas.fr") || sName == "videas" || sName == "svidea" -> {
                    extractVideas(
                        url = url,
                        label = buildDisplayName("Videas", src.qualityLabel, src.version, isDl),
                        fallbackQuality = qVal,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                lower.contains("videa.hu") || lower.contains("videakid.hu") || sName == "videa" -> {
                    extractVidea(
                        url = url,
                        labelPrefix = buildDisplayName("Videa", null, src.version, isDl),
                        referer = src.referer,
                        callback = callback
                    )
                }

                lower.contains("mega.nz") || lower.contains("mega.co.nz") || sName == "mega" -> {
                    extractMega(
                        url = url,
                        label = buildDisplayName("MEGA", src.qualityLabel, src.version, isDl),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("hgcloud.") || lower.contains("vibuxer.") || lower.contains("hanerix.") ||
                    lower.contains("cavanhabg.") || lower.contains("dintezuvio.") ||
                    lower.contains("streamhg") || lower.contains("earnvids") || sName.contains("hgcloud") -> {
                    extractHgCloud(
                        url = url,
                        label = buildDisplayName("HGCloud", src.qualityLabel, src.version, isDl),
                        fallbackQuality = qVal,
                        referer = src.referer,
                        callback = callback
                    )
                }

                lower.contains("yonaplay.") || sName.contains("yonaplay") -> {
                    extractYonaPlay(
                        url = url,
                        referer = src.referer ?: "${WitAnimeNetwork.mainUrl}/",
                        version = src.version,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }

                lower.contains("dotplay.") || sName.contains("dotplay") -> {
                    extractDotPlay(
                        url = url,
                        referer = src.referer ?: "https://mid.yonaplay.net/",
                        label = buildDisplayName("DotPlay", src.qualityLabel, src.version, isDl),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("mp4upload.com") || sName.contains("mp4upload") -> {
                    extractMp4Upload(
                        url = url,
                        label = buildDisplayName("Mp4Upload", src.qualityLabel, src.version, isDl),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("ok.ru") || lower.contains("odnoklassniki.ru") || sName == "ok" -> {
                    extractOkRu(
                        url = url,
                        labelPrefix = buildDisplayName("OkRu", null, src.version, isDl),
                        fallbackQuality = qVal,
                        callback = callback
                    )
                }

                lower.contains("4shared.com") || sName.contains("4shared") -> {
                    extractFourShared(
                        url = url,
                        label = buildDisplayName("4Shared", src.qualityLabel, src.version, isDl),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("gofile.io") || sName.contains("gofile") -> {
                    extractGoFile(
                        url = url,
                        label = buildDisplayName("GoFile", src.qualityLabel, src.version, true),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("workupload.com") || sName.contains("workupload") -> {
                    extractWorkUpload(
                        url = url,
                        label = buildDisplayName("WorkUpload", src.qualityLabel, src.version, true),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("mediafire.com") || sName.contains("mediafire") -> {
                    extractMediaFire(
                        url = url,
                        label = buildDisplayName("MediaFire", src.qualityLabel, src.version, true),
                        quality = qVal,
                        callback = callback
                    )
                }

                lower.contains("mail.ru") || sName.contains("mailru") -> {
                    extractMailRu(
                        url = url,
                        labelPrefix = buildDisplayName("MailRu", null, src.version, isDl),
                        callback = callback
                    )
                }

                else -> {
                    // Universal CloudStream built-in fallback for any future third-party host
                    loadExtractor(url, src.referer ?: "${WitAnimeNetwork.mainUrl}/", subtitleCallback, callback)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ── 1. Videas (`app.videas.com` & `app.videas.fr`) ───────────────────────

    suspend fun extractVideas(
        url: String,
        label: String = "WitAnime - Videas",
        fallbackQuality: Int = Qualities.P1080.value,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url).ifBlank { "https://app.videas.com" }
        val normalizedUrl = url.replace("/embed/media/", "/embed/")
        val (_, html) = WitAnimeNetwork.httpGet(normalizedUrl, referer = "${WitAnimeNetwork.mainUrl}/") ?: return
        val doc = Jsoup.parse(html, normalizedUrl)

        var embedId = ""
        var opening = ""
        val cfgRaw = doc.selectFirst("script#videas-embed-config")?.data().orEmpty()
        if (cfgRaw.isNotBlank()) {
            runCatching { JSONObject(cfgRaw) }.getOrNull()?.let { cfg ->
                embedId = cfg.optString("embedId").trim()
                opening = cfg.optString("opening").trim()
            }
        }
        if (embedId.isEmpty()) {
            embedId = Regex("""/embed/(?:media/)?([0-9a-fA-F-]{20,})""").find(url)?.groupValues?.get(1).orEmpty()
        }
        if (embedId.isEmpty()) return

        val viewerId = UUID.randomUUID().toString()
        val visitorHeaders = buildMap {
            put("Accept", "application/json")
            put("Origin", origin)
            if (opening.isNotEmpty()) put("X-Embed-Opening", opening)
        }
        val (visCode, visJsonRaw) = WitAnimeNetwork.httpGet(
            url = "$origin/api/visitor/embed/$embedId/?viewer_id=$viewerId",
            referer = url,
            extraHeaders = visitorHeaders
        ) ?: return
        if (visCode !in 200..299 || visJsonRaw.isBlank()) return

        val visObj = runCatching { JSONObject(visJsonRaw) }.getOrNull() ?: return
        val playbackToken = visObj.optString("playback_token").trim()
        val sessionId = visObj.optString("session_id").trim()
        val items = visObj.optJSONArray("items") ?: JSONArray()

        // Optional subtitles from Videas visitor metadata
        for (idx in 0 until items.length()) {
            val itemObj = items.optJSONObject(idx) ?: continue
            val assetObj = itemObj.optJSONObject("asset") ?: continue
            val assetUid = assetObj.optString("uid").trim()
            if (assetUid.isEmpty()) continue

            assetObj.optJSONArray("subtitles")?.let { subs ->
                for (s in 0 until subs.length()) {
                    val sub = subs.optJSONObject(s) ?: continue
                    val subUrl = sub.optString("url").trim()
                    val subLang = sub.optString("label", sub.optString("language", "Arabic")).trim()
                    if (subUrl.isNotEmpty()) {
                        subtitleCallback(newSubtitleFile(subLang, resolveRelative(subUrl, origin)))
                    }
                }
            }

            val reqJson = JSONObject()
                .put("asset_id", assetUid)
                .put("playback_token", playbackToken)
                .put("session_id", sessionId)
                .toString()

            val (pbCode, pbRaw) = WitAnimeNetwork.httpPostJson(
                url = "$origin/api/visitor/playback/info/",
                jsonBody = reqJson,
                referer = url,
                extraHeaders = mapOf("Origin" to origin)
            ) ?: continue
            if (pbCode !in 200..299 || pbRaw.isBlank()) continue

            val pbObj = runCatching { JSONObject(pbRaw) }.getOrNull() ?: continue
            val hlsRaw = pbObj.optJSONObject("hls_url")?.optString("url")
                ?: pbObj.optString("hls_url").takeIf { it.startsWith("/") || it.startsWith("http") }

            if (!hlsRaw.isNullOrBlank()) {
                val fullHls = resolveRelative(hlsRaw, origin)
                val emitted = emitSmartHls(
                    masterUrl = fullHls,
                    referer = "$origin/",
                    sourceLabel = label,
                    fallbackQuality = fallbackQuality,
                    extraHeaders = mapOf("Origin" to origin),
                    callback = callback
                )
                if (emitted > 0) continue
            }

            val mp4Raw = pbObj.optJSONObject("source_url")?.optString("url")
                ?: pbObj.optString("source_url").takeIf { it.startsWith("/") || it.startsWith("http") }
            if (!mp4Raw.isNullOrBlank()) {
                val fullMp4 = WitAnimeNetwork.encodeUrlIfNeeded(resolveRelative(mp4Raw, origin))
                callback(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = fullMp4,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$origin/"
                        this.quality = fallbackQuality
                        this.headers = mapOf("Origin" to origin)
                    }
                )
            }
        }
    }

    // ── 2. Videa (`videa.hu`) ────────────────────────────────────────────────

    suspend fun extractVidea(
        url: String,
        labelPrefix: String = "WitAnime - Videa",
        referer: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.toHttpUrlOrNull()?.queryParameter("v")
            ?: Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('-').substringBefore('?').takeIf { it.length in 8..24 }
            ?: return

        val playerUrl = "https://videa.hu/player?v=$videoId"
        val (_, playerHtml) = WitAnimeNetwork.httpGet(playerUrl, referer = referer ?: "https://videa.hu/") ?: return
        val nonce = Regex("""_xt\s*=\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.get(1) ?: return

        val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
        val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""
        val unscrambled = buildString(32) {
            for (i in 0 until 32) {
                val ch = paramL[i]
                val idxInSecret = VIDEA_SECRET.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInSecret - 31)
                val safeIndex = when {
                    paramSPart.isEmpty() -> 0
                    index < 0 -> 0
                    index >= paramSPart.length -> paramSPart.length - 1
                    else -> index
                }
                append(if (paramSPart.isNotEmpty()) paramSPart[safeIndex] else 'a')
            }
        }

        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val seed = (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        val paramT = unscrambled.take(16).padEnd(16, '0')
        val rc4KeyPart = if (unscrambled.length > 16) unscrambled.substring(16) else ""

        val xmlEndpoint = "https://videa.hu/player/xml?platform=desktop&v=$videoId&_s=$seed&_t=$paramT"
        val (xmlCode, xmlHeaders, xmlBodyRaw) = WitAnimeNetwork.httpGetWithHeaders(
            url = xmlEndpoint,
            referer = playerUrl,
            extraHeaders = mapOf("Origin" to "https://videa.hu")
        ) ?: return
        if (xmlCode !in 200..299 || xmlBodyRaw.isBlank()) return

        val xVideaXs = xmlHeaders["x-videa-xs"].orEmpty()
        val xmlString = if (xmlBodyRaw.trimStart().startsWith("<")) {
            xmlBodyRaw
        } else {
            val decoded = runCatching { Base64.decode(xmlBodyRaw.trim(), Base64.DEFAULT) }.getOrNull() ?: return
            rc4DecryptUtf8(decoded, rc4KeyPart + seed + xVideaXs)
        }
        if (xmlString.isBlank()) return

        val xmlDoc = Jsoup.parse(xmlString, "", Parser.xmlParser())
        if (xmlDoc.selectFirst("error") != null) return

        val sources = mutableListOf<Triple<Int, String, String>>()
        for (src in xmlDoc.select("video_source")) {
            val qName = src.attr("name").trim()
            val rawVideoUrl = src.text().trim()
            if (rawVideoUrl.isEmpty()) continue
            val exp = src.attr("exp").trim()
            val md5 = xmlDoc.getElementsByTag("hash_value_$qName").firstOrNull()?.text()?.trim()
            val baseVideoUrl = if (rawVideoUrl.startsWith("//")) "https:$rawVideoUrl" else rawVideoUrl
            val signedUrl = if (!md5.isNullOrEmpty() && exp.isNotEmpty()) {
                "$baseVideoUrl?md5=$md5&expires=$exp"
            } else {
                baseVideoUrl
            }
            val qVal = qualityFromHint(qName)
            sources.add(Triple(qVal, qName, signedUrl))
        }

        for ((qVal, qName, signedUrl) in sources.sortedByDescending { it.first }) {
            val fullLabel = if (qName.isNotEmpty()) "$labelPrefix [$qName]" else labelPrefix
            callback(
                newExtractorLink(
                    source = fullLabel,
                    name = fullLabel,
                    url = WitAnimeNetwork.encodeUrlIfNeeded(signedUrl),
                    type = if (signedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = playerUrl
                    this.quality = qVal
                }
            )
        }
    }

    private fun rc4DecryptUtf8(data: ByteArray, key: String): String {
        if (key.isEmpty()) return ""
        val s = IntArray(256) { it }
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (idx in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            out[idx] = ((data[idx].toInt() and 0xFF) xor k).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    // ── 3. MEGA (`mega.nz` via local AES-128-CTR HTTP Range Proxy) ───────────

    suspend fun extractMega(
        url: String,
        label: String = "WitAnime - MEGA",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val localStreamUrl = WitAnimeMegaServer.registerMegaStream(url) ?: return
        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = localStreamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
            }
        )
    }

    // ── 4. HGCloud / StreamHG (`hgcloud.to` -> `vibuxer.com` / `hanerix.com`) ─

    suspend fun extractHgCloud(
        url: String,
        label: String = "WitAnime - HGCloud",
        fallbackQuality: Int = Qualities.P1080.value,
        referer: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('/').substringBefore('?').substringBefore('.')
        if (code.isBlank()) return

        val candidateUrls = LinkedHashSet<String>()
        val hostLower = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        // `hgcloud.to` is a pure JS redirector shell; jump straight to active player mirrors first!
        if (!hostLower.contains("hgcloud.")) {
            candidateUrls.add(url)
        }
        for (mirror in STREAMHG_MIRRORS) {
            candidateUrls.add("$mirror/e/$code")
        }
        if (hostLower.contains("hgcloud.")) {
            candidateUrls.add(url)
        }

        for (candidate in candidateUrls) {
            val candOrigin = originOf(candidate)
            val (status, html) = WitAnimeNetwork.httpGet(
                url = candidate,
                referer = referer ?: "${WitAnimeNetwork.mainUrl}/"
            ) ?: continue
            if (status !in 200..299 || html.length < 500) continue

            val combined = buildString {
                append(html)
                Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)\)\s*;?""", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(html)
                    .forEach { m ->
                        runCatching { JsUnpacker(m.value).unpack() }.getOrNull()?.let { unpacked ->
                            append('\n').append(unpacked)
                        }
                    }
            }

            val m3u8Candidates = LinkedHashSet<String>()
            for (keyName in listOf("hls4", "hls3", "hls2", "file")) {
                Regex("""["']$keyName["']\s*:\s*["']([^"']+)["']""")
                    .findAll(combined)
                    .forEach { m ->
                        val raw = m.groupValues[1].replace("\\/", "/").trim()
                        if (raw.contains(".m3u8") || raw.startsWith("/stream/")) {
                            m3u8Candidates.add(resolveRelative(raw, candOrigin))
                        }
                    }
            }
            if (m3u8Candidates.isEmpty()) {
                Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
                    .findAll(combined.replace("\\/", "/"))
                    .forEach { m3u8Candidates.add(it.value) }
            }

            var totalEmitted = 0
            for (hlsUrl in m3u8Candidates) {
                totalEmitted += emitSmartHls(
                    masterUrl = hlsUrl,
                    referer = "$candOrigin/",
                    sourceLabel = label,
                    fallbackQuality = fallbackQuality,
                    extraHeaders = mapOf("Origin" to candOrigin),
                    callback = callback
                )
                if (totalEmitted > 0) return
            }
        }
    }

    // ── 5. YonaPlay (`mid.yonaplay.net` AES-256-GCM Multi-Server) & DotPlay ──

    private fun decryptYonaPlayAesGcm(b64Data: String, pageKey: String): String? {
        return try {
            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(pageKey.toByteArray(StandardCharsets.UTF_8))
            val raw = Base64.decode(b64Data.trim(), Base64.DEFAULT)
            if (raw.size <= 28) return null
            val iv = raw.copyOfRange(0, 12)
            val tag = raw.copyOfRange(12, 28)
            val ciphertext = raw.copyOfRange(28, raw.size)
            val ctWithTag = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, ctWithTag, 0, ciphertext.size)
            System.arraycopy(tag, 0, ctWithTag, ciphertext.size, tag.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ctWithTag), StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {
            null
        }
    }

    suspend fun extractYonaPlay(
        url: String,
        referer: String = "${WitAnimeNetwork.mainUrl}/",
        version: String = "sub",
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = originOf(url).ifBlank { "https://mid.yonaplay.net" }
        val (session, html) = WitAnimeNetwork.openProtectedSubHost(url, referer = referer) ?: return

        var code = ""
        var embedToken = ""
        var pageKey = ""

        // Check inline `window._E = { c: "...", t: "...", k: "..." }` if present
        Regex("""window\._E\s*=\s*(\{[^;]+\})""").find(html)?.groupValues?.get(1)?.let { rawObj ->
            runCatching { JSONObject(rawObj) }.getOrNull()?.let { obj ->
                code = obj.optString("c").trim()
                embedToken = obj.optString("t").trim()
                pageKey = obj.optString("k").trim()
            }
        }

        val apiHeaders = mutableMapOf(
            "Origin" to origin,
            "Referer" to url
        )

        if (code.isEmpty() || pageKey.isEmpty()) {
            val (initCode, initBody) = session.postJson("$origin/api/init-session.php", "{}", apiHeaders) ?: return
            if (initCode !in 200..299 || initBody.isBlank()) return
            val initObj = runCatching { JSONObject(initBody) }.getOrNull() ?: return
            if (!initObj.optBoolean("success", false)) return
            code = initObj.optString("c").trim()
            embedToken = initObj.optString("t").trim()
            pageKey = initObj.optString("k").trim()
        }
        if (code.isEmpty() || pageKey.isEmpty()) return
        if (embedToken.isNotEmpty()) {
            apiHeaders["X-Embed-Token"] = embedToken
        }

        val srcPayload = JSONObject().put("code", code).toString()
        val (srcCode, srcBody) = session.postJson("$origin/api/sources.php", srcPayload, apiHeaders) ?: return
        if (srcCode !in 200..299 || srcBody.isBlank()) return

        val qualitiesObj = runCatching { JSONObject(srcBody).optJSONObject("qualities") }.getOrNull() ?: return
        for (qKey in listOf("4k", "fhd", "hd", "sd")) {
            val qGroup = qualitiesObj.optJSONObject(qKey) ?: continue
            val servers = qGroup.optJSONArray("servers") ?: continue
            for (i in 0 until servers.length()) {
                val srv = servers.optJSONObject(i) ?: continue
                val srvName = srv.optString("name", "Server").trim()
                val srvToken = srv.optString("token").trim()
                if (srvToken.isEmpty()) continue

                val apiPayload = JSONObject()
                    .put("code", code)
                    .put("token", srvToken)
                    .put("key", pageKey)
                    .toString()

                val (aCode, aBody) = session.postJson("$origin/api/api.php", apiPayload, apiHeaders) ?: continue
                if (aCode !in 200..299 || aBody.isBlank()) continue
                val aObj = runCatching { JSONObject(aBody) }.getOrNull() ?: continue
                val encD = aObj.optString("d").trim()
                if (encD.isEmpty()) continue
                val sendRef = aObj.optInt("r", 1) == 1

                val subUrl = decryptYonaPlayAesGcm(encD, pageKey)?.takeIf { it.startsWith("http") } ?: continue
                val subRef = if (sendRef) "$origin/" else null

                if (subUrl.contains("dotplay.", ignoreCase = true)) {
                    // Reuse the exact same Cloudflare-passing session first for DotPlay!
                    val emitted = extractDotPlayWithSession(
                        session = session,
                        url = subUrl,
                        referer = subRef ?: "$origin/",
                        label = buildDisplayName("YonaPlay ($srvName)", qKey.uppercase(), version),
                        quality = qualityFromHint(qKey),
                        callback = callback
                    )
                    if (!emitted) {
                        extractDotPlay(
                            url = subUrl,
                            referer = subRef ?: "$origin/",
                            label = buildDisplayName("YonaPlay ($srvName)", qKey.uppercase(), version),
                            quality = qualityFromHint(qKey),
                            callback = callback
                        )
                    }
                } else {
                    extractWitSource(
                        src = WitAnimeNetwork.ResolvedWitSource(
                            kind = "stream",
                            serverName = srvName,
                            qualityLabel = qKey.uppercase(),
                            version = version,
                            lang = if (version == "dub") "ara" else "jpn",
                            resolvedUrl = subUrl,
                            referer = subRef
                        ),
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }
        }
    }

    private suspend fun extractDotPlayWithSession(
        session: WitAnimeNetwork.ProtectedHttpSession,
        url: String,
        referer: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = originOf(url).ifBlank { "https://dotplay.net" }
        val (code, html) = session.get(
            url,
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer" to referer,
                "Upgrade-Insecure-Requests" to "1"
            )
        ) ?: return false
        if (code !in 200..299 || html.contains("error.php") || WitAnimeNetwork.isCloudflareChallenge(code, html)) {
            return false
        }

        val embedCode = Regex("""window\.embedCode\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: url.substringAfterLast('/').substringBefore('?')
        val embedToken = Regex("""window\.embedToken\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1).orEmpty()
        if (embedCode.isBlank()) return false

        val headers = buildMap {
            put("Accept", "application/json")
            put("Referer", url)
            if (embedToken.isNotBlank()) put("X-Embed-Token", embedToken)
        }
        val (apiCode, apiJson) = session.get("$origin/api.php?code=$embedCode", headers) ?: return false
        if (apiCode !in 200..299 || apiJson.isBlank()) return false

        val obj = runCatching { JSONObject(apiJson) }.getOrNull() ?: return false
        val encodedVideo = obj.optString("video_url").trim()
        if (encodedVideo.isEmpty()) return false

        val decoded = runCatching {
            String(Base64.decode(encodedVideo, Base64.DEFAULT), StandardCharsets.UTF_8)
        }.getOrNull()?.substringBefore('|')?.trim() ?: return false
        if (!decoded.startsWith("http")) return false

        val fullStreamUrl = WitAnimeNetwork.encodeUrlIfNeeded(decoded)
        val isHls = fullStreamUrl.contains(".m3u8", ignoreCase = true)
        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = fullStreamUrl,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$origin/"
                this.quality = quality
            }
        )
        return true
    }

    suspend fun extractDotPlay(
        url: String,
        referer: String = "https://mid.yonaplay.net/",
        label: String = "WitAnime - DotPlay",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val (session, _) = WitAnimeNetwork.openProtectedSubHost(url, referer = referer) ?: return
        extractDotPlayWithSession(session, url, referer, label, quality, callback)
    }

    // ── 6. Mp4Upload (`mp4upload.com`) ───────────────────────────────────────

    suspend fun extractMp4Upload(
        url: String,
        label: String = "WitAnime - Mp4Upload",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = if (url.contains("embed-")) {
            url
        } else {
            val id = url.trimEnd('/').substringAfterLast('/').substringBefore('.').substringBefore('?')
            if (id.isNotBlank()) "https://www.mp4upload.com/embed-$id.html" else url
        }

        val (_, html) = WitAnimeNetwork.httpGet(embedUrl, referer = "https://www.mp4upload.com/") ?: return
        val combined = buildString {
            append(html)
            Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)\)\s*;?""", setOf(RegexOption.DOT_MATCHES_ALL))
                .findAll(html)
                .forEach { m ->
                    runCatching { JsUnpacker(m.value).unpack() }.getOrNull()?.let { append('\n').append(it) }
                }
        }

        val mp4Url = Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.get(1)
            ?: Regex("""https?://[a-z0-9.-]*mp4upload\.com(?::\d+)?/d/[^"'\s<>]+\.mp4""", RegexOption.IGNORE_CASE)
                .find(combined)?.value
            ?: return

        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = WitAnimeNetwork.encodeUrlIfNeeded(mp4Url),
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.mp4upload.com/"
                this.quality = quality
                this.headers = mapOf("Origin" to "https://www.mp4upload.com")
            }
        )
    }

    // ── 7. OkRu (`ok.ru`) ────────────────────────────────────────────────────

    suspend fun extractOkRu(
        url: String,
        labelPrefix: String = "WitAnime - OkRu",
        fallbackQuality: Int = Qualities.P720.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val (_, html) = WitAnimeNetwork.httpGet(
            url = embedUrl,
            referer = "https://ok.ru/",
            extraHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
        ) ?: return

        val doc = Jsoup.parse(html, embedUrl)
        val optionsRaw = doc.selectFirst("[data-options]")?.attr("data-options").orEmpty()
        if (optionsRaw.isBlank()) return

        val optionsJson = runCatching { JSONObject(optionsRaw) }.getOrNull() ?: return
        val flashvars = optionsJson.optJSONObject("flashvars") ?: return
        val meta = flashvars.optJSONObject("metadata")
            ?: flashvars.optString("metadata").takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            }
            ?: return

        var emittedAny = false
        val videos = meta.optJSONArray("videos")
        if (videos != null) {
            val collected = mutableListOf<Triple<Int, String, String>>()
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                if (v.optBoolean("disallowed", false)) continue
                val vUrl = v.optString("url").trim()
                val vName = v.optString("name").trim()
                if (vUrl.isNotEmpty()) {
                    val q = qualityFromHint(vName).takeIf { it != Qualities.Unknown.value } ?: fallbackQuality
                    collected.add(Triple(q, vName.uppercase(), vUrl))
                }
            }
            for ((q, qLabel, vUrl) in collected.sortedByDescending { it.first }) {
                val fullName = if (qLabel.isNotEmpty()) "$labelPrefix [$qLabel]" else labelPrefix
                callback(
                    newExtractorLink(
                        source = fullName,
                        name = fullName,
                        url = WitAnimeNetwork.encodeUrlIfNeeded(vUrl),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://ok.ru/"
                        this.quality = q
                    }
                )
                emittedAny = true
            }
        }

        val hlsUrl = meta.optString("ondemandHls").ifBlank { meta.optString("hlsManifestUrl") }
        if (hlsUrl.isNotBlank() && !emittedAny) {
            emitSmartHls(
                masterUrl = hlsUrl,
                referer = "https://ok.ru/",
                sourceLabel = labelPrefix,
                fallbackQuality = fallbackQuality,
                callback = callback
            )
        }
    }

    // ── 8. FourShared (`4shared.com`) ────────────────────────────────────────

    suspend fun extractFourShared(
        url: String,
        label: String = "WitAnime - 4Shared",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""4shared\.com/(?:web/embed/file|embed|video|file|s)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: return
        val normalizedEmbed = "https://www.4shared.com/web/embed/file/$fileId"

        val (_, html) = WitAnimeNetwork.httpGet(normalizedEmbed, referer = "https://www.4shared.com/") ?: return
        val doc = Jsoup.parse(html, normalizedEmbed)
        val mp4Url = doc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
            ?: Regex("""https?://dc\d+\.4shared\.com/[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .find(html)?.value
            ?: return

        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = WitAnimeNetwork.encodeUrlIfNeeded(mp4Url),
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.4shared.com/"
                this.quality = quality
            }
        )
    }

    // ── 9. GoFile (`gofile.io` 2026 `X-Website-Token` + `.zip` filter) ───────

    private fun generateGoFileWebsiteToken(accountToken: String, lang: String = "ar"): String {
        val bucket = (System.currentTimeMillis() / 1000L) / 14_400L
        val payload = "${WitAnimeNetwork.userAgent}::$lang::$accountToken::$bucket::12af056dacea0b"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun extractGoFile(
        url: String,
        label: String = "WitAnime - GoFile [DL]",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val contentId = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (contentId.isBlank()) return

        val (_, accRaw) = WitAnimeNetwork.httpPostJson(
            url = "https://api.gofile.io/accounts",
            jsonBody = "{}",
            referer = "https://gofile.io/",
            extraHeaders = mapOf("Origin" to "https://gofile.io")
        ) ?: return
        val token = runCatching {
            JSONObject(accRaw).optJSONObject("data")?.optString("token").orEmpty()
        }.getOrDefault("")
        if (token.isBlank()) return

        val wt = generateGoFileWebsiteToken(token, "ar")
        val (_, contentRaw) = WitAnimeNetwork.httpGet(
            url = "https://api.gofile.io/contents/$contentId?page=1&pageSize=100&sortField=name&sortDirection=1",
            referer = "https://gofile.io/",
            extraHeaders = mapOf(
                "Authorization" to "Bearer $token",
                "X-Website-Token" to wt,
                "X-BL" to "ar",
                "Origin" to "https://gofile.io"
            )
        ) ?: return

        val dataObj = runCatching {
            JSONObject(contentRaw).optJSONObject("data")
        }.getOrNull() ?: return

        val fileEntries = mutableListOf<JSONObject>()
        if (dataObj.optString("type") == "file") {
            fileEntries.add(dataObj)
        } else {
            dataObj.optJSONObject("children")?.let { ch ->
                val keys = ch.keys()
                while (keys.hasNext()) {
                    ch.optJSONObject(keys.next())?.let { fileEntries.add(it) }
                }
            }
            dataObj.optJSONArray("children")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { fileEntries.add(it) }
                }
            }
        }
        if (fileEntries.isEmpty()) return

        val cookieHeader = "accountToken=$token"
        for (child in fileEntries) {
            val name = child.optString("name").lowercase()
            val link = child.optString("link").trim()
            val mimetype = child.optString("mimetype").lowercase()
            if (link.isEmpty()) continue
            if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z") ||
                mimetype.contains("zip") || mimetype.contains("rar")
            ) {
                continue
            }
            if (!WitAnimeNetwork.isPlayableDirectMedia(link, referer = "https://gofile.io/", cookie = cookieHeader)) {
                continue
            }
            val detectedQ = qualityFromHint(name).takeIf { it != Qualities.Unknown.value } ?: quality
            callback(
                newExtractorLink(
                    source = label,
                    name = label,
                    url = WitAnimeNetwork.encodeUrlIfNeeded(link),
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://gofile.io/"
                    this.quality = detectedQ
                    this.headers = mapOf("Cookie" to cookieHeader)
                }
            )
        }
    }

    // ── 10. WorkUpload (`workupload.com` SHA-256 PoW + `.zip` filter) ────────

    suspend fun extractWorkUpload(
        url: String,
        label: String = "WitAnime - WorkUpload [DL]",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileId = Regex("""workupload\.com/(?:file|start)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return@withContext

        val wuJar = WitAnimeNetwork.SessionCookieJar()
        val wuClient: OkHttpClient = WitAnimeNetwork.subHostFastClient.newBuilder()
            .cookieJar(wuJar)
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()

        val pageUrl = "https://workupload.com/file/$fileId"
        var (_, pageHtml) = WitAnimeNetwork.httpGet(pageUrl, clientOverride = wuClient) ?: return@withContext

        // Solve WorkUpload SHA-256 Proof-of-Work (`data.puzzle` + `data.find` + `data.range`)
        if (pageHtml.contains("solvePuzzle") || pageHtml.contains("/puzzle")) {
            val (_, puzRaw) = WitAnimeNetwork.httpGet(
                url = "https://workupload.com/puzzle",
                referer = pageUrl,
                extraHeaders = mapOf(
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                clientOverride = wuClient
            ) ?: return@withContext

            val puzData = runCatching { JSONObject(puzRaw).optJSONObject("data") }.getOrNull() ?: return@withContext
            val puzzle = puzData.optString("puzzle")
            val range = puzData.optInt("range", 50_000).coerceAtMost(200_000)
            val findArr = puzData.optJSONArray("find")

            if (puzzle.isNotEmpty() && findArr != null && findArr.length() > 0) {
                val findSet = HashSet<String>(findArr.length())
                for (i in 0 until findArr.length()) {
                    val target = findArr.optString(i).lowercase().trim()
                    if (target.isNotEmpty()) findSet.add(target)
                }
                val hexTable = "0123456789abcdef".toCharArray()
                val hexBuf = CharArray(64)
                val md = MessageDigest.getInstance("SHA-256")
                val puzzleBytes = puzzle.toByteArray(StandardCharsets.UTF_8)
                val solutions = ArrayList<String>(findSet.size)
                for (i in 0 until range) {
                    if ((i and 0xFFF) == 0) ensureActive()
                    md.reset()
                    md.update(puzzleBytes)
                    val digest = md.digest(i.toString().toByteArray(StandardCharsets.UTF_8))
                    for (bIdx in 0 until 32) {
                        val v = digest[bIdx].toInt() and 0xFF
                        hexBuf[bIdx * 2] = hexTable[v ushr 4]
                        hexBuf[bIdx * 2 + 1] = hexTable[v and 0x0F]
                    }
                    val hex = String(hexBuf)
                    if (hex in findSet) {
                        solutions.add(i.toString())
                        if (solutions.size >= findSet.size) break
                    }
                }
                if (solutions.isNotEmpty()) {
                    WitAnimeNetwork.httpPostForm(
                        url = "https://workupload.com/captcha",
                        fields = mapOf("captcha" to (solutions.joinToString(" ") + " ")),
                        referer = pageUrl,
                        extraHeaders = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        clientOverride = wuClient
                    )
                    pageHtml = WitAnimeNetwork.httpGet(pageUrl, referer = pageUrl, clientOverride = wuClient)?.second.orEmpty()
                }
            }
        }

        // Reject archive downloads (`.zip`, `.rar`, `.7z`) immediately via filename metadata
        val doc = Jsoup.parse(pageHtml, pageUrl)
        val fileNameMeta = (doc.title() + " " + doc.select("th, td, h1, span.filename").text()).lowercase()
        if (fileNameMeta.contains(".zip") || fileNameMeta.contains(".rar") || fileNameMeta.contains(".7z")) {
            return@withContext
        }

        val startUrl = "https://workupload.com/start/$fileId"
        WitAnimeNetwork.httpGet(startUrl, referer = pageUrl, clientOverride = wuClient)
        val (_, srvRaw) = WitAnimeNetwork.httpGet(
            url = "https://workupload.com/api/file/getDownloadServer/$fileId",
            referer = startUrl,
            extraHeaders = mapOf(
                "Accept" to "application/json",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            clientOverride = wuClient
        ) ?: return@withContext

        val dlUrl = runCatching {
            JSONObject(srvRaw).optJSONObject("data")?.optString("url").orEmpty().trim()
        }.getOrDefault("")
        if (!dlUrl.startsWith("http")) return@withContext

        val cookieHeader = wuJar.buildCookieHeader("https://workupload.com/")
        // Verify first 8 bytes are not PK\x03\x04 (`ZIP`) or HTML
        val probeReq = Request.Builder()
            .url(WitAnimeNetwork.encodeUrlIfNeeded(dlUrl))
            .header("User-Agent", WitAnimeNetwork.userAgent)
            .header("Referer", "https://workupload.com/")
            .apply { if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader) }
            .build()

        val validVideo = try {
            wuClient.newCall(probeReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val cDisp = resp.header("Content-Disposition").orEmpty().lowercase()
                if (cDisp.contains(".zip") || cDisp.contains(".rar") || cDisp.contains(".7z")) return@use false
                val magic = ByteArray(4)
                val n = resp.body?.byteStream()?.read(magic) ?: 0
                !(n == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && magic[2] == 0x03.toByte())
            }
        } catch (_: Exception) {
            false
        }
        if (!validVideo) return@withContext

        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = WitAnimeNetwork.encodeUrlIfNeeded(dlUrl),
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://workupload.com/"
                this.quality = quality
                if (cookieHeader.isNotBlank()) {
                    this.headers = mapOf("Cookie" to cookieHeader)
                }
            }
        )
    }

    // ── 11. MediaFire (`mediafire.com` — rejects `.zip` / `.rar` archives) ───

    suspend fun extractMediaFire(
        url: String,
        label: String = "WitAnime - MediaFire [DL]",
        quality: Int = Qualities.P1080.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val lowerUrl = url.substringBefore('?').lowercase()
        if (lowerUrl.contains(".zip") || lowerUrl.contains(".rar") || lowerUrl.contains(".7z")) {
            return
        }

        val (_, html) = WitAnimeNetwork.httpGet(url, referer = "https://www.mediafire.com/") ?: return
        val doc = Jsoup.parse(html, url)

        val dlBtn = doc.selectFirst("#downloadButton, a[aria-label='Download file'], a[href*='download']")
        val scrambled = dlBtn?.attr("data-scrambled-url")?.trim().orEmpty()
        val directUrl = if (scrambled.isNotEmpty()) {
            runCatching { String(Base64.decode(scrambled, Base64.DEFAULT), StandardCharsets.UTF_8).trim() }.getOrNull()
        } else {
            dlBtn?.attr("href")?.trim()?.takeIf { it.contains("mediafire.com") && it.startsWith("http") }
                ?: Regex("""https?://download\d*\.mediafire\.com/[^\s"'<>]+""").find(html)?.value
        } ?: return

        if (!WitAnimeNetwork.isPlayableDirectMedia(directUrl, referer = "https://www.mediafire.com/")) {
            return
        }

        callback(
            newExtractorLink(
                source = label,
                name = label,
                url = WitAnimeNetwork.encodeUrlIfNeeded(directUrl),
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://www.mediafire.com/"
                this.quality = quality
            }
        )
    }

    // ── 12. Mail.ru (`my.mail.ru` legacy support) ────────────────────────────

    suspend fun extractMailRu(
        url: String,
        labelPrefix: String = "WitAnime - MailRu",
        callback: (ExtractorLink) -> Unit
    ) {
        val mailJar = WitAnimeNetwork.SessionCookieJar()
        val mailClient = WitAnimeNetwork.directClient.newBuilder().cookieJar(mailJar).build()
        val (_, html) = WitAnimeNetwork.httpGet(url, referer = "https://my.mail.ru/", clientOverride = mailClient) ?: return

        val metaPath = Regex("""["']metadataUrl["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        val metaUrl = resolveRelative(metaPath, "https://my.mail.ru")
        val (_, metaRaw) = WitAnimeNetwork.httpGet(metaUrl, referer = url, clientOverride = mailClient) ?: return
        val videos = runCatching { JSONObject(metaRaw).optJSONArray("videos") }.getOrNull() ?: return

        val cookieStr = mailJar.buildCookieHeader("https://my.mail.ru/")
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val rawLink = v.optString("url").trim()
            val qKey = v.optString("key").trim()
            if (rawLink.isEmpty()) continue
            val fullUrl = resolveRelative(rawLink, "https://my.mail.ru")
            val qVal = qualityFromHint(qKey)
            val fullName = if (qKey.isNotEmpty()) "$labelPrefix [$qKey]" else labelPrefix
            callback(
                newExtractorLink(
                    source = fullName,
                    name = fullName,
                    url = WitAnimeNetwork.encodeUrlIfNeeded(fullUrl),
                    type = if (fullUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = qVal
                    if (cookieStr.isNotBlank()) this.headers = mapOf("Cookie" to cookieStr)
                }
            )
        }
    }
}

// ── Standalone CloudStream ExtractorApi Registrations ────────────────────────

open class WitAnimeVideasExtractor : ExtractorApi() {
    override val name = "Videas"
    override val mainUrl = "https://app.videas.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractVideas(url, subtitleCallback = subtitleCallback, callback = callback)
    }
}

class WitAnimeVideasFrExtractor : WitAnimeVideasExtractor() {
    override val name = "VideasFr"
    override val mainUrl = "https://app.videas.fr"
}

class WitAnimeVideaExtractor : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractVidea(url, referer = referer, callback = callback)
    }
}

class WitAnimeMegaExtractor : ExtractorApi() {
    override val name = "MegaNz"
    override val mainUrl = "https://mega.nz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractMega(url, callback = callback)
    }
}

class WitAnimeHgCloudExtractor : ExtractorApi() {
    override val name = "HGCloud"
    override val mainUrl = "https://hgcloud.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractHgCloud(url, referer = referer, callback = callback)
    }
}

class WitAnimeYonaPlayExtractor : ExtractorApi() {
    override val name = "YonaPlay"
    override val mainUrl = "https://mid.yonaplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractYonaPlay(
            url = url,
            referer = referer ?: "${WitAnimeNetwork.mainUrl}/",
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}

class WitAnimeDotPlayExtractor : ExtractorApi() {
    override val name = "DotPlay"
    override val mainUrl = "https://dotplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractDotPlay(
            url = url,
            referer = referer ?: "https://mid.yonaplay.net/",
            callback = callback
        )
    }
}

class WitAnimeMp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractMp4Upload(url, callback = callback)
    }
}

class WitAnimeOkRuExtractor : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractOkRu(url, callback = callback)
    }
}

class WitAnimeFourSharedExtractor : ExtractorApi() {
    override val name = "FourShared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractFourShared(url, callback = callback)
    }
}

class WitAnimeGoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractGoFile(url, callback = callback)
    }
}

class WitAnimeWorkUploadExtractor : ExtractorApi() {
    override val name = "WorkUpload"
    override val mainUrl = "https://workupload.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractWorkUpload(url, callback = callback)
    }
}

class WitAnimeMediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        WitAnimeExtractors.extractMediaFire(url, callback = callback)
    }
}

package com.stardima

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

/**
 * Mixdrop (mixdrop.top, redirects to mxdrop.top) extractor for StarDima.
 *
 * The `/e/<code>` embed page answers `302` to a sibling host and ships its
 * player config inside a P.A.C.K.E.R.-obfuscated script. Once decoded it reads:
 *
 *   MDCore.ref = "<code>"; ... wurl = "//a-delivery44.mxcontent.net/v2/<code>.mp4?s=..&e=.."
 *
 * The `wurl` is a progressive MP4 (token baked into the query). Its real
 * resolution is not in the page, so we read it from the media itself with a
 * small HTTP range request (the `tkhd` box inside `moov` carries the video
 * width/height as 16.16 fixed point). That height becomes the link `quality`,
 * so CloudStream labels the source "Mixdrop 480p" instead of "Mixdrop -1p".
 * If the probe fails for any reason the link is still emitted, labelled just
 * "Mixdrop".
 */
class MixdropExtractor : ExtractorApi() {
    override var name = "Mixdrop"
    override var mainUrl = "https://mixdrop.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = url.trim()
        val response = try {
            app.get(embed, headers = PAGE_HEADERS, referer = referer ?: "$mainUrl/")
        } catch (e: Throwable) {
            Log.i(TAG, "fetch failed ${embed.take(120)}: $e")
            return
        }
        val pageReferer = response.url.ifBlank { embed }
        val html = response.text

        val decoded = JsPacker.unpack(html)
        val raw = (decoded?.let { WURL_RE.find(it)?.groupValues?.get(1) })
            ?: WURL_RE.find(html)?.groupValues?.get(1)
            ?: return
        val streamUrl = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> raw
        }

        val type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        // Use the real video height as the quality so CloudStream shows e.g.
        // "Mixdrop 720p". When it cannot be read, fall back to the "unknown"
        // sentinel (whose label is empty) so it shows just "Mixdrop", not
        // "Mixdrop -1p".
        val quality = if (type == ExtractorLinkType.VIDEO) {
            resolveVideoHeight(streamUrl, pageReferer) ?: Qualities.Unknown.value
        } else {
            Qualities.Unknown.value
        }

        callback(
            ExtractorLink(
                source = streamUrl,
                name = name,
                url = streamUrl,
                referer = pageReferer,
                quality = quality,
                headers = mapOf("User-Agent" to BROWSER_UA),
                type = type
            )
        )
    }

    // ------------------------------------------------------------ MP4 probing

    /** Fetch just enough of the MP4 to read the video track height, or null. */
    private suspend fun resolveVideoHeight(mp4Url: String, referer: String): Int? {
        val head = safeGet(mp4Url, referer, "bytes=0-${HEAD_BYTES - 1}") ?: return null
        if (!rangeOk(head)) return null
        val headBytes = bodyBytes(head) ?: return null
        parseHeightFromTop(headBytes)?.let { return it }

        // moov is at the end (non-faststart): jump to where it should start and
        // read its first chunk, then fall back to scanning the file tail.
        val total = head.headers["Content-Range"]?.substringAfterLast('/')?.trim()?.toLongOrNull()
        if (total != null && total > HEAD_BYTES) {
            val moovStart = moovOffsetFromTop(headBytes, total)
            if (moovStart != null && moovStart < total) {
                val chunk = rangeChunk(mp4Url, referer, moovStart)
                if (chunk != null) parseMoov(chunk, 0, chunk.size)?.let { return it }
            }
            val tailStart = (total - HEAD_BYTES).coerceAtLeast(0)
            val tail = rangeChunk(mp4Url, referer, tailStart)
            if (tail != null) parseHeightFromTail(tail)?.let { return it }
        }
        return null
    }

    private suspend fun safeGet(
        url: String,
        referer: String,
        range: String
    ): com.lagradost.nicehttp.NiceResponse? = try {
        app.get(url, headers = rangeHeaders(referer, range), referer = referer)
    } catch (_: Throwable) {
        null
    }

    private suspend fun rangeChunk(url: String, referer: String, start: Long): ByteArray? {
        val response = safeGet(url, referer, "bytes=$start-${start + HEAD_BYTES - 1}") ?: return null
        if (!rangeOk(response)) return null
        return bodyBytes(response)
    }

    /** Refuse to read the body unless the server honoured the range (avoids a
     *  full-file download if `Range` was ignored). */
    private fun rangeOk(response: com.lagradost.nicehttp.NiceResponse): Boolean {
        if (response.code == 206) return true
        val length = response.headers["Content-Length"]?.trim()?.toLongOrNull() ?: return false
        return length <= HEAD_BYTES.toLong() * 2
    }

    private fun rangeHeaders(referer: String, range: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Range" to range,
        "Referer" to referer
    )

    private fun bodyBytes(response: com.lagradost.nicehttp.NiceResponse): ByteArray? = try {
        response.okhttpResponse.body?.bytes()
    } catch (_: Throwable) {
        null
    }

    /** Walk the top-level ISO-BMFF boxes and parse `moov` when it is up front. */
    private fun parseHeightFromTop(bytes: ByteArray): Int? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            var size = u32(bytes, pos)
            var header = 8L
            if (size == 1L) {
                if (pos + 16 > bytes.size) return null
                size = (u32(bytes, pos + 8) shl 32) or u32(bytes, pos + 12)
                header = 16L
            }
            val type = typeAt(bytes, pos + 4)
            if (type == "moov") {
                val end = (pos + size).coerceAtMost(bytes.size.toLong()).toInt()
                return parseMoov(bytes, pos + header.toInt(), end)
            }
            if (size < header) return null
            pos += size.toInt()
        }
        return null
    }

    /** When `moov` follows an `mdat` of known size, return its absolute offset. */
    private fun moovOffsetFromTop(bytes: ByteArray?, total: Long): Long? {
        if (bytes == null) return null
        var pos = 0L
        while (pos + 8 <= bytes.size && pos < total) {
            var size = u32(bytes, pos.toInt())
            var header = 8L
            if (size == 1L) {
                size = (u32(bytes, pos.toInt() + 8) shl 32) or u32(bytes, pos.toInt() + 12)
                header = 16L
            }
            val type = typeAt(bytes, pos.toInt() + 4)
            if (type == "mdat" && size > header) return pos + size
            if (size < header) return null
            pos += size
        }
        return null
    }

    private fun parseHeightFromTail(bytes: ByteArray): Int? {
        var i = bytes.size - 4
        while (i >= 0) {
            if (typeAt(bytes, i) == "moov") {
                val boxStart = i - 4
                if (boxStart >= 0) {
                    val size = u32(bytes, boxStart)
                    if (size >= 8) {
                        val end = (boxStart + size).coerceAtMost(bytes.size.toLong()).toInt()
                        parseMoov(bytes, i + 4, end)?.let { return it }
                    }
                }
            }
            i--
        }
        return null
    }

    /** Iterate the children of a `moov` box looking for the first video `trak`. */
    private fun parseMoov(bytes: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= bytes.size) {
            val size = u32(bytes, pos)
            val type = typeAt(bytes, pos + 4)
            if (type == "trak") {
                val trackEnd = (pos + size).coerceAtMost(end.toLong()).toInt()
                parseTrak(bytes, pos + 8, trackEnd)?.let { if (it > 0) return it }
            }
            if (size < 8) break
            pos += size.toInt()
        }
        return null
    }

    private fun parseTrak(bytes: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= bytes.size) {
            val size = u32(bytes, pos)
            val type = typeAt(bytes, pos + 4)
            if (type == "tkhd") {
                val tkhdEnd = (pos + size).coerceAtMost(end.toLong()).toInt()
                return parseTkhd(bytes, pos + 8, tkhdEnd)
            }
            if (size < 8) break
            pos += size.toInt()
        }
        return null
    }

    /** `tkhd` stores width/height as 16.16 fixed point at a version-dependent offset. */
    private fun parseTkhd(bytes: ByteArray, content: Int, end: Int): Int? {
        if (content >= bytes.size || content >= end) return null
        val version = bytes[content].toInt() and 0xFF
        val whOffset = content + if (version == 1) 88 else 76
        if (whOffset + 8 > bytes.size || whOffset + 8 > end) return null
        val width = (u32(bytes, whOffset) ushr 16).toInt()
        val height = (u32(bytes, whOffset + 4) ushr 16).toInt()
        return if (width in 16..8192 && height in 16..8192) height else null
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun typeAt(bytes: ByteArray, offset: Int): String =
        if (offset >= 0 && offset + 4 <= bytes.size) {
            String(bytes, offset, 4, Charsets.ISO_8859_1)
        } else {
            ""
        }

    companion object {
        private const val TAG = "StarDimaMixdrop"
        private const val HEAD_BYTES = 262144
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        private val WURL_RE = Regex("""\bwurl\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""")
    }
}

package com.stardima

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * StreamHG (hgcloud.to) extractor for StarDima.
 *
 * `hgcloud.to/e/<code>` only serves a JS loader that bounces to the actual
 * player host `vibuxer.com/e/<code>`. That page hides its JWPlayer setup inside
 * a P.A.C.K.E.R.-obfuscated script; once decoded it reads:
 *
 *   links = {"hls3":"https://<cdn>/..master.txt","hls2":"https://<cdn>/..master.m3u8?t=.."}
 *   sources: [{ file: links.hls4 || links.hls3 || links.hls2, type: "hls" }]
 *
 * We resolve the player page, decode the payload and hand the first available
 * HLS master to [M3u8Helper]. The CDN is Referer-gated (404 without
 * `Referer: https://vibuxer.com/`), so that header is carried through.
 */
class StreamHgExtractor : ExtractorApi() {
    override var name = "StreamHG"
    override var mainUrl = "https://hgcloud.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = url.trim()
        val code = embed.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return

        val host = try {
            Uri.parse(embed).host?.lowercase()
        } catch (_: Throwable) {
            null
        }
        val playerUrl = if (host != null && host.contains("vibuxer")) {
            embed
        } else {
            "$PLAYER_HOST/e/$code"
        }

        val html = try {
            app.get(playerUrl, headers = PAGE_HEADERS, referer = referer ?: "$mainUrl/").text
        } catch (e: Throwable) {
            Log.i(TAG, "fetch failed ${playerUrl.take(120)}: $e")
            return
        }

        val decoded = JsPacker.unpack(html)
        val stream = pickStream(decoded) ?: pickStream(html) ?: return
        emitM3u8(stream, callback)
    }

    /** The setup references `hls4 || hls3 || hls2`; take the highest present. */
    private fun pickStream(text: String?): String? {
        if (text == null) return null
        val numbered = HLS_RE.findAll(text)
            .mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] } }
            .toList()
        numbered.maxByOrNull { it.first }?.let { return it.second }
        return PLAIN_HLS_RE.find(text)?.groupValues?.get(1)
    }

    private suspend fun emitM3u8(m3u8: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "$PLAYER_HOST/"
        )
        val generated = try {
            M3u8Helper.generateM3u8(name, m3u8, "$PLAYER_HOST/", headers = headers)
        } catch (_: Throwable) {
            emptyList()
        }
        if (generated.isNotEmpty()) {
            generated.forEach { callback(it) }
            return
        }
        callback(
            ExtractorLink(
                source = m3u8,
                name = name,
                url = m3u8,
                referer = "$PLAYER_HOST/",
                quality = -1,
                headers = headers,
                type = ExtractorLinkType.M3U8
            )
        )
    }

    companion object {
        private const val TAG = "StarDimaStreamHg"
        private const val PLAYER_HOST = "https://vibuxer.com"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        private val HLS_RE = Regex("""hls(\d+)"\s*:\s*"([^"]+)"""")
        private val PLAIN_HLS_RE = Regex(""""hls"\s*:\s*"([^"]+)"""")
    }
}

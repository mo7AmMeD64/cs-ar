package com.stardima

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * Uqload (uqload.vc) extractor for StarDima.
 *
 * The embed page (`/embed-<code>.html`) hides its JW8 setup inside a
 * P.A.C.K.E.R.-obfuscated script. Once decoded it reads:
 *
 *   sources: [{ file: "https://strm12.uqload.vc/hls2/<..>/<code>_,l,n,.urlset/master.m3u8?t=..&e=..&.." }]
 *
 * We decode the payload and hand the HLS master to [M3u8Helper]. The master has
 * no anti-hotlink gate (served without a Referer), so playback is plain.
 */
class UqloadExtractor : ExtractorApi() {
    override var name = "Uqload"
    override var mainUrl = "https://uqload.vc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embed = url.trim()
        val html = try {
            app.get(embed, headers = PAGE_HEADERS, referer = referer ?: "$mainUrl/").text
        } catch (e: Throwable) {
            Log.i(TAG, "fetch failed ${embed.take(120)}: $e")
            return
        }

        val decoded = JsPacker.unpack(html)
        val m3u8 = (decoded?.let { SOURCES_RE.find(it)?.groupValues?.get(1) })
            ?: SOURCES_RE.find(html)?.groupValues?.get(1)
            ?: return
        emitM3u8(m3u8, embed, callback)
    }

    private suspend fun emitM3u8(m3u8: String, embed: String, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to embed
        )
        val generated = try {
            M3u8Helper.generateM3u8(name, m3u8, embed, headers = headers)
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
                referer = embed,
                quality = -1,
                headers = headers,
                type = ExtractorLinkType.M3U8
            )
        )
    }

    companion object {
        private const val TAG = "StarDimaUqload"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val PAGE_HEADERS = mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        private val SOURCES_RE = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""")
    }
}

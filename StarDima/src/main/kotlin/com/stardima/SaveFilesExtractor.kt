package com.stardima

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * SaveFiles (savefiles.com) extractor for StarDima.
 *
 * The `/e/<filecode>` embed page does not ship the stream; the real player
 * config is returned by POSTing `op=embed&file_code=..&auto=1&referer=..` to
 * `/dl`. The response is a plain JWPlayer setup whose sources carry the HLS
 * master on s1.savefiles.com.
 */
class SaveFilesExtractor : ExtractorApi() {
    override var name = "SaveFiles"
    override var mainUrl = "https://savefiles.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.trim().trimEnd('/')
        val fileCode = embedUrl.substringAfterLast("/")
        if (fileCode.isBlank()) return

        // Fast path: plain OkHttp, no browser needed.
        if (resolve(embedUrl, fileCode, cookie = null, callback)) return

        // Fallback: earn the Cloudflare challenge cookies in a hidden WebView,
        // then replay the same flow with the Cookie header attached.
        val cookie = try {
            CfxSolver.cookiesFor(embedUrl)
        } catch (_: Throwable) {
            null
        }
        if (cookie != null) {
            resolve(embedUrl, fileCode, cookie, callback)
        }
    }

    private suspend fun resolve(
        embedUrl: String,
        fileCode: String,
        cookie: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Seed the session cookie used by the /dl endpoint (harmless if it 403s).
        try {
            app.get(embedUrl, headers = baseHeaders(cookie), referer = embedUrl)
        } catch (_: Throwable) {
            // continue; /dl may still answer
        }

        val dlText = try {
            app.post(
                "$mainUrl/dl",
                data = mapOf(
                    "op" to "embed",
                    "file_code" to fileCode,
                    "auto" to "1",
                    "referer" to embedUrl,
                ),
                headers = baseHeaders(cookie) + mapOf(
                    "Referer" to embedUrl,
                    "Origin" to mainUrl,
                ),
                referer = embedUrl
            ).text
        } catch (e: Throwable) {
            android.util.Log.i(TAG, "SaveFiles /dl failed ${embedUrl.take(120)}: $e")
            return false
        }

        val m3u8 = M3U8_RE.find(dlText)?.groupValues?.get(1) ?: return false
        val generated = M3u8Helper.generateM3u8(name, m3u8, embedUrl, headers = m3u8Headers(embedUrl))
        if (generated.isNotEmpty()) {
            generated.forEach { callback(it) }
            return true
        }
        // Fallback: emit without the OkHttp-based M3u8Helper validation so the
        // player (Cronet, browser-like TLS) can fetch gated HLS CDNs itself.
        callback(
            ExtractorLink(
                source = m3u8,
                name = name,
                url = m3u8,
                referer = embedUrl,
                quality = -1,
                headers = m3u8Headers(embedUrl),
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }

    private fun baseHeaders(cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent" to BROWSER_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        ) + (cookie?.let { mapOf("Cookie" to it) } ?: emptyMap())

    /** The core's M3u8Helper validation fetch only sends stream headers, so
     *  carry the browser UA and embedding page as Referer explicitly. */
    private fun m3u8Headers(embedUrl: String): Map<String, String> =
        mapOf(
            "Referer" to embedUrl,
            "User-Agent" to BROWSER_UA
        )

    companion object {
        private const val TAG = "StarDimaSaveFiles"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val M3U8_RE = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""")
    }
}
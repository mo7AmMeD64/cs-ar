package com.animeslayer

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * MediaFire extractor, ported from the `mediafire()` routine in AnimeSlayer's
 * own `ol6.js`. The direct link is exposed either as a base64 `data-scrambled-url`
 * attribute or as the `href` of `a#downloadButton` (standard + `/file_premium/`
 * pages). Both are handled, in both flavours, for `.mp4`/`.mkv`/`.webm`.
 */
class AnimeSlayerMediafire : ExtractorApi() {
    override var mainUrl = "https://www.mediafire.com"
    override var name = "MediaFire"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val page = runCatching {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to UA)).text
        }.getOrNull() ?: return

        val direct = extractDirect(page) ?: return

        callback(
            newExtractorLink(name, "$name ${qualityName(direct)}", direct, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = qualityValue(direct)
                this.headers = mapOf("User-Agent" to UA)
            }
        )
    }

    private fun extractDirect(page: String): String? {
        // 1) base64 "data-scrambled-url" (some premium pages)
        Regex("""data-scrambled-url="([^"]+)"""").find(page)?.groupValues?.get(1)
            ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }

        // 2) the download button / "input popsok" anchor href
        Regex("""href="(https?://download\d+\.mediafire\.com/[^"]+)"""")
            .find(page)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.let { return it }

        // 3) any bare direct-link occurrence
        return Regex("""https?://download\d+\.mediafire\.com/[^"'\s<>\\]+""")
            .find(page)?.value
    }

    private fun qualityValue(url: String): Int = when {
        url.contains("uhd") || url.contains("1080") -> Qualities.P1080.value
        url.contains("_h.mp4") || url.contains("720") -> Qualities.P720.value
        url.contains("_s.mp4") || url.contains("480") -> Qualities.P480.value
        url.contains("360") -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun qualityName(url: String): String = when {
        url.contains("uhd") || url.contains("1080") -> "1080p"
        url.contains("_h.mp4") || url.contains("720") -> "720p"
        url.contains("_s.mp4") || url.contains("480") -> "480p"
        url.contains("360") -> "360p"
        else -> "auto"
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    }
}

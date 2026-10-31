package com.animerift

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/**
 * مستخرج الستريم تيب.
 *
 * خوادم `streamtape` لا تُعيد رابطاً مباشراً من البوابة بل **تذكرة** (ticket)
 * تحتاج إلى استبدالها عبر واجهة streamtape العامة:
 *
 *   GET https://api.streamtape.com/file/dl?file=<fileId>&ticket=<ticket>
 *   -> { "status":200, "result": { "name": "...", "size":..., "url":"https://<host>/radosgw/..." } }
 *
 * اسم الملف الأصلي يحوي التسمية العربية (مترجم عربي / مدبلج عربي) وهو
 * مفتاح ممتاز للتمييز بين النسخ.
 */
class RiftStreamtapeExtractor : ExtractorApi() {
    override var name = "RiftStreamtape"
    override var mainUrl = "https://api.streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = url.trim()
        if (target.isBlank()) return

        val fileId = Regex("file=([^&]+)").find(target)?.groupValues?.get(1) ?: return
        val ticket = Regex("ticket=([^&]+)").find(target)?.groupValues?.get(1) ?: return

        val text = try {
            app.get(
                "$mainUrl/file/dl?file=$fileId&ticket=$ticket",
                headers = mapOf(
                    "User-Agent" to RiftApi.CLIENT_UA,
                    "Accept" to "application/json",
                )
            ).text
        } catch (_: Exception) {
            return
        }

        val result = runCatching { JSONObject(text).optJSONObject("result") }.getOrNull() ?: return
        val streamUrl = result.optString("url")
        if (streamUrl.isBlank()) return
        val fileName = result.optString("name")

        val quality = Regex("(\\[(\\d{3,4}p)\\])", RegexOption.IGNORE_CASE)
            .find(fileName)?.groupValues?.get(2)
            ?: Regex("(\\d{3,4}p)", RegexOption.IGNORE_CASE).find(fileName)?.groupValues?.get(1)
            ?: ""

        val label = if (fileName.isNotBlank()) fileName else "$name ${quality.ifBlank { "" }}".trim()

        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = streamUrl,
                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.quality = when {
                    quality.contains("1080") -> Qualities.P1080.value
                    quality.contains("720") -> Qualities.P720.value
                    quality.contains("480") -> Qualities.P480.value
                    quality.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                this.headers = mapOf("User-Agent" to RiftApi.CLIENT_UA)
            }
        )
    }
}

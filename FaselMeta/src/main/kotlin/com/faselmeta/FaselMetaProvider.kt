package com.faselmeta

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FaselMetaProvider : TmdbProvider() {

    override var name = "FaselHD Meta"
    override var mainUrl = "https://www.faselhd.cam"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        private const val BACKEND = "http://145.241.158.129:3112"
        private const val NUVO_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        private val json = Json { ignoreUnknownKeys = true }
    }

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = (this[k] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
            if (!v.isNullOrBlank() && v != "null") return v
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            // TmdbLink JSON: {"imdbID":..,"tmdbID":..,"episode":..,"season":..,"movieName":..}
            val o = json.parseToJsonElement(data) as? JsonObject ?: return false
            val tmdbId = o.str("tmdbID", "tmdbId")?.toIntOrNull() ?: return false
            val season = o.str("season")?.toIntOrNull()
            val episode = o.str("episode")?.toIntOrNull()
            val type = if (season != null && episode != null) "series" else "movie"
            val idStr = if (type == "series") "$tmdbId:$season:$episode" else tmdbId.toString()

            val resp = app.get("$BACKEND/resolve/$type/$idStr", headers = mapOf("User-Agent" to NUVO_UA)).text
            val streams = (json.parseToJsonElement(resp) as? JsonObject)
                ?.get("streams") as? JsonArray ?: return false

            var got = false
            for (s in streams) {
                val o = s as? JsonObject ?: continue
                val url = o.str("url", "file", "src", "link") ?: continue
                if (!url.startsWith("http")) continue
                val label = o.str("name", "title", "server") ?: "FaselHD"
                val quality = o.str("quality", "label", "q").orEmpty()
                val linkType = when {
                    url.contains(".mp4") -> ExtractorLinkType.VIDEO
                    else -> ExtractorLinkType.M3U8
                }
                val headers = (o["headers"] as? JsonObject)
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap()
                    ?: mapOf("User-Agent" to NUVO_UA)

                callback(
                    newExtractorLink(name, listOf(label, quality).filter { it.isNotBlank() }.joinToString(" "), url, linkType) {
                        this.headers = headers
                        this.quality = quality.replace("p", "").toIntOrNull()
                            ?.let { q -> Qualities.entries.firstOrNull { it.value == q }?.value }
                            ?: Qualities.Unknown.value
                    }
                )
                got = true
            }
            got
        } catch (_: Exception) {
            false
        }
    }
}

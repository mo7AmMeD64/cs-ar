package com.dramalive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * DramaLive (dramalivedl.com, com.sneig.livedrama) — CloudStream provider.
 *
 * The OLD chattyradio live-TV protocol (from the dramalive.har capture + APK reverse):
 * - ALL requests/responses are AES-128-CBC: key="0123456789abcdef", IV="fedcba9876543210"
 *   (from the APK: o8/r.smali field a + const-string). The body = base64(cipher):base64(iv)
 *   (PHP-style "\/" slashes; the response may have leading whitespace).
 * - POST {host}/getliveTopics {type:"tv"}            -> 265 topics (categories/countries)
 * - POST {host}/getLiveByTopic {type,topic}          -> the channels: {id_live,name,agent,img_url}
 * - POST {host}/getLiveAllStreamsById {id}          -> live:{url:".LS.V2 handler",agent,backup}
 * - POST {fastapisource}/redirect/getLiveByRedirect {id,url,agent} -> data:{url,agent}
 *   (the "advanced"/"double_redirect" agents = link-lock pages; "1"/"shai" = the stream is offline)
 * - The final m3u8 (https://{rand}.{rand}.net:8443/hls/{id}.m3u8?s=&e=) is behind obfuscated
 *   player JS (quotarevival/lockpop/rap4 pages with window._econfig) -> WebViewResolver.
 */
class DramaLiveProvider : MainAPI() {

    override var name = "DramaLive"
    override var mainUrl = "http://ads.chattyradio.com/api/live/livedrama/v13.0.0"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val REDIRECT_HOST = "http://4f2ffff1d1eede14a007949cf4b27785.fastapisource.shop/redirect"
        private const val UA = "Dalvik/2.1.0 (Linux; U; Android 14; 23043RP34G Build/UKQ1.240624.001)"
        private val json = Json { ignoreUnknownKeys = true }
    }

    // ---------- crypto ----------

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("0123456789abcdef".toByteArray(), "AES"),
            IvParameterSpec("fedcba9876543210".toByteArray()))
        Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    } catch (_: Exception) { null }

    private fun decrypt(body: String?): String? = try {
        val b64 = body?.trim()?.split(':')?.getOrNull(0) ?: return null
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("0123456789abcdef".toByteArray(), "AES"),
            IvParameterSpec("fedcba9876543210".toByteArray()))
        String(cipher.doFinal(raw))
    } catch (_: Exception) { null }

    private val client = okhttp3.OkHttpClient()

    /** the API call: device payload + query -> POST the raw encrypted body (NO form key!)
     *  NOTE: the payload must be serialized with kotlinx (org.json.JSONObject drops
     *  kotlinx JsonElement values -> the query fields vanish -> no channels!) */
    private fun api(endpoint: String, query: JsonObject): JsonObject? {
        val dev = kotlinx.serialization.json.buildJsonObject {
            put("user_id", "_12345_${System.currentTimeMillis()}_notloggedin.com_dramalive3")
            put("device_id", "a1b2c3d4-e5f6-7890-abcd-ef0123456789")
            put("device_api", "34")
            put("version_name", "185")
            put("language", "en")
            put("timezone", "Asia/Baghdad")
            put("device_type", "phone")
            put("KEY_ACTIVATED_TYPE", "202122")
            put("store", "playStore")
            put("isStoreVersion", false)
            put("isPremium", false)
            put("isCoupon_active", false)
            put("hideAds", false)
            put("appCount", "{}")
            put("mainServer", mainUrl)
            query.forEach { (k, v) -> put(k, v) }
        }
        val payload = encrypt(dev.toString()) ?: return null
        val host = if (endpoint.startsWith("getLiveByRedirect")) REDIRECT_HOST else mainUrl
        return try {
            val body = payload.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val req = okhttp3.Request.Builder()
                .url("$host/$endpoint")
                .header("User-Agent", UA)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                json.parseToJsonElement(decrypt(resp.body?.string() ?: return null) ?: return null).jsonObject
            }
        } catch (_: Exception) { null }
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "bein_sport" to "Bein Sport",
        "mbc" to "MBC",
        "hot_now" to "Most Watched",
        "ar_1" to "Arabic Entertainment",
        "ar_2" to "News",
        "osn" to "OSN",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val j = api("getLiveByTopic", json.parseToJsonElement("""{"type":"tv","topic":"${request.data}"}""").jsonObject)
        val items = j?.arr("live")?.mapNotNull { el ->
            val o = el.jsonObject
            val id = o.str("id_live") ?: return@mapNotNull null
            newTvSeriesSearchResponse(o.str("name") ?: id, "dramalive://chan/$id", TvType.Live) {
                this.posterUrl = o.str("img_url")
            }
        } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (topic in listOf("bein_sport", "mbc", "hot_now", "ar_1", "osn", "netflix", "rotana", "art", "shahid")) {
            val j = api("getLiveByTopic", json.parseToJsonElement("""{"type":"tv","topic":"$topic"}""").jsonObject) ?: continue
            j.arr("live")?.forEach { el ->
                val o = el.jsonObject
                val id = o.str("id_live") ?: return@forEach
                val name = o.str("name") ?: return@forEach
                if (name.lowercase().contains(q)) {
                    out.add(newTvSeriesSearchResponse(name, "dramalive://chan/$id", TvType.Live) {
                        this.posterUrl = o.str("img_url")
                    })
                }
            }
            if (out.size > 30) break
        }
        return out.distinctBy { it.url }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("dramalive://")) return null
        val id = url.substringAfter("dramalive://chan/", "").trim()
        if (id.isBlank()) return null

        val j = api("getLiveAllStreamsById", json.parseToJsonElement("""{"id":"$id"}""").jsonObject) ?: return null
        val live = j.obj("live") ?: return null
        val name = live.str("name") ?: id
        val poster = live.str("img_url") ?: api("getLiveByTopic", json.parseToJsonElement("""{"type":"tv","topic":"bein_sport"}""").jsonObject)
            ?.arr("live")?.firstOrNull { it.jsonObject.str("id_live") == id }?.jsonObject?.str("img_url")

        val handlers = mutableListOf<Pair<String, String>>() // url to agent
        live.str("url")?.let { handlers.add(it to (live.str("agent") ?: "redirect")) }
        live.str("backup")?.takeIf { it.isNotBlank() }?.let { handlers.add(it to "redirect") }
        if (handlers.isEmpty()) return null

        // fetch all the redirect targets (sequential — 1-2 requests)
        val dataUrls = handlers.mapNotNull { (u, agent) ->
            val d = api("getLiveByRedirect", json.parseToJsonElement(
                """{"id":"$id","url":"${u.replace("\"", "\\\"")}","agent":"$agent"}"""
            ).jsonObject)?.obj("data") ?: return@mapNotNull null
            val inner = d.str("url") ?: return@mapNotNull null
            val a = d.str("agent") ?: ""
            if (inner == "1" || inner.isBlank()) return@mapNotNull null // offline stub
            val real = if (inner.startsWith("{")) runCatching { json.parseToJsonElement(inner).jsonObject.str("url") }.getOrNull() ?: inner else inner
            real to a
        }.filter { it.first.startsWith("http") }

        if (dataUrls.isEmpty()) return null

        return newMovieLoadResponse(name, url, TvType.Live, "dramalive://links/" +
            dataUrls.joinToString("||") { "${it.first}::${it.second}" }) {
            this.posterUrl = poster
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.contains("dramalive://links/")) {
            val payload = data.substringAfter("dramalive://links/", "")
            val pairs = payload.split("||").mapNotNull {
                val (u, a) = it.split("::", limit = 2).let { l -> l.getOrNull(0) to l.getOrNull(1) }
                u?.takeIf { it.startsWith("http") }?.let { it to (a ?: "") }
            }
            var got = false
            for ((u, agent) in pairs.distinctBy { it.first }) {
                try {
                    // 1. the direct m3u8 check (some advanced pages 302 to the m3u8)
                    if (u.contains(".m3u8")) {
                        callback(newExtractorLink(name, "DramaLive", u, ExtractorLinkType.M3U8) {
                            this.referer = u.substringBeforeLast("/")
                            this.quality = Qualities.Unknown.value
                        })
                        got = true
                        continue
                    }
                    // 2. the link-lock pages: the obfuscated player JS must run -> real WebView
                    val resolver = WebViewResolver(Regex("\\.m3u8"))
                    val res = app.get(u, interceptor = resolver, headers = mapOf("User-Agent" to CHROME_UA))
                    val m3u8 = Regex("(https?://[^\"'\\s]*\\.m3u8[^\"'\\s]*)").find(res.text)?.groupValues?.get(1)
                    if (m3u8 != null) {
                        callback(newExtractorLink(name, "DramaLive", m3u8, ExtractorLinkType.M3U8) {
                            this.referer = u
                            this.quality = Qualities.Unknown.value
                        })
                        got = true
                    }
                } catch (_: Exception) {}
            }
            return got
        }
        return false
    }

    private val CHROME_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    // ---------- json helpers ----------

    // NOTE: null-valued keys ("live":null) — the throwing .jsonArray crashes on JsonNull
    private fun JsonObject.arr(key: String): kotlinx.serialization.json.JsonArray? =
        this[key] as? kotlinx.serialization.json.JsonArray

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]?.jsonPrimitive?.contentOrNull
            if (v != null && v != "null") return v
        }
        return null
    }
}

package com.cimacloud

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cima Cloud (cima-cloud.com app) — CloudStream provider.
 *
 * Fully reverse-engineered from the CimaCloud 1.3 APK + its traffic captures.
 * The private API lives behind Cloudflare on a bare IP-ish domain that is fetched
 * live from the app's own config (so the domain can rotate without an update):
 *
 *   GET  https://cloud-day.online/v1.3/CloudDay/uploads/config_v1.3.json
 *         -> data[0].cloudday = "https://1654865.xyz"   (API base)
 *
 * Catalog (no auth, User-Agent "okhttp/4.10.0" like the app):
 *   GET  /v1.3/api/home                        -> sections[] (slider/episodes/...)
 *   POST /v1.3/api/all    type,sort,page[,category][,year]
 *         type: 0=all 1=movies 2=series | sort: 1=newest 2=oldest | page >= 1
 *   POST /v1.3/api/search title,type,sort,page
 *   GET  /v1.3/api/series/{id}[/episodes]
 *   GET  /v1.3/api/movie/{id}
 *
 * Two custom headers protect the sensitive endpoints (details + servers):
 *   firebase_id   — hybrid attestation token: a fixed JSON payload (app id,
 *                   signing-cert hash + base.apk path, integrity flags,
 *                   unix timestamp + random nonce) encrypted with a random
 *                   AES-256-GCM key, itself wrapped with the app's embedded
 *                   RSA-2048 public key. Base64 blobs joined with "." (698 chars).
 *                   Must be FRESH per request (the server rejects replays).
 *   cloudflare-id — 15 random alphanumerics + a 16-hex "device id".
 *
 * The servers responses are NOT JSON: they are Base64 of AES-256-CBC where
 *   key = hex(MD5("Gm4il@G00Gl3.Com" + deviceId))
 *   iv  = hex(MD5(deviceId + "Gm4il@G00Gl3.Com")).take(16)
 * -> [{"id":..., "type":"embed", "link":"https://..."}, ...]
 *
 * Server links are then resolved:
 *   - direct mp4/m3u8            -> as-is (Chrome UA)
 *   - photos.google.com/share/.. -> share page [data-url] -> lh3 MPD (DASH,
 *                                   Origin/Referer = photos.google.com), like the app
 *   - hrrejhp.com/aminegoogle/googlefinal.php?url=<photos link> -> unwrapped first
 *   - *.developer-pro.workers.dev/get-links?id=.. -> JSON of media links
 *   - vidtube/fasel/...          -> the developer's own
 *                                   cloud-day.online/cimacloud/extractor.php
 *                                   (returns labeled m3u8 links + referer/origin)
 *   - egybestvid/vidspeed/...    -> scraped JWPlayer page (incl. p.a.c.k.e.d eval)
 *   - anything else              -> delegated to CloudStream's loadExtractor()
 */
class CimaCloudProvider : MainAPI() {

    override var name = "Cima Cloud"
    override var mainUrl = "https://1654865.xyz" // cloudday; resolved live from the app config
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
    )

    companion object {
        private const val FALLBACK_API_BASE = "https://1654865.xyz"
        private const val CONFIG_URL =
            "https://cloud-day.online/v1.3/CloudDay/uploads/config_v1.3.json"
        private const val API = "/v1.3/api"
        private const val EXTRACTOR_URL = "https://cloud-day.online/cimacloud/extractor.php?url="

        private const val UA_OKHTTP = "okhttp/4.10.0"
        private const val UA_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.77 Safari/537.36"
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

        // ------------------------------------------------------------ attestation
        private const val ATTEST_APP_ID = "21406135"
        private const val ATTEST_CERT_PATH =
            "021bb8f6029e4e63e4b5b0ff922788b4fe24739e261e637dddf70bf95e0aacd7" +
                "|/data/app/com.app.cimacloud-1/base.apk"
        private const val RSA_PUB_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwDPYEppEb3xys/R99+zmZW54vhcQIcV+6" +
                "+BtHI/Qmb4I0M2hw0fQ9fzLY/RhKM33yuOOZNAulpq4ByJ7b556fkkplaPMI3vbESmoJqCk3Q" +
                "Ch65Nbi4mKRFFJyDkJVepBX2BDr3+zECzdSy0oVSn+nFqw5YJcS+d06Lgyyr5oZSaZSeGh9F" +
                "tgJkuz17n265rLB6ruUUrHQVeEDl8ph3t/x/NynwtwQDA5VX+a/AVmedgb2JZCQLry8Za/lvs" +
                "q4Lhuthcts6gtVq3uPExSKdLBpbqbwQNvhRgB3IEYV47OyddG32qbriJbxdvW+n5OzowiYQr" +
                "sC8hG65GPBGCuj3zO7wIDAQAB"

        // --------------------------------------------- servers-response crypto
        // XOR of these two arrays from the app yields the ASCII key base
        private val KEY_H0 = byteArrayOf(
            0x1d, 0x51, 0x45, 0x47, 0x04, 0x5d, 0x08, 0x23,
            0x6a, 0x7b, 0x1d, 0x1d, 0x46, 0x5e, 0x20, 0x7e,
        )
        private val KEY_I0 = byteArrayOf(0x5a, 0x3c, 0x71, 0x2e, 0x68, 0x1d, 0x4f, 0x13)
        private val KEY_BASE: String = buildString {
            for (i in KEY_H0.indices) {
                append(((KEY_H0[i].toInt() xor KEY_I0[i % KEY_I0.size].toInt()) and 0xFF).toChar())
            }
        }

        private const val MPD_SUFFIX =
            "=mm,dash-vm-vf,dr.sdr,sdrCodec.vp9.h264?alr=true&mpd_version=5&pacing=0"
        private val CF_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val HEX = "0123456789abcdef"

        /** hosts handled by the developer's own extractor.php (vidtube = "TB", fasel = "FS", ...) */
        private val EXTRACTOR_PHP_DOMAINS = listOf(
            "vidtube", "fasel", "ukrcdn", "upns", "uns.bio", "videoland",
            "rpmhub", "lalalala", "web51018x", "web71512x",
        )

        /** JWPlayer-style embeds we scrape ourselves (sources:[{file:...}] incl. p.a.c.k.e.d) */
        private val JW_EMBED_DOMAINS = listOf(
            "egybestvid", "vidspeed", "anamov", "anafast", "aflam", "aflaam",
            "extreamnow", "mp4plus", "vidoba", "mirvad", "mwdy", "vidmoly",
        )
    }

    private val secureRandom = SecureRandom()

    /** plays the role of the android_id in the app's crypto (random per install/session) */
    private val deviceId: String = buildString { repeat(16) { append(HEX[secureRandom.nextInt(16)]) } }

    // ================================================================ helpers

    private fun String.toJsonObject(): JsonObject? = try {
        if (isBlank()) null else Json.parseToJsonElement(this).jsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

    private fun JsonObject.ok(): Boolean = this["status"]?.jsonPrimitive?.booleanOrNull == true

    private fun hostOf(url: String): String? =
        try { URI(url).host?.lowercase() } catch (e: Exception) { null }

    private fun fixImg(url: String?): String? =
        url?.trim()?.takeIf { it.startsWith("http") }
            ?.let { if (it.startsWith("http://")) it.replaceFirst("http", "https") else it }

    private fun JsonObject.categoryIds(): List<Int> =
        arr("categories")?.objects()?.mapNotNull { it["id"]?.jsonPrimitive?.intOrNull }
            ?: emptyList()

    private fun JsonObject.tagNames(): List<String> =
        arr("genres")?.objects()?.mapNotNull { g -> g.str("name")?.trim()?.takeIf { it.isNotEmpty() } }
            ?: emptyList()

    private fun JsonObject.yearValue(): Int? =
        int("year") ?: str("release_date")?.take(4)?.toIntOrNull()

    private fun serverLabel(url: String): String {
        val host = hostOf(url) ?: return "سيرفر"
        val clean = host.removePrefix("www.").removePrefix("s1.")
        return "سيرفر ${clean.substringBefore('.')}"
    }

    /**
     * CloudStream absolutizes our relative URLs (fixUrl / watch history prefix
     * mainUrl), so accept both "series/123" and "https://<host>/series/123".
     * Returns (kind, id) with kind in {movie, series, ep} or null.
     */
    private fun parseTarget(raw: String): Pair<String, String>? {
        var u = raw.trim()
        Regex("""(?i)^[a-z]+://[^/]+/""").find(u)?.let { u = u.substring(it.value.length) }
        u = u.substringBefore('?').substringBefore('#')
        val parts = u.trim('/').split('/')
        val kind = parts.getOrNull(0)?.lowercase()
        val id = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return if (kind == "movie" || kind == "series" || kind == "ep") kind to id else null
    }

    // ================================================================ crypto

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Hybrid attestation token the app sends as `firebase_id`:
     * AES-256-GCM (random key + 12-byte IV) over a fixed JSON payload,
     * the AES key wrapped with the embedded RSA-2048 public key,
     * three Base64 blobs joined with "." (698 chars like the real app).
     * A fresh one is required for every request.
     */
    private fun makeFirebaseId(): String {
        val payload = "{\"a\":\"$ATTEST_APP_ID\",\"b\":\"$ATTEST_CERT_PATH\"," +
            "\"c\":\"false\",\"d\":\"empty\",\"e\":\"empty\",\"f\":\"false\"," +
            "\"g\":\"false\",\"h\":\"false\",\"t\":${System.currentTimeMillis() / 1000}," +
            "\"n\":\"${UUID.randomUUID().toString().replace("-", "").substring(0, 16)}\"}"

        val aesKey = ByteArray(32).also(secureRandom::nextBytes)
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = gcm.doFinal(payload.toByteArray(Charsets.UTF_8)) // includes 16-byte tag

        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.decode(RSA_PUB_B64, Base64.NO_WRAP))),
        )
        val wrappedKey = rsa.doFinal(aesKey)

        return Base64.encodeToString(wrappedKey, Base64.NO_WRAP) + "." +
            Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /** 15 random alphanumerics + our device id (exactly how the app builds it) */
    private fun makeCloudflareId(): String =
        buildString {
            repeat(15) { append(CF_ALPHABET[secureRandom.nextInt(62)]) }
            append(deviceId)
        }

    /** Decrypt the AES-256-CBC servers list (key & IV derived from our device id). */
    private fun decryptServers(b64Body: String): List<String>? {
        return try {
            val ciphertext = Base64.decode(b64Body.trim(), Base64.DEFAULT)
            val key = md5Hex(KEY_BASE + deviceId).toByteArray(Charsets.UTF_8)
            val iv = md5Hex(deviceId + KEY_BASE).substring(0, 16).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val json = Json.parseToJsonElement(String(cipher.doFinal(ciphertext), Charsets.UTF_8)).jsonObject
            if (!json.ok()) return null
            json["servers"]?.jsonArray
                ?.mapNotNull { el -> (el as? JsonObject)?.str("link") }
                ?.filter { it.startsWith("http") }
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // ================================================================ network

    @Volatile
    private var apiBaseCache: String? = null

    /** The app's own trick: the API domain comes from its remote config. */
    private suspend fun apiBase(): String {
        apiBaseCache?.let { return it }
        return try {
            val cfg = app.get(CONFIG_URL, headers = mapOf("User-Agent" to UA_OKHTTP))
                .text.toJsonObject()
            val base = cfg?.arr("data")?.objects()?.firstOrNull()
                ?.str("cloudday")?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("http") }
            (base ?: FALLBACK_API_BASE).also { apiBaseCache = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FALLBACK_API_BASE
        }
    }

    private suspend fun apiGet(path: String, headers: Map<String, String> = emptyMap()): JsonObject? =
        try {
            app.get(
                "${apiBase()}$API/$path",
                headers = mapOf("User-Agent" to UA_OKHTTP) + headers,
            ).text.toJsonObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun apiGetRaw(path: String, headers: Map<String, String> = emptyMap()): String? =
        try {
            app.get(
                "${apiBase()}$API/$path",
                headers = mapOf("User-Agent" to UA_OKHTTP) + headers,
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /** POST as application/x-www-form-urlencoded, exactly like the app. */
    private suspend fun apiPostForm(path: String, form: Map<String, String>): JsonObject? =
        try {
            val body = form.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            app.post(
                "${apiBase()}$API/$path",
                requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                headers = mapOf(
                    "User-Agent" to UA_OKHTTP,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
            ).text.toJsonObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    // ================================================================ mapping

    /** catalog item -> SearchResponse. "/home" uses type "serie" (no s!), the rest "series". */
    private fun JsonObject.toSearch(): SearchResponse? {
        val id = str("id") ?: return null
        val rawTitle = str("name")?.trim() ?: return null
        val poster = fixImg(str("poster"))
        val cats = categoryIds()
        val dub = str("dub")?.takeIf { it != "false" }
        val title = if (dub != null) "$rawTitle ($dub)" else rawTitle

        return when (str("type")?.lowercase()) {
            "movie" -> newMovieSearchResponse(
                title, "movie/$id",
                if (6 in cats) TvType.AnimeMovie else TvType.Movie,
            ) { this.posterUrl = poster }

            "serie", "series" -> newTvSeriesSearchResponse(
                title, "series/$id",
                if (5 in cats) TvType.Anime else TvType.TvSeries,
            ) { this.posterUrl = poster }

            else -> null
        }
    }

    /** episode card inside the home "episodes" section -> link to its series */
    private fun JsonObject.episodeToSearch(): SearchResponse? {
        val showId = str("tv_show_id") ?: return null
        val title = str("tv_show_name")?.trim() ?: return null
        return newTvSeriesSearchResponse(
            title, "series/$showId",
            if (5 in categoryIds()) TvType.Anime else TvType.TvSeries,
        ) { this.posterUrl = fixImg(str("poster")) }
    }

    // ================================================================ main page

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "b:1:0" to "أحدث الأفلام",
        "b:2:0" to "أحدث المسلسلات",
        "b:2:5" to "أنمي وكرتون (مسلسلات)",
        "b:1:6" to "أنمي وكرتون (أفلام)",
        "b:2:3" to "مسلسلات أجنبية",
        "b:1:4" to "أفلام أجنبية",
        "b:2:1" to "مسلسلات تركية",
        "b:1:2" to "أفلام تركية",
        "b:2:7" to "مسلسلات كورية",
        "b:1:8" to "أفلام كورية",
        "b:2:9" to "مسلسلات هندية",
        "b:1:10" to "أفلام هندية",
        "b:2:11" to "مسلسلات آسيوية",
        "b:1:12" to "أفلام آسيوية",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // the app home screen: every section in one request
        if (request.data == "home") {
            if (page > 1) return newHomePageResponse(emptyList<HomePageList>(), false)

            val res = apiGet("home")
                ?: return newHomePageResponse(emptyList<HomePageList>(), false)

            val lists = res.arr("sections")?.objects()?.mapNotNull { section ->
                val title = section.str("section_name")?.trim() ?: return@mapNotNull null
                val items = section.arr("section_items")?.objects()?.mapNotNull { item ->
                    if (item.str("type").equals("episode", ignoreCase = true)) {
                        item.episodeToSearch()
                    } else {
                        item.toSearch()
                    }
                }.orEmpty()
                if (items.isEmpty()) null else HomePageList(title, items)
            }.orEmpty()

            return newHomePageResponse(lists, false)
        }

        // any other entry: "b:type:category", paged through POST /all
        val parts = request.data.split(":")
        val form = mutableMapOf(
            "type" to parts.getOrElse(1) { "0" },
            "sort" to "1",
            "page" to page.toString(),
        )
        parts.getOrNull(2)?.takeIf { it != "0" }?.let { form["category"] = it }
        val res = apiPostForm("all", form)
            ?: return newHomePageResponse(request, emptyList<SearchResponse>(), false)

        val items = res.arr("results")?.objects()?.mapNotNull { it.toSearch() }.orEmpty()
        val totalPages = res.int("total_pages") ?: 1
        return newHomePageResponse(request, items, page < totalPages)
    }

    // ================================================================ search

    override suspend fun search(query: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        var page = 1
        while (page <= 5) {
            val res = apiPostForm(
                "search",
                mapOf(
                    "title" to query,
                    "type" to "0",
                    "sort" to "1",
                    "page" to page.toString(),
                ),
            ) ?: break
            val items = res.arr("results")?.objects()?.mapNotNull { it.toSearch() }.orEmpty()
            out.addAll(items)
            val totalPages = res.int("total_pages") ?: 1
            if (items.isEmpty() || page >= totalPages) break
            page++
        }
        return out.distinctBy { it.url }
    }

    // ================================================================ load

    override suspend fun load(url: String): LoadResponse? {
        val (kind, id) = parseTarget(url) ?: return null

        if (kind == "movie") {
            val res = apiGet("movie/$id", mapOf("firebase_id" to makeFirebaseId()))
                ?: return null
            if (!res.ok()) return null
            val m = res.obj("movie") ?: return null
            val title = m.str("name")?.trim() ?: return null
            val tvType = if (6 in m.categoryIds()) TvType.AnimeMovie else TvType.Movie

            return newMovieLoadResponse(title, url, tvType, "movie/$id") {
                this.posterUrl = fixImg(m.str("poster") ?: m.str("cover"))
                this.backgroundPosterUrl = fixImg(m.str("backdrop"))
                this.plot = m.str("overview")?.trim()
                this.tags = m.tagNames()
                this.year = m.yearValue()
                this.score = Score.from10(m.str("vote_average")?.toFloatOrNull())
                this.duration = m.str("runtime")?.toIntOrNull()
                addTrailer(m.str("trailer")?.takeIf { it.startsWith("http") })
            }
        }

        if (kind == "series") {
            val res = apiGet("series/$id", mapOf("firebase_id" to makeFirebaseId()))
                ?: return null
            if (!res.ok()) return null
            val s = res.obj("series") ?: return null
            val title = s.str("name")?.trim() ?: return null

            // one request holds every season + episode
            val episodes = apiGet("series/$id/episodes")
                ?.arr("seasons")?.objects()
                ?.mapIndexedNotNull { seasonIdx, season ->
                    val seasonNumber = season.str("season_number")?.toIntOrNull()
                        ?: season.int("season_number")
                        ?: (seasonIdx + 1)
                    season.arr("episodes")?.objects()?.mapIndexedNotNull { epIdx, ep ->
                        val epId = ep.str("id") ?: return@mapIndexedNotNull null
                        val epNumber = Regex("""\d+""")
                            .find(ep.str("title") ?: "")?.value?.toIntOrNull()
                            ?: (epIdx + 1)
                        newEpisode("ep/$epId") {
                            this.name = ep.str("title")?.trim()?.takeIf { it.isNotBlank() }
                                ?: "الحلقة ${epIdx + 1}"
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = fixImg(ep.str("cover") ?: ep.str("image"))
                        }
                    } ?: emptyList()
                }
                ?.flatten()
                ?: emptyList()

            val tvType = if (5 in s.categoryIds()) TvType.Anime else TvType.TvSeries
            return newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = fixImg(s.str("poster"))
                this.backgroundPosterUrl = fixImg(s.str("backdrop"))
                this.plot = s.str("overview")?.trim()
                this.tags = s.tagNames()
                this.year = s.yearValue()
                this.score = Score.from10(s.str("vote_average")?.toFloatOrNull())
                addTrailer(s.str("trailer")?.takeIf { it.startsWith("http") })
            }
        }

        return null
    }

    // ================================================================ loadLinks

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val (kind, id) = parseTarget(data) ?: return false
        val path = when (kind) {
            "movie" -> "movie/$id/servers"
            "ep" -> "episode/$id/servers"
            else -> return false
        }

        // both headers must be fresh — the server rejects replayed tokens
        val raw = apiGetRaw(
            path,
            mapOf(
                "firebase_id" to makeFirebaseId(),
                "cloudflare-id" to makeCloudflareId(),
            ),
        ) ?: return false

        val servers = decryptServers(raw)
            ?: raw.toJsonObject()?.takeIf { it.ok() }?.arr("servers")?.objects()
                ?.mapNotNull { it.str("link") }
            ?: return false

        // resolve all servers in parallel, one bad server must not kill the rest
        val results = servers.amap { link ->
            try {
                resolveServer(link, subtitleCallback, callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
        return results.any { it }
    }

    /** dispatch a single server link to the right resolver */
    private suspend fun resolveServer(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val host = hostOf(link) ?: return false
        val label = serverLabel(link)

        // 1) direct media files
        val clean = link.substringBefore("?").lowercase()
        when {
            clean.endsWith(".mp4") -> {
                callback(
                    newExtractorLink(source = name, name = label, url = link, type = ExtractorLinkType.VIDEO) {
                        this.headers = mapOf("User-Agent" to UA_CHROME)
                    },
                )
                return true
            }

            clean.endsWith(".m3u8") || clean.endsWith(".mpd") -> {
                callback(
                    newExtractorLink(
                        source = name, name = label, url = link,
                        type = if (clean.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH,
                    ) {
                        this.headers = mapOf("User-Agent" to UA_CHROME)
                    },
                )
                return true
            }
        }

        // 2) Google Photos (plain share link or the "aminegoogle" wrapper around one)
        gphotosUrl(link)?.let {
            return resolveGooglePhotos(it, callback)
        }

        // 3) the developer's Cloudflare Workers resolvers
        if (host.endsWith("developer-pro.workers.dev")) {
            return resolveWorkers(link, callback)
        }

        // 4) hosts the developer's extractor.php handles (vidtube = TB, fasel = FS, ...)
        if (EXTRACTOR_PHP_DOMAINS.any { host.contains(it) }) {
            if (resolveViaExtractorPhp(link, callback)) return true
        }

        // 5) JWPlayer embeds we scrape ourselves
        if (JW_EMBED_DOMAINS.any { host.contains(it) }) {
            return resolveJwEmbed(link, label, callback)
        }

        // 6) anything else -> hope CloudStream knows the host (upstream, streamwish, ...)
        return try {
            loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** hrrejhp.com/aminegoogle/googlefinal.php?url=<photos link> -> the inner photos link */
    private fun gphotosUrl(link: String): String? {
        val host = hostOf(link) ?: return null
        return when {
            host.contains("photos.google.com") -> link
            link.contains("googlefinal.php") -> {
                Regex("""[?&]url=(https?[^&]+)""").find(link)?.groupValues?.get(1)
                    ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                    ?.takeIf { hostOf(it)?.contains("photos.google.com") == true }
            }
            else -> null
        }
    }

    /**
     * Google Photos share link -> [data-url] on the share page ->
     * lh3.googleusercontent MPD (DASH), played with photos.google.com
     * Origin/Referer — exactly the flow the app uses.
     */
    private suspend fun resolveGooglePhotos(
        shareUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val page = try {
            app.get(
                shareUrl,
                headers = mapOf(
                    "User-Agent" to UA_MOBILE,
                    "Accept-Language" to "ar,en;q=0.9",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
            ).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }

        var found = false

        // direct download link embedded in the page (the app calls it regex_direct_download)
        Regex("""(https?://video-downloads[^"\\\s]+)""").find(page)?.groupValues?.get(1)?.let { dl ->
            callback(
                newExtractorLink(
                    source = name, name = "سيرفر Google (تنزيل)", url = dl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "https://photos.google.com"
                    this.headers = mapOf(
                        "Origin" to "https://photos.google.com",
                        "User-Agent" to UA_CHROME,
                    )
                },
            )
            found = true
        }

        // DASH manifest used by the app ([data-url] + the mpd_version suffix)
        Regex("""data-url=\\?"([^"\\]+)""").find(page)?.groupValues?.get(1)?.let { dataUrl ->
            callback(
                newExtractorLink(
                    source = name, name = "سيرفر Google Photos", url = dataUrl + MPD_SUFFIX,
                    type = ExtractorLinkType.DASH,
                ) {
                    this.referer = "https://photos.google.com"
                    this.headers = mapOf(
                        "Origin" to "https://photos.google.com",
                        "User-Agent" to UA_CHROME,
                    )
                },
            )
            found = true
        }

        return found
    }

    /** *.developer-pro.workers.dev/get-links?id=... — returns links pointing at media */
    private suspend fun resolveWorkers(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val text = try {
            app.get(url, headers = mapOf("User-Agent" to UA_CHROME)).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }

        val urls = mutableSetOf<String>()
        text.toJsonObject()?.let { collectUrls(it, urls) }
        Regex("""https?://[^\s"'\\]+""").findAll(text).forEach { urls.add(it.value.trim()) }
        if (urls.isEmpty()) return false

        var found = false
        for (u in urls) {
            val host = hostOf(u) ?: continue
            val clean = u.substringBefore("?").lowercase()
            when {
                host.contains("photos.google.com") -> if (resolveGooglePhotos(u, callback)) found = true

                clean.endsWith(".mp4") -> {
                    callback(
                        newExtractorLink(source = name, name = "سيرفر Worker", url = u, type = ExtractorLinkType.VIDEO) {
                            this.headers = mapOf("User-Agent" to UA_CHROME)
                        },
                    )
                    found = true
                }

                clean.endsWith(".m3u8") -> {
                    callback(
                        newExtractorLink(source = name, name = "سيرفر Worker", url = u, type = ExtractorLinkType.M3U8) {
                            this.headers = mapOf("User-Agent" to UA_CHROME)
                        },
                    )
                    found = true
                }
            }
        }
        return found
    }

    private fun collectUrls(element: JsonElement, out: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.values.forEach { collectUrls(it, out) }
            is JsonArray -> element.forEach { collectUrls(it, out) }
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.startsWith("http") }
                ?.let { out.add(it) }
        }
    }

    /** the developer's own extractor.php: returns labeled links + referer/origin */
    private suspend fun resolveViaExtractorPhp(
        link: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val res = try {
            app.get(
                EXTRACTOR_URL + URLEncoder.encode(link, "UTF-8"),
                headers = mapOf("User-Agent" to UA_OKHTTP),
            ).text.toJsonObject()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false

        val servers = res.arr("servers")?.objects() ?: return false

        var found = false
        for (srv in servers) {
            val url = srv.str("url")?.takeIf { it.startsWith("http") } ?: continue
            val label = srv.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: "سيرفر"
            val referer = srv.str("referer")
            val origin = srv.str("origin")
            val clean = url.substringBefore("?").lowercase()

            callback(
                newExtractorLink(
                    source = name, name = label, url = url,
                    type = if (clean.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer ?: ""
                    this.quality = Regex("""(\d{3,4})p""").find(label)?.value
                        ?.let { getQualityFromName(it) }
                        ?: Qualities.Unknown.value
                    this.headers = mutableMapOf("User-Agent" to UA_CHROME).apply {
                        origin?.let { put("Origin", it) }
                    }
                },
            )
            found = true
        }
        return found
    }

    private val SOURCES_REGEX = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
    private val FILE_REGEX = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""")

    /**
     * JWPlayer-style embed page. Handles both the plain
     * `sources:[{file:"https://...m3u8"}]` form and the p.a.c.k.e.d eval()
     * variant (vidspeed & friends), then expands the master playlist.
     * Segments require Referer = the embed page itself (verified in traffic).
     */
    private suspend fun resolveJwEmbed(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val headers = mutableMapOf(
            "User-Agent" to UA_CHROME,
            "Referer" to embedUrl,
        )
        hostOf(embedUrl)?.let { headers["Origin"] = "https://$it" }
        val page = try {
            app.get(embedUrl, headers = headers).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }

        var stream = SOURCES_REGEX.find(page)?.groupValues?.get(1)
        if (stream == null) {
            stream = unpackPackedJs(page)?.let { FILE_REGEX.find(it)?.groupValues?.get(1) }
                ?: FILE_REGEX.find(page)?.groupValues?.get(1)
        }
        val url = stream
            ?.replace("""\/""", "/")
            ?.takeIf { it.startsWith("http") }
            ?: return false

        val clean = url.substringBefore("?").lowercase()
        return when {
            clean.endsWith(".m3u8") -> addM3u8Expanded(url, label, embedUrl, callback)

            clean.endsWith(".mp4") -> {
                callback(
                    newExtractorLink(source = name, name = label, url = url, type = ExtractorLinkType.VIDEO) {
                        this.referer = embedUrl
                        this.headers = mapOf("User-Agent" to UA_CHROME)
                    },
                )
                true
            }

            else -> false
        }
    }

    /** expand a master m3u8 into labeled per-quality links (falls back to the raw master). */
    private suspend fun addM3u8Expanded(
        masterUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        try {
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = masterUrl,
                referer = referer,
                headers = mapOf("User-Agent" to UA_CHROME),
            )
            if (links.isEmpty()) throw IOException("empty playlist")
            links.forEach { link ->
                callback(
                    newExtractorLink(
                        source = link.source,
                        name = "$label ${link.name}".trim(),
                        url = link.url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = link.referer
                        this.quality = link.quality
                        this.headers = link.headers
                    },
                )
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callback(
                newExtractorLink(source = name, name = label, url = masterUrl, type = ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.headers = mapOf("User-Agent" to UA_CHROME)
                },
            )
            return true
        }
    }

    /** p.a.c.k.e.d eval(function(p,a,c,k,e,d){...}) -> unpacked JS (same trick krmzy uses) */
    private fun unpackPackedJs(page: String): String? {
        val evalMatch = Regex(
            """eval\s*\(\s*function\s*\(.*?\)\s*\{.*?\}\s*\((.*)\)\s*\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(page) ?: return null
        val params = evalMatch.groupValues.getOrNull(1) ?: return null
        val paramMatch = Regex(
            """['"](.*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"](.*?)['"]\.split\s*\(['"]\|['"]\)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(params) ?: return null
        val (packed, baseStr, countStr, dictStr) = paramMatch.destructured
        return deobfuscate(packed, baseStr.toInt(), countStr.toInt(), dictStr.split('|'))
    }

    private fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
        fun toBase(num: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % radix])
                n /= radix
            }
            return sb.reverse().toString()
        }

        val replaceMap = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val keyword = k.getOrNull(i)
            if (!keyword.isNullOrEmpty()) replaceMap[toBase(i, a)] = keyword
        }
        return Regex("""\b\w+\b""").replace(p) { m -> replaceMap[m.value] ?: m.value }
    }
}

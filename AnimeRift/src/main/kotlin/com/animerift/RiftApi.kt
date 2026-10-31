package com.animerift

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Client of the official AnimeRift gateway — the very same API the Android
 * application talks to (`gateway.anime-rift.com/api/v4`).
 *
 * The gateway only answers requests that look like they come from the
 * official Flutter client, so the following are required:
 *
 *  - a Dart user agent (`Dart/3.10 (dart:io)`),
 *  - the device headers: `x-os`, `x-device-language`, `x-installation-source`,
 *    `x-device-release-version`, `x-device-timezone`,
 *  - a registered device: `x-firebase-id` + `x-device-id`.
 *
 * The device id is generated once per installation and registered through
 * `/auth/register/device` exactly the way the app does it on first launch.
 * Without a registered id the gateway answers `DEVICE_SUSPENDED`.
 *
 * =========================== RATE LIMIT POLICY ===========================
 * The gateway limits **every endpoint** and answers `429 Too Many Requests`
 * ("You have exceeded the rate limit for this endpoint") when the limit is
 * crossed. Repeating that mistake turns into `403 ACCESS_DENIED`
 * ("Access Denied for suspicious behaviour") which is a ban on the **IP
 * address** — the only way out of it is a new IP (restarting the router).
 *
 * This client therefore never bursts:
 *
 *  1. a hard minimum gap between two gateway requests (1.2 s by default),
 *  2. an adaptive gap that grows after every 429 and relaxes again after a
 *     long streak of successful calls,
 *  3. a full stop (cooldown) when the gateway answers 403 — no request is
 *     sent while the cooldown is running, which keeps a temporary block from
 *     turning into a long one,
 *  4. responses are cached, so re-opening the same page/episode costs
 *     nothing,
 *  5. the caller limits how many sources of an episode are resolved.
 */
class RiftApi(context: Context) {

    companion object {
        const val TAG = "AnimeRift"
        const val APP_VERSION = "3.13.13"
        const val CLIENT_UA = "Dart/3.10 (dart:io)"
        const val DEVICE_OS_ID = "UQ1A.240205.004"
        const val STREAMTAPE_API = "https://api.streamtape.com"

        /** أقصى عدد محاولات لكل طلب (مع تراجع عشوائي). */
        private const val MAX_ATTEMPTS = 3
        private const val CACHE_TTL_HOME = 30 * 60_000L
        private const val CACHE_TTL_DETAILS = 60 * 60_000L
        private const val CACHE_TTL_EPISODES = 60 * 60_000L
        private const val CACHE_TTL_SOURCES = 5 * 60_000L
        private const val CACHE_TTL_SEARCH = 15 * 60_000L

        val GATEWAYS = listOf(
            "https://gateway.anime-rift.com/api/v4",
            "https://gateway.embrassme.top/api/v4",
        )
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("animerift_device", Context.MODE_PRIVATE)

    /** Firebase installation id, used as the device identity of the gateway. */
    private val firebaseId: String
        get() = prefs.getString("firebase_id", null) ?: newToken(22).also {
            prefs.edit().putString("firebase_id", it).apply()
        }

    /** Device id: `<firebase id>:APA91b<token>` like the app sends it. */
    private val deviceId: String
        get() = prefs.getString("device_id", null)
            ?: (firebaseId + ":APA91b" + newToken(40)).also {
                prefs.edit().putString("device_id", it).apply()
            }

    private val gatewayIndex: Int
        get() = prefs.getInt("gateway", 0).coerceIn(0, GATEWAYS.lastIndex)

    private fun setGateway(index: Int) {
        if (gatewayIndex != index) prefs.edit().putInt("gateway", index).apply()
    }

    private fun newToken(length: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    // =====================================================================
    // Rate limiting
    // =====================================================================

    /** مُطبّق سياسة حد البوابة (تباعد إلزامي + تهدئة عند 429/403). */
    private val limiter = RateLimiter(
        store = object : RateLimiter.Store {
            override fun getLong(key: String, default: Long): Long =
                prefs.getLong(key, default)

            override fun putLong(key: String, value: Long) {
                prefs.edit().putLong(key, value).apply()
            }
        }
    )

    private fun onSuccess() = limiter.onSuccess()

    private fun onRateLimited() = limiter.onRateLimited()

    private fun onAccessDenied() {
        limiter.onAccessDenied()
        android.util.Log.i(
            TAG,
            "ACCESS_DENIED — حظر الـ IP: إيقاف كل الطلبات " +
                "(${limiter.banRemainingMs / 60000} دقيقة)"
        )
    }

    private fun isBanned(): Boolean = limiter.isBanned

    private fun isThrottled(): Boolean = limiter.isThrottled

    /**
     * Waits for a pending cooldown to expire (bounded, so the UI never hangs
     * forever) and reports whether the gateway may be used again.
     */
    private suspend fun awaitCooldown(): Boolean = limiter.awaitCooldown(maxWaitMs = 20_000L)

    // =====================================================================
    // Cache
    // =====================================================================

    private class TimedCache(private val ttl: Long) {
        private val map = LinkedHashMap<String, Pair<Long, Any?>>()

        @Suppress("UNCHECKED_CAST")
        fun get(key: String): Any? {
            val now = System.currentTimeMillis()
            synchronized(map) {
                val hit = map[key] ?: return null
                if (now - hit.first > ttl) {
                    map.remove(key)
                    return null
                }
                return hit.second
            }
        }

        fun put(key: String, value: Any?) {
            val now = System.currentTimeMillis()
            synchronized(map) {
                map[key] = now to value
                if (map.size > 200) {
                    val oldest = map.entries.minByOrNull { it.value.first }?.key
                    if (oldest != null) map.remove(oldest)
                }
            }
        }

        fun clear() = synchronized(map) { map.clear() }
    }

    private val homeCache = TimedCache(CACHE_TTL_HOME)
    private val detailsCache = TimedCache(CACHE_TTL_DETAILS)
    private val episodesCache = TimedCache(CACHE_TTL_EPISODES)
    private val sourcesCache = TimedCache(CACHE_TTL_SOURCES)
    private val searchCache = TimedCache(CACHE_TTL_SEARCH)

    /** Called when the device got banned: every cached answer is dropped. */
    private fun dropCaches() {
        homeCache.clear()
        detailsCache.clear()
        episodesCache.clear()
        sourcesCache.clear()
        searchCache.clear()
    }

    // =====================================================================
    // Device
    // =====================================================================

    private fun deviceInfo(): Map<String, Any?> = mapOf(
        "model" to "M2006C3MG",
        "brand" to "Redmi",
        "manufacturer" to "Xiaomi",
        "device" to "angelica",
        "hardware" to "mt6765",
        "product" to "angelica_global",
        "androidVersion" to "14",
        "sdkInt" to 34,
        "isPhysicalDevice" to true,
        "supportedAbis" to listOf("arm64-v8a", "armeabi-v7a"),
        "tags" to "release-keys",
        "type" to "user",
        "buildId" to DEVICE_OS_ID,
    )

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent" to CLIENT_UA,
        "Accept" to "application/json",
        "Content-Type" to "application/json; charset=UTF-8",
        "x-os" to "android",
        "x-platform" to "Mobile",
        "x-device-language" to "ar",
        "x-installation-source" to "IS_INSTALLED_FROM_OTHER_SOURCE",
        "x-device-release-version" to APP_VERSION,
        "x-device-os-id" to DEVICE_OS_ID,
        "x-device-timezone" to isoTimestamp(),
        "x-firebase-app-check" to "null",
        "x-firebase-id" to firebaseId,
        "x-device-id" to deviceId,
    )

    private fun isoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS", Locale.US).format(Date())

    /**
     * Registers the generated device on every gateway, so the same id keeps
     * working no matter which host answers the next request.
     */
    private suspend fun registerDevice(): Boolean {
        val body = mapOf(
            "deviceId" to deviceId,
            "current_app_version" to APP_VERSION,
            "device_os" to "android",
            "device_environment" to "production",
            "device_info" to JSONObject(deviceInfo()).toString(),
        )
        var ok = false
        for ((index, base) in GATEWAYS.withIndex()) {
            if (isBanned()) return false
            try {
                limiter.pace()
                val res = withContext(Dispatchers.IO) {
                    app.post("$base/auth/register/device", headers = headers(), json = body)
                }
                if (res.code in 200..299) {
                    ok = true
                    setGateway(index)
                } else if (res.code == 403 && res.text.contains("ACCESS_DENIED")) {
                    onAccessDenied()
                    return false
                }
            } catch (_: Exception) {
                // try the next gateway
            }
        }
        if (ok) prefs.edit().putBoolean("registered", true).apply()
        return ok
    }

    private suspend fun ensureRegistered() {
        if (prefs.getBoolean("registered", false)) return
        // لا نلمس البوابة أثناء الحظر: أي طلب إضافي قد يطيله.
        if (isBanned()) return
        registerDevice()
    }

    // =====================================================================
    // Request core
    // =====================================================================

    /**
     * Performs a request against the gateway, transparently falling back to the
     * second host when the first one fails, and never bursting: every call is
     * spaced by the adaptive limiter and every rate limit answer is retried
     * with a backoff.
     */
    private suspend fun request(
        method: String,
        path: String,
        body: Map<String, Any?>? = null
    ): JSONObject? {
        ensureRegistered()
        if (isBanned() && !limiter.shouldProbe()) {
            android.util.Log.i(TAG, "الطلب $path متوقف بسبب حظر الـ IP")
            return null
        }
        if (limiter.overHardCap && !limiter.shouldProbe()) {
            android.util.Log.i(
                TAG,
                "الطلب $path متوقف: تجاوز السقف الساعي " +
                    "(${limiter.hourlyCount} طلب/ساعة)"
            )
            return null
        }
        if (!awaitCooldown()) return null

        val order = listOf(gatewayIndex) + GATEWAYS.indices.filter { it != gatewayIndex }
        for (index in order) {
            val base = GATEWAYS[index]
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                limiter.pace()
                val res = try {
                    withContext(Dispatchers.IO) {
                        if (method.equals("GET", true)) {
                            app.get("$base$path", headers = headers())
                        } else {
                            app.post("$base$path", headers = headers(), json = body ?: emptyMap<String, Any?>())
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.i(TAG, "$method $path failed: ${e.message}")
                    attempt++
                    delay(limiter.backoffMs(attempt))
                    continue
                }

                when {
                    res.code in 200..299 -> {
                        setGateway(index)
                        onSuccess()
                        return runCatching { JSONObject(res.text) }.getOrNull()
                    }

                    res.code == 429 -> {
                        android.util.Log.i(TAG, "$method $path -> 429 (سيُعاد بعد ${retryAfterMs(res)})")
                        onRateLimited()
                        delay(retryAfterMs(res))
                        attempt++
                    }

                    res.code == 403 && res.text.contains("ACCESS_DENIED") -> {
                        onAccessDenied()
                        dropCaches()
                        return null
                    }

                    res.code == 403 -> {
                        // device suspended / invalid device — re-register once
                        android.util.Log.i(TAG, "$method $path -> 403 (إعادة تسجيل الجهاز)")
                        prefs.edit().putBoolean("registered", false).apply()
                        if (!registerDevice()) return null
                        attempt++
                    }

                    res.code >= 500 -> {
                        android.util.Log.i(TAG, "$method $path -> ${res.code}")
                        attempt++
                        delay(limiter.backoffMs(attempt))
                    }

                    else -> {
                        android.util.Log.i(TAG, "$method $path -> ${res.code} ${res.text.take(120)}")
                        return null
                    }
                }
            }
        }
        return null
    }

    /** `Retry-After` header of the gateway, or a value parsed from the body. */
    private fun retryAfterMs(res: com.lagradost.nicehttp.NiceResponse): Long {
        val header = res.headers["Retry-After"]?.trim()
        if (!header.isNullOrBlank()) {
            val seconds = header.toLongOrNull()
            if (seconds != null) return (seconds * 1000L).coerceIn(1_000L, 60_000L)
        }
        val match = Regex("(\\d{1,3})\\s*(second|minute)").find(res.text)
        if (match != null) {
            val value = match.groupValues[1].toLongOrNull() ?: 0L
            val unit = if (match.groupValues[2].startsWith("min")) 60_000L else 1_000L
            return (value * unit).coerceIn(1_000L, 60_000L)
        }
        return 5_000L
    }

    // =====================================================================
    // Endpoints used by the extension
    // =====================================================================

    suspend fun get(path: String): JSONObject? = request("GET", path)

    suspend fun post(path: String, body: Map<String, Any?>): JSONObject? =
        request("POST", path, body)

    suspend fun search(query: String, page: Int = 0): JSONObject? {
        val key = "$query|$page"
        (searchCache.get(key) as? JSONObject)?.let { return it }
        val result = post(
            "/library/search?page=$page&sort_by=release_year&sort_direction=1&text_direction=jp",
            mapOf("query" to query)
        )
        if (result != null) searchCache.put(key, result)
        return result
    }

    suspend fun homeContent(): JSONObject? {
        (homeCache.get("home") as? JSONObject)?.let { return it }
        val result = get("/library/home_content?with_genres=true")
        if (result != null) homeCache.put("home", result)
        return result
    }

    suspend fun browse(page: Int): JSONObject? {
        val key = "browse|$page"
        (detailsCache.get(key) as? JSONObject)?.let { return it }
        val result = post(
            "/library/all?page=$page&sort_by=name&sort_direction=1&filter_by=library&text_direction=jp",
            mapOf<String, Any?>("country_origin" to null)
        )
        if (result != null) detailsCache.put(key, result)
        return result
    }

    suspend fun details(animeId: String): JSONObject? {
        (detailsCache.get(animeId) as? JSONObject)?.let { return it }
        val result = get("/library/details/$animeId")
        if (result != null) detailsCache.put(animeId, result)
        return result
    }

    suspend fun episodes(animeId: String): JSONObject? {
        (episodesCache.get(animeId) as? JSONObject)?.let { return it }
        val result = get("/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true")
        if (result != null) episodesCache.put(animeId, result)
        return result
    }

    suspend fun sources(animeId: String, episodeId: String, episodeNumber: Int): JSONObject? {
        val key = "$animeId|$episodeId|$episodeNumber"
        (sourcesCache.get(key) as? JSONObject)?.let { return it }
        val result = post(
            "/library/episode/sources",
            mapOf(
                "animeId" to animeId,
                "episodeId" to episodeId,
                "episode_number" to episodeNumber,
            )
        )
        if (result != null) sourcesCache.put(key, result)
        return result
    }

    suspend fun canPlay(
        episodeId: String,
        hostId: String,
        isDownload: Boolean = false
    ): JSONObject? =
        post(
            "/library/episode/source/can_play",
            mapOf(
                "episodeId" to episodeId,
                "hostId" to hostId,
                "is_download" to isDownload,
                "event_name" to if (isDownload) "download_episode" else "play_episode_unlocked",
            )
        )

    /**
     * يطلب الرابط المباشر. المزوّدات التي لا تملك جودة محددة (السيرفرات
     * "realtime") ترفض وجود حقل `quality` من الأساس، لذلك لا يُرسل إلا إذا
     * كانت الجودة معروفة.
     */
    suspend fun directLink(
        hostId: String,
        quality: String?,
        sessionId: String?
    ): JSONObject? {
        val body = HashMap<String, Any?>()
        body["id"] = hostId
        body["with_internal_player"] = "1"
        body["sessionId"] = sessionId ?: ""
        if (!quality.isNullOrBlank()) body["quality"] = quality
        return post("/library/episode/source/direct_link", body)
    }

    /**
     * Streamtape hands out a ticket instead of the final url. The ticket is
     * only valid after `wait_time` seconds and has to be exchanged against the
     * real cdn url through the public streamtape api.
     */
    suspend fun resolveTicket(fileId: String, ticket: String, waitTime: Int): Pair<String, String>? {
        if (waitTime > 0) kotlinx.coroutines.delay(waitTime * 1000L)
        return try {
            val res = withContext(Dispatchers.IO) {
                app.get(
                    "$STREAMTAPE_API/file/dl?file=$fileId&ticket=$ticket",
                    headers = mapOf("User-Agent" to CLIENT_UA, "Accept" to "application/json")
                )
            }
            if (res.code !in 200..299) return null
            val result = JSONObject(res.text).optJSONObject("result") ?: return null
            val url = result.optString("url")
            if (url.isBlank()) null else url to result.optString("name")
        } catch (_: Exception) {
            null
        }
    }
}

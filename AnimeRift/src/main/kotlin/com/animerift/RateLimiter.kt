package com.animerift

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * سياسة حد الطلبات (rate limit) لبوابة AnimeRift.
 *
 * ## لماذا هذا الملف موجود؟
 * البوابة تضع حداً على **كل** endpoint وتردّ عند تجاوزه بـ
 * `429 Too Many Requests` ("You have exceeded the rate limit for this endpoint").
 * وتكرار التجاوز يرقّي الردّ إلى
 * `403 ACCESS_DENIED` ("Access Denied for suspicious behaviour") وهو **حظر على
 * مستوى عنوان الـ IP** لا يُرفع إلا بتغيير الـ IP (إعادة تشغيل الراوتر).
 *
 * ## القواعد المطبَّقة
 *  1. **تباعد إلزامي** بين أي طلبين: `minIntervalMs` على الأقل، ويزداد
 *     تلقائياً بعد كل 429 حتى يتعافى الحساب.
 *  2. **لا انفجار**: الطلبات تمرّ عبر بوابة واحدة (Mutex) فلا تتزامن.
 *  3. **توقّف كامل** عند 403: لا يُرسل أي طلب أثناء فترة التهدئة، لأن
 *     المحاولة أثناء الحظر تطيله.
 *  4. **تراجع تدريجي**: بعد 429 ننتظر `Retry-After` (أو قيمة افتراضية)
 *     ونعيد المحاولة بحد أقصى [maxAttempts] مرات.
 *
 * كل الأرقام قابلة للضبط عبر [Store] حتى تُحفظ بين تشغيلات التطبيق.
 */
class RateLimiter(
    private val store: Store,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS,
    private val banCooldownMs: Long = DEFAULT_BAN_COOLDOWN_MS,
    private val maxBanCooldownMs: Long = DEFAULT_MAX_BAN_COOLDOWN_MS,
    private val throttleCooldownMs: Long = DEFAULT_THROTTLE_COOLDOWN_MS,
    private val successBeforeRelax: Int = DEFAULT_SUCCESS_BEFORE_RELAX,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val probeIntervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
    private val jitter: () -> Long = { Random.nextLong(0, 400L) },
) {

    /** تخزين القيم بين الجلسات (SharedPreferences في التطبيق). */
    interface Store {
        fun getLong(key: String, default: Long): Long
        fun putLong(key: String, value: Long)
    }

    companion object {
        /** أقل فاصل بين طلبين: 50 طلباً/دقيقة (أقل من نصف السقف المقاس). */
        const val DEFAULT_MIN_INTERVAL_MS = 1_200L

        /** أقصى فاصل يصل إليه المُخمِّد التكيّفي. */
        const val DEFAULT_MAX_INTERVAL_MS = 15_000L

        /** مدة التهدئة بعد 403 (15 دقيقة). */
        const val DEFAULT_BAN_COOLDOWN_MS = 15 * 60_000L

        /** أقصى مدة تهدئة بعد 403 (ساعتان). */
        const val DEFAULT_MAX_BAN_COOLDOWN_MS = 2 * 60 * 60_000L

        /** تهدئة قصيرة بعد 429 حتى يمتلئ دلو Endpoint. */
        const val DEFAULT_THROTTLE_COOLDOWN_MS = 15_000L

        /** عدد النجاحات المتتالية قبل تخفيف الفاصل. */
        const val DEFAULT_SUCCESS_BEFORE_RELAX = 60

        /**
         * أثناء الحظر نجرّب طلباً واحداً كل [DEFAULT_PROBE_INTERVAL_MS]
         * حتى يتعافى التطبيق تلقائياً (رفع الحظر أو تغيّر عنوان الـ IP)
         * دون أن يحتاج المستخدم لأي تدخل.
         */
        const val DEFAULT_PROBE_INTERVAL_MS = 5 * 60_000L

        const val KEY_INTERVAL = "interval"
        const val KEY_BAN_UNTIL = "ban_until"
        const val KEY_BAN_COOLDOWN = "ban_cooldown"
        const val KEY_THROTTLE_UNTIL = "throttle_until"
        const val KEY_LAST_PROBE = "last_probe"
        const val KEY_HOUR_COUNT = "hour_count"
        const val KEY_HOUR_START = "hour_start"

        /**
         * عدد الطلبات في الساعة التي نبدأ بعدها بتوسيع الفاصل (3x) — حد
         * احترازي لتخفيف الحمل قبل الوصول للسقف.
         */
        const val SOFT_REQUESTS_PER_HOUR = 250

        /**
         * السقف الصلب: بعده تتوقف الطلبات حتى تنتهي الساعة. التطبيق الرسمي
         * يستهلك ~6 طلبات للحلقة، ونحن ~24، فالمستخدم النهم قد يتجاوز السقف.
         * السقف الصلب أضمن من المخاطرة بحظر يدوم ساعة على الأقل.
         */
        const val MAX_REQUESTS_PER_HOUR = 350
    }

    private val lock = Mutex()
    private var lastRequestAt = 0L
    private var successStreak = 0

    /** الفاصل الحالي بين الطلبات. */
    val intervalMs: Long
        get() = store.getLong(KEY_INTERVAL, minIntervalMs).coerceIn(minIntervalMs, maxIntervalMs)

    /** عدد الطلبات في الساعة الحالية. */
    val hourlyCount: Int
        get() {
            val start = store.getLong(KEY_HOUR_START, 0L)
            val current = now()
            if (current - start > 3_600_000L) return 0
            return store.getLong(KEY_HOUR_COUNT, 0L).toInt()
        }

    /**
     * الفاصل الفعّال: ثلاثي عندما تُتجاوز الميزانية الساعية (شبكة أمان
     * إضافية ضد الحظر الطويل).
     */
    val effectiveIntervalMs: Long
        get() {
            val base = intervalMs
            val over = hourlyCount >= SOFT_REQUESTS_PER_HOUR
            return if (over) (base * 3).coerceAtMost(maxIntervalMs) else base
        }

    /** هل تجاوزنا السقف الصلب للساعة؟ */
    val overHardCap: Boolean
        get() = hourlyCount >= MAX_REQUESTS_PER_HOUR

    /** هل ال�� IPcurrently محظور؟ */
    val isBanned: Boolean
        get() = now() < store.getLong(KEY_BAN_UNTIL, 0L)

    /** هل نحن في تهدئة قصيرة بعد 429؟ */
    val isThrottled: Boolean
        get() = now() < store.getLong(KEY_THROTTLE_UNTIL, 0L)

    /** المتبقي من الحظر بالمللي ثانية. */
    val banRemainingMs: Long
        get() = (store.getLong(KEY_BAN_UNTIL, 0L) - now()).coerceAtLeast(0L)

    /**
     * ينتظر حتى يحين موعد الطلب التالي. يضمن ألا يقلّ الفاصل بين أي
     * طلبين عن [intervalMs] مهما كان عدد الطلبات المتوازية.
     */
    suspend fun pace() {
        lock.withLock {
            val current = now()
            val wait = lastRequestAt + effectiveIntervalMs - current
            if (wait > 0) sleep(wait)
            lastRequestAt = now()
        }
    }

    /** نجح الطلب: نُخَفِّض الفاصل تدريجياً بعد سلسلة نجاحات طويلة. */
    fun onSuccess() {
        // عدّاد الساعة
        val current = now()
        val start = store.getLong(KEY_HOUR_START, 0L)
        if (current - start > 3_600_000L) {
            store.putLong(KEY_HOUR_START, current)
            store.putLong(KEY_HOUR_COUNT, 1L)
        } else {
            store.putLong(KEY_HOUR_COUNT, store.getLong(KEY_HOUR_COUNT, 0L) + 1L)
        }

        // نجاح واحد يكفي لإلغاء الحظر: غالباً سُمح بحظر الـ IP.
        if (isBanned) {
            store.putLong(KEY_BAN_UNTIL, 0L)
            store.putLong(KEY_THROTTLE_UNTIL, 0L)
        }
        successStreak++
        if (successStreak >= successBeforeRelax) {
            successStreak = 0
            val current = intervalMs
            if (current > minIntervalMs) {
                // نصل إلى الحد الأدنى بخطوات كبيرة حتى لا نبقى محافظين بلا داع.
                store.putLong(KEY_INTERVAL, (current / 2).coerceAtLeast(minIntervalMs))
            }
        }
    }

    /** الردّ 429: نضاعف الفاصل ونبدأ تهدئة قصيرة. */
    fun onRateLimited() {
        successStreak = 0
        val current = intervalMs
        store.putLong(KEY_INTERVAL, (current * 2).coerceAtMost(maxIntervalMs))
        val until = now() + throttleCooldownMs
        if (until > store.getLong(KEY_THROTTLE_UNTIL, 0L)) {
            store.putLong(KEY_THROTTLE_UNTIL, until)
        }
    }

    /** الردّ 403: حظر الـ IP — نوقف كل شيء لفترة طويلة. */
    fun onAccessDenied() {
        successStreak = 0
        // أول حظر: banCooldownMs، وكل حظر لاحق يضاعف المدة حتى السقف.
        val current = store.getLong(KEY_BAN_COOLDOWN, 0L)
        val cooldown = if (current <= 0L) banCooldownMs else (current * 2).coerceAtMost(maxBanCooldownMs)
        store.putLong(KEY_BAN_COOLDOWN, cooldown)
        store.putLong(KEY_BAN_UNTIL, now() + cooldown)
    }

    /**
     * أثناء الحظر أو تجاوز السقف الصلب: هل حان وقت محاولة واحدة (probe)؟
     * هذه المحاولة الوحيدة التي تُرسل في تلك الحالة، وتُعيد ضبط المؤقّت
     * في كل مرة — وبذلك يتعافى التطبيق تلقائياً.
     */
    fun shouldProbe(): Boolean {
        val last = store.getLong(KEY_LAST_PROBE, 0L)
        if (now() - last < probeIntervalMs) return false
        store.putLong(KEY_LAST_PROBE, now())
        return true
    }

    /**
     * ينتظر انتهاء التهدئة (بحد أقصى [maxWaitMs] حتى لا تتجمد الواجهة)
     * ويخبر ما إذا أم إرسال الطلب بعد ذلك.
     */
    suspend fun awaitCooldown(maxWaitMs: Long = 90_000L): Boolean {
        var waited = 0L
        while (isBanned || isThrottled) {
            if (waited >= maxWaitMs) return false
            sleep(1_000L)
            waited += 1_000L
        }
        return true
    }

    /** تراجع عشوائي بسيط بين المحاولات (2s, 4s, 8s ...). */
    fun backoffMs(attempt: Int): Long =
        (1_000L * (1L shl (attempt - 1).coerceAtLeast(0))) + jitter()

    /** إلغاء الحظر يدوياً (عند تغيّر الـ IP مثلاً). */
    fun clearBan() {
        store.putLong(KEY_BAN_UNTIL, 0L)
        store.putLong(KEY_THROTTLE_UNTIL, 0L)
        store.putLong(KEY_INTERVAL, minIntervalMs)
        successStreak = 0
    }
}

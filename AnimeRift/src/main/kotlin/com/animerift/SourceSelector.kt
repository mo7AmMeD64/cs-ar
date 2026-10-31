package com.animerift

import org.json.JSONArray
import org.json.JSONObject

/**
 * اختيار المصادر التي ستُفتح لكل حلقة.
 *
 * ## لماذا هذه الطبقة منفصلة؟
 * البوابة تعيد ~23 مصدراً لحلقة واحدة، وفتح كل مصدر يحتاج طلبين لكل جودة
 * (`can_play` + `direct_link`، والجلسة تُستهلك مرة واحدة) أي ~46 طلباً دفعة
 * واحدة — وهو ما يتجاوز سقف البوابة (429) ويؤدي في النهاية إلى حظر الـ IP
 * (403 ACCESS_DENIED) الذي يدوم ساعة على الأقل.
 *
 * لذلك نختار المصادر بـ**ميزانية طلبات** محدودة، مع تفضيل المصادر التي
 * تعطي أكثر جودات بأقل عدد طلبات، ومع توزيع دوري يضمن تغطية كل الخوادم
 * (مترجم + مدبلج) قبل تكرار أي خادم.
 *
 * كل المنطق هنا خالص (بلا Android) ليُختبر مباشرة.
 */
object SourceSelector {

    /** أقصى عدد طلبات بوابة مسموح به لكل حلقة. */
    const val REQUEST_BUDGET = 24

    /**
     * حصة محجوزة للمصادر غير العربية (الإيطالية/الإنجليزية) حتى لا يبتلع
     *-budget المصادر العربية كله.
     */
    const val FOREIGN_RESERVE = 4

    /** أقصى عدد جودات تُفتح من المصدر الواحد. */
    const val MAX_QUALITIES_PER_SOURCE = 2

    /** أقصى عدد مصادر غير عربية تُفتح. */
    const val MAX_FOREIGN_SOURCES = 2

    /** مصدر مختار مع الجودات التي ستُفتح منه. */
    data class Choice(val item: JSONObject, val qualities: List<String>) {
        val server: String get() = item.optString("server_name")
        val track: String get() = item.optString("sub_title")
    }

    /**
     * تكلفة فتح جودة واحدة: طلب `can_play` + طلب `direct_link`.
     * (جلسة `can_play` تُستهلك مرة واحدة، فكل جودة تحتاج جلستها.)
     */
    fun cost(qualities: List<String>): Int = 2 * qualities.size.coerceAtLeast(1)

    private fun isArabic(item: JSONObject): Boolean =
        item.optString("sub_title").startsWith("ar_")

    /** ترتيب الجودات تنازلياً حسب الدقة. */
    private fun qualityRank(quality: String): Int =
        quality.filter { it.isDigit() }.toIntOrNull() ?: 0

    /**
     * الجودات التي ستُفتح من مصدر (الأعلى دقة أولاً).
     *
     * المصادر من نوع "realtime" لا تُحدَّد لها جودة إطلاقاً، وتُرجع مصفوفة
     * فارغة — نتعامل معها كجودة واحدة implicita تُرسل بدون حقل الجودة.
     */
    fun qualitiesOf(item: JSONObject, max: Int = MAX_QUALITIES_PER_SOURCE): List<String> {
        val array = item.optJSONArray("qualities")
        if (array == null) return listOf("")
        val all = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) all.add(value)
        }
        if (all.isEmpty()) return listOf("")
        all.sortByDescending { qualityRank(it) }
        return all.take(max)
    }

    /**
     * يختار المصادر المطلوبة:
     *  1. العربية (مترجم/مدبلج) — الأغنى بجودات أولاً، بتوزيع دوري.
     *  2. غير العربية — بعدد محدود جداً.
     */
    fun select(items: JSONArray): List<Choice> {
        val ordered = ArrayList<JSONObject>(items.length())
        for (pass in 0..1) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val arabic = isArabic(item)
                if ((pass == 0 && arabic) || (pass == 1 && !arabic)) ordered.add(item)
            }
        }

        val selected = ArrayList<Choice>(ordered.size)
        // المصادر العربية تحصل على كامل الميزانية ما عدا الحجز، والمصادر
        // الأخرى تأخذ الحجز فقط.
        var budget = REQUEST_BUDGET - FOREIGN_RESERVE

        // مجموعات (خادم + نوع مسار) — كل مجموعة مصادرها بنفس اللغة.
        val groups = LinkedHashMap<String, ArrayList<JSONObject>>()
        for (item in ordered) {
            if (!isArabic(item)) continue
            val key = "${item.optString("provider")}|${item.optString("sub_title")}"
            groups.getOrPut(key) { ArrayList() }.add(item)
        }
        // المجموعات الأغنى بجودات أولاً (مصدر واحد = عدة جودات).
        val sortedGroups = groups.entries.sortedByDescending { entry ->
            entry.value.sumOf { qualitiesOf(it).size }
        }
        // توزيع دوري: مصدر واحد من كل مجموعة قبل تكرار أي مجموعة، حتى لا
        // تستهلك مجموعة واحدة (مثل streamtape) الميزانية كاملة.
        var round = 0
        var progressed = true
        while (progressed) {
            progressed = false
            for (group in sortedGroups) {
                val item = group.value.getOrNull(round) ?: continue
                val qualities = qualitiesOf(item)
                if (qualities.isEmpty()) continue
                val c = cost(qualities)
                if (c > budget) continue
                budget -= c
                selected.add(Choice(item, qualities))
                progressed = true
            }
            round++
        }

        var foreignCount = 0
        var foreignBudget = FOREIGN_RESERVE
        for (item in ordered) {
            if (isArabic(item)) continue
            if (foreignCount >= MAX_FOREIGN_SOURCES) break
            val qualities = qualitiesOf(item)
            if (qualities.isEmpty()) continue
            val c = cost(qualities)
            if (c > foreignBudget) break
            foreignBudget -= c
            foreignCount++
            selected.add(Choice(item, qualities))
        }

        return selected
    }
}

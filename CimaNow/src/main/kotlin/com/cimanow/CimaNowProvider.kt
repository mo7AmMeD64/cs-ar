package com.cimanow

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Cima Now (\u0633\u064a\u0645\u0627 \u0646\u0627\u0648, vip.cimanowinc.com) — CloudStream provider.
 *
 * The site REQUIRES LOGIN: the user logs in via their BROWSER, copies the cookies
 * (access-token JWT + profile-guid, or the full cookie set) and PASTES them into
 * the extension settings (Settings next to the extension in CloudStream).
 *
 * Verified (the cimanow HAR + live):
 * - Search: GET /?s={query} -> the article results (the selary/ episode + the \u0641\u064a\u0644\u0645/ movie links)
 * - The episode pages: /selary/{slug}/  |  the movie pages: /\u0641\u064a\u0644\u0645-{slug}/ + /watching/
 * - The WATCHING page content may be OBFUSCATED:
 *     <script id="g_XXXXXX" data-...> = ~3421 base64 chunks + a decoder
 *     decode: atob(chunks joined) XOR key, where the key = String(p1+p2+p3+z-index+offsetWidth)
 *     (the constants in the decoder are decoys — the key is derived from the known
 *     plaintext "<!DOCTYPE html>" prefix instead — it is a 6-digit repeating key, e.g. "159022")
 *     + an anti-scrape patch (the hrefs -> void(0)) hooks the DOM getters only —
 *     the RAW bytes decode fine
 * - The decoded page: the direct mp4s (deva-*.cimanowtv.com/uploads/...),
 *   the VK embeds (vk.com/video_ext.php), the quality|url pairs
 * - The watchlist API: api.cimanow.online/watchlist?action=posts&profile={guid} + authorization: {jwt}
 */
private const val PREF_COOKIES = "cimanow_cookies"

class CimaNowProvider(private val prefs: SharedPreferences) : MainAPI() {

    override var name = "CimaNow"
    override var mainUrl = "https://vip.cimanowinc.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    companion object {
        const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        val PLAIN_PREFIX = "<!DOCTYPE html>"
    }

    /**
     * The cookie header: accepts BOTH formats:
     *   1. the raw Cookie format:  access-token=...; profile-guid=...
     *   2. the browser JSON export: [{"name":"access-token","value":"..."},...]
     */
    private fun cookieHeader(): String {
        val raw = prefs.getString(PREF_COOKIES, "")?.trim() ?: ""
        if (raw.startsWith("[")) {
            return try {
                val arr = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val n = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val v = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    "$n=$v"
                }.joinToString("; ")
            } catch (_: Exception) {
                ""
            }
        }
        return raw
    }

    private suspend fun get(url: String): String? = try {
        val res = app.get(
            url,
            headers = mapOf("User-Agent" to UA, "Cookie" to cookieHeader(), "Referer" to "$mainUrl/"),
        )
        if (res.code != 200) null else res.text
    } catch (_: Exception) {
        null
    }

    private suspend fun getAsync(url: String): String? = get(url)

    /**
     * The obfuscated pages: <script id="g_XXXX" data-...> with the base64 chunks + the XOR key.
     * The key is derived from the known plaintext prefix (the decoder's constants are decoys).
     */
    private fun deobfuscate(pageHtml: String): String? {
        val m = Regex("<script id=\"[^\"]*\"[^>]*data-[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(pageHtml) ?: return null
        val js = m.groupValues[1]
        val chunks = Regex("\"([A-Za-z0-9+/=]{50,})\"").findAll(js).map { it.groupValues[1] }.toList()
        if (chunks.isEmpty()) return null
        val b64 = chunks.joinToString("")
        val dec = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        if (dec.size < PLAIN_PREFIX.length) return null
        // the key = the first bytes XOR the known plaintext (a 6-char repeating key)
        val key = ByteArray(6) { (dec[it].toInt() xor PLAIN_PREFIX[it].code).toByte() }
        // sanity: the key must be printable ASCII digits
        if (!key.all { it.toInt() in 48..57 }) return null
        val out = ByteArray(dec.size) { (dec[it].toInt() xor key[it % 6].toInt()).toByte() }
        return String(out, Charsets.UTF_8)
    }

    private data class Card(val url: String, val title: String, val poster: String?)

    private fun cardsFrom(html: String): List<Card> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Card>()
        val seen = mutableSetOf<String>()
        // 1. the a[title] cards (the search page: <a href title class="movie__block">)
        doc.select("a[title]").forEach { a ->
            val href = (a.absUrl("href").ifBlank { a.attr("href") }).trim()
            val title = a.attr("title").trim()
            if (href.isBlank() || title.isBlank()) return@forEach
            if (!seen.add(href)) return@forEach
            val img = a.selectFirst("img")?.let { im ->
                im.attr("data-src").ifBlank { im.attr("src") }.takeIf { it.contains("media") || it.contains("upload") }
            }
            out.add(Card(href, title, img))
        }
        // 2. the picture>a cards (the category/home pages: <picture><a href><img alt></a></picture>)
        doc.select("picture > a[href], picture a[href]").forEach { a ->
            val href = (a.absUrl("href").ifBlank { a.attr("href") }).trim()
            if (href.isBlank()) return@forEach
            if (!seen.add(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val title = img.attr("alt").trim()
            if (title.isBlank()) return@forEach
            val src = img.attr("src").takeIf { it.contains("media") || it.contains("upload") }
            out.add(Card(href, title, src))
        }
        // keep only the content posts on the site's domain
        return out.filter { c ->
            c.url.contains("vip.cimanowinc.com") &&
                !Regex("(category|tag|actor|year|signin|plans|dmca|privacy|about)").containsMatchIn(c.url)
        }
    }

    private fun isMovie(url: String): Boolean =
        java.net.URLDecoder.decode(url, "UTF-8").contains("فيلم-")

    private fun itemFrom(c: Card): SearchResponse = newTvSeriesSearchResponse(
        c.title,
        "cimanow://post/${c.url}",
        if (isMovie(c.url)) TvType.Movie else TvType.TvSeries,
    ) {
        this.posterUrl = c.poster
    }

    // ---------- main page ----------

    override val mainPage = mainPageOf(
        "category/المسلسلات/" to "مسلسلات",
        "category/الافلام/" to "أفلام",
        "category/برامج-تلفزيونية/" to "برامج",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val html = get("$mainUrl/${request.data}") ?: return newHomePageResponse(request.name, emptyList())
        val items = cardsFrom(html).map { itemFrom(it) }
        return newHomePageResponse(request.name, items)
    }

    // ---------- search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val html = get("$mainUrl/?s=${java.net.URLEncoder.encode(q, "UTF-8")}") ?: return emptyList()
        return cardsFrom(html).map { itemFrom(it) }
    }

    // ---------- load ----------

    override suspend fun load(url: String): LoadResponse? {
        // NOTE: the url may be mangled by CloudStream's fixUrl; check "cimanow://" first
        if (!url.contains("cimanow://")) return null
        val postUrl = url.substringAfter("cimanow://post/", "").trim()
        if (!postUrl.startsWith("http")) return null

        val html = get(postUrl) ?: return null
        val real = deobfuscate(html) ?: html
        val doc = Jsoup.parse(real)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: java.net.URLDecoder.decode(postUrl.substringAfterLast("/"), "UTF-8").replace("-", " ")
        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?: doc.selectFirst("img[src*=\"media\"]")?.attr("src")
        val plot = doc.selectFirst("p")?.text()?.trim()

        // each post IS an episode/movie — the sibling episodes from the episode links
        val epLinks = doc.select("a[href*=\"/selary/\"]").map { it.absUrl("href").ifBlank { it.attr("href") } }
            .filter { it.isNotBlank() }.distinct()

        if (epLinks.size <= 1) {
            // a movie (or a single episode): the servers on the watching page
            return newMovieLoadResponse(title, url, TvType.Movie, "cimanow://post/$postUrl") {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        val seriesTitle = title.replace(Regex("الحلقة.*"), "").trim().ifBlank { title }
        val episodes = epLinks.mapIndexed { idx, href ->
            val decoded = java.net.URLDecoder.decode(href, "UTF-8")
            val epNum = Regex("-(?:ج|eps?)(\\d+)-?", RegexOption.IGNORE_CASE)
                .find(decoded)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            newEpisode(
                url = "cimanow://post/$href",
                initializer = {
                    this.name = "الحلقة $epNum"
                    this.episode = epNum
                },
                fix = false,
            )
        }
        return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------- links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.contains("cimanow://post/")) {
            val postUrl = data.substringAfter("cimanow://post/").trim()
            if (!postUrl.startsWith("http")) return false
            // the watching page has the player
            val watchUrl = postUrl.trimEnd('/').removeSuffix("/watching") + "/watching/"
            val html = get(watchUrl) ?: get(postUrl) ?: return false
            val real = deobfuscate(html) ?: html
            val links = Jsoup.parse(real).select("iframe[src], [data-link], a[href]").mapNotNull { el ->
                val u = el.attr("data-link").ifBlank {
                    el.attr("src").ifBlank { el.attr("href") }
                }.trim()
                u.takeIf {
                    it.startsWith("http") && !it.contains("googleads") && !it.contains("doubleclick") &&
                        !it.contains("freex2line") && !it.contains("youtube")
                }
            }.distinct()
            if (links.isEmpty()) return false
            var got = false
            for (l in links) {
                got = resolveLink(l, subtitleCallback, callback) || got
            }
            return got
        }
        if (data.startsWith("http")) {
            return resolveLink(data, subtitleCallback, callback)
        }
        return false
    }

    private suspend fun resolveLink(
        link: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val url = java.net.URLDecoder.decode(link, "UTF-8").trim()
            if (url.contains("freex2line.online") || url.contains("/watching")) return false
            if (url.contains(".mp4") || url.contains(".mkv")) {
                callback(
                    newExtractorLink(name, "CimaNow", url, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            loadExtractor(url, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)
        } catch (_: Exception) {
            false
        }
    }
}

/**
 * The cookie-paste settings: the user logs into vip.cimanowinc.com in their BROWSER,
 * copies the cookies, and pastes them here (opened via setOpenSettings).
 */
internal fun showCookieDialog(context: Context, prefs: SharedPreferences) {
    val dialog = Dialog(context)

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(60, 50, 60, 40)
        setBackgroundColor(Color.parseColor("#12141a"))
    }

    val title = TextView(context).apply {
        text = "CimaNow — تسجيل الدخول"
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 24)
    }
    root.addView(title)

    val help = TextView(context).apply {
        text = "1. افتح المتصفح وسجل الدخول في vip.cimanowinc.com\n" +
            "2. انسخ الكوكيز (access-token و profile-guid)\n" +
            "3. الصقها هنا بالصيغة: access-token=...; profile-guid=..."
        textSize = 13f
        setTextColor(Color.parseColor("#9aa3ad"))
        setPadding(0, 0, 0, 24)
    }
    root.addView(help)

    val input = EditText(context).apply {
        hint = "access-token=...; profile-guid=..."
        setSingleLine(false)
        minLines = 3
        gravity = Gravity.TOP
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#5a6470"))
        setBackgroundColor(Color.parseColor("#1c2028"))
        setPadding(24, 24, 24, 24)
        setText(prefs.getString(PREF_COOKIES, ""))
    }
    root.addView(input)

    val btnRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 24, 0, 0)
    }

    fun roundBtn(label: String, color: String, action: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = 24f
            }
            setOnClickListener { action() }
        }

    btnRow.addView(
        roundBtn("حفظ", "#c80101") {
            val v = input.text.toString().trim()
            val isJson = v.startsWith("[") || v.startsWith("{")
            val isRaw = v.contains("=") && (v.contains("token") || v.contains("guid"))
            if (v.length < 20 || (!isJson && !isRaw)) {
                Toast.makeText(context, "الصق الكوكيز كاملة (بدون ...) بصيغة access-token=...; profile-guid=...", Toast.LENGTH_LONG).show()
                return@roundBtn
            }
            prefs.edit().putString(PREF_COOKIES, v).apply()
            val fmt = if (isJson) "JSON" else "raw"
            Toast.makeText(context, "تم الحفظ ($fmt) ✓ حدّث الصفحة", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 12
        },
    )
    btnRow.addView(
        roundBtn("مسح", "#333a44") {
            prefs.edit().remove(PREF_COOKIES).apply()
            input.setText("")
            Toast.makeText(context, "تم المسح", Toast.LENGTH_SHORT).show()
        },
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )

    root.addView(btnRow)
    dialog.setContentView(root)
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.window?.setGravity(Gravity.CENTER)
    dialog.show()
}

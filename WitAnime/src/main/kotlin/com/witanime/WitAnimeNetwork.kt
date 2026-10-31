package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Resilient network, anti-Cloudflare, session-cookie, and rate-limit-aware gate resolution
 * engine for WitAnime (`https://witanime.site`) and its protected video hosts.
 */
object WitAnimeNetwork {

    private const val TAG = "WitAnimeNet"
    private const val PREFS_NAME = "witanime_net_v22"
    private const val KEY_COOKIES = "session_cookies"
    private const val KEY_USER_AGENT = "user_agent"
    private const val KEY_MAIN_URL = "main_url"

    const val DEFAULT_MAIN_URL = "https://witanime.site"

    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @Volatile
    var mainUrl: String = DEFAULT_MAIN_URL
        private set

    @Volatile
    var userAgent: String = DEFAULT_UA
        private set

    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    @Volatile
    private var prefsLoaded = false

    @Volatile
    private var preferWebViewTransport = false

    private val cfMutex = Mutex()
    private val proxyMutex = Mutex()
    private val hostPaceMutex = Mutex()
    private val lastHostCallAt = AtomicLong(0L)

    // DNS cache: host -> (expiresAtMs, addresses)
    private val dnsCache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    // Proxy pool for ASN-blocked VPN / datacenter exits & gate rate-limit overflow
    private val workingProxies = CopyOnWriteArrayList<String>()
    private val deadProxies = ConcurrentHashMap.newKeySet<String>()
    private val proxyFailCounts = ConcurrentHashMap<String, Int>()
    private val proxyClients = ConcurrentHashMap<String, ProxySessionClient>()

    private val PROXY_FEEDS = listOf(
        "socks5" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
        "http" to "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/http.txt",
        "socks5" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "http" to "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt"
    )

    fun init(context: Context) {
        appContextRef = WeakReference(context.applicationContext)
        ensurePrefsLoaded()
    }

    private fun getAppContext(): Context? = appContextRef?.get()

    // ── Session-Sticky Cookie Jar (handles Laravel XSRF + witanime-session rotation) ──

    class SessionCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, Cookie>()

        private fun baseDomain(host: String): String {
            val parts = host.lowercase().trim('.').split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val base = baseDomain(url.host)
            for (cookie in cookies) {
                val key = "$base|${cookie.name}"
                if (cookie.expiresAt <= System.currentTimeMillis() && cookie.value.isEmpty()) {
                    store.remove(key)
                } else {
                    store[key] = cookie
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val base = baseDomain(url.host)
            val result = mutableListOf<Cookie>()
            val iter = store.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val cookie = entry.value
                if (cookie.expiresAt < now) {
                    iter.remove()
                    continue
                }
                if (entry.key.startsWith("$base|") || cookie.matches(url)) {
                    result.add(cookie)
                }
            }
            return result
        }

        fun setRawCookie(hostOrUrl: String, name: String, value: String) {
            val host = if (hostOrUrl.contains("://")) {
                hostOrUrl.toHttpUrlOrNull()?.host ?: return
            } else {
                hostOrUrl
            }
            val base = baseDomain(host)
            val cookie = Cookie.Builder()
                .name(name.trim())
                .value(value.trim())
                .domain(base)
                .path("/")
                .expiresAt(System.currentTimeMillis() + 86_400_000L)
                .build()
            store["$base|${name.trim()}"] = cookie
        }

        fun getCookieMap(url: String): Map<String, String> {
            val httpUrl = url.toHttpUrlOrNull() ?: return emptyMap()
            return loadForRequest(httpUrl).associate { it.name to it.value }
        }

        fun buildCookieHeader(url: String): String {
            return getCookieMap(url).entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    val cookieJar = SessionCookieJar()

    // ── Trust-All SSL (handles mp4upload:183 & CDN edge chains) ──────────────

    val trustAllManager: X509TrustManager = @SuppressLint("CustomX509TrustManager") object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val trustAllSslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }.socketFactory

    // ── Hybrid System + DNS-over-HTTPS Resolver ──────────────────────────────

    private val dohBootstrapClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    val hybridDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == "127.0.0.1" || hostname == "localhost") {
                return Dns.SYSTEM.lookup(hostname)
            }
            val key = hostname.lowercase()
            val now = System.currentTimeMillis()
            dnsCache[key]?.let { (exp, addrs) ->
                if (exp > now && addrs.isNotEmpty()) return addrs
            }

            try {
                val sys = Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty() && sys.none { it.isLoopbackAddress || it.isAnyLocalAddress }) {
                    dnsCache[key] = (now + 1_800_000L) to sys
                    return sys
                }
            } catch (_: Exception) {
                // Fall back to DoH
            }

            val dohEndpoints = listOf(
                "https://1.1.1.1/dns-query?name=${URLEncoder.encode(hostname, "UTF-8")}&type=A",
                "https://8.8.8.8/resolve?name=${URLEncoder.encode(hostname, "UTF-8")}&type=A"
            )
            for (endpoint in dohEndpoints) {
                try {
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("Accept", "application/dns-json")
                        .build()
                    dohBootstrapClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string() ?: return@use
                        val answers = JSONObject(body).optJSONArray("Answer") ?: return@use
                        val list = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val ans = answers.optJSONObject(i) ?: continue
                            if (ans.optInt("type") == 1) {
                                val ip = ans.optString("data").trim()
                                if (ip.isNotEmpty()) {
                                    runCatching { InetAddress.getByName(ip) }.getOrNull()?.let(list::add)
                                }
                            }
                        }
                        if (list.isNotEmpty()) {
                            dnsCache[key] = (now + 1_800_000L) to list
                            return list
                        }
                    }
                } catch (_: Exception) {
                }
            }

            return Dns.SYSTEM.lookup(hostname)
        }
    }

    // ── Chromium Client Hints Interceptor (required by witanime.site /sources) ─

    private val CHROME_VER_REGEX = Regex("""Chrome/(\d+)""")

    private val browserClientHintsInterceptor = okhttp3.Interceptor { chain ->
        val orig = chain.request()
        val ua = orig.header("User-Agent") ?: userAgent
        if (ua.contains("Chrome/", ignoreCase = true) && orig.header("sec-ch-ua") == null) {
            val major = CHROME_VER_REGEX.find(ua)?.groupValues?.get(1) ?: "131"
            val isMobile = if (ua.contains("Mobile", ignoreCase = true)) "?1" else "?0"
            val platform = when {
                ua.contains("Android", ignoreCase = true) -> "\"Android\""
                ua.contains("Macintosh", ignoreCase = true) -> "\"macOS\""
                ua.contains("Linux", ignoreCase = true) -> "\"Linux\""
                else -> "\"Windows\""
            }
            val updated = orig.newBuilder()
                .header("sec-ch-ua", "\"Google Chrome\";v=\"$major\", \"Chromium\";v=\"$major\", \"Not_A Brand\";v=\"24\"")
                .header("sec-ch-ua-mobile", isMobile)
                .header("sec-ch-ua-platform", platform)
                .build()
            chain.proceed(updated)
        } else {
            chain.proceed(orig)
        }
    }

    // ── Primary OkHttp Clients ───────────────────────────────────────────────

    val directClient: OkHttpClient = OkHttpClient.Builder()
        .dns(hybridDns)
        .cookieJar(cookieJar)
        .addInterceptor(browserClientHintsInterceptor)
        .sslSocketFactory(trustAllSslSocketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    val noRedirectClient: OkHttpClient = directClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    val subHostFastClient: OkHttpClient = directClient.newBuilder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(11, TimeUnit.SECONDS)
        .build()

    private val cfKiller by lazy { CloudflareKiller() }

    private val cfKillerClient: OkHttpClient by lazy {
        directClient.newBuilder()
            .addInterceptor(cfKiller)
            .callTimeout(14, TimeUnit.SECONDS)
            .build()
    }

    private val feedClient = OkHttpClient.Builder()
        .dns(hybridDns)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun ensurePrefsLoaded() {
        if (prefsLoaded) return
        synchronized(this) {
            if (prefsLoaded) return
            try {
                val ctx = getAppContext() ?: return
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.getString(KEY_MAIN_URL, null)?.takeIf { it.startsWith("http") }?.let {
                    mainUrl = it.trimEnd('/')
                }
                prefs.getString(KEY_USER_AGENT, null)?.takeIf { it.isNotBlank() }?.let {
                    userAgent = it
                }
                prefs.getString(KEY_COOKIES, null)?.takeIf { it.isNotBlank() }?.let { raw ->
                    val json = JSONObject(raw)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = json.optString(k)
                        if (v.isNotBlank()) cookieJar.setRawCookie(mainUrl, k, v)
                    }
                }
                prefsLoaded = true
            } catch (_: Exception) {
            }
        }
    }

    private fun saveSessionToPrefs() {
        try {
            val ctx = getAppContext() ?: return
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cookiesJson = JSONObject()
            for ((k, v) in cookieJar.getCookieMap(mainUrl)) {
                cookiesJson.put(k, v)
            }
            prefs.edit()
                .putString(KEY_MAIN_URL, mainUrl)
                .putString(KEY_USER_AGENT, userAgent)
                .putString(KEY_COOKIES, cookiesJson.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    // ── URL Normalization & Cloudflare Detection ─────────────────────────────

    fun encodeUrlIfNeeded(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            val u = URI(trimmed)
            u.toASCIIString()
        } catch (_: Exception) {
            buildString(trimmed.length + 16) {
                for (ch in trimmed) {
                    if (ch.code <= 0x7E && ch != ' ') {
                        append(ch)
                    } else {
                        append(URLEncoder.encode(ch.toString(), "UTF-8").replace("+", "%20"))
                    }
                }
            }
        }
    }

    fun resolveUrl(href: String?, base: String = mainUrl): String? {
        val raw = href?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("javascript:") && it != "#" }
            ?: return null
        val full = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "${base.trimEnd('/')}$raw"
            else -> "${base.trimEnd('/')}/$raw"
        }
        return encodeUrlIfNeeded(full)
    }

    fun isCloudflareChallenge(code: Int, body: String, serverHeader: String? = null): Boolean {
        if (code == 403 || code == 503 || code == 429) {
            if (body.contains("_cf_chl_opt") ||
                body.contains("cf-mitigated") ||
                body.contains("Just a moment...") ||
                body.contains(" لحظة من فضلك") ||
                body.contains("challenge-platform") ||
                body.contains("__cf_chl_")
            ) {
                return true
            }
            if ((code == 403 || code == 503) &&
                serverHeader?.contains("cloudflare", ignoreCase = true) == true &&
                body.length < 20_000 &&
                !body.contains("witanime", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    private suspend fun paceMainHost(minGapMs: Long = 190L) {
        hostPaceMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastHostCallAt.get()
            if (elapsed in 0 until minGapMs) {
                delay(minGapMs - elapsed)
            }
            lastHostCallAt.set(System.currentTimeMillis())
        }
    }

    private fun updateMainOriginIfRedirected(finalUrl: HttpUrl) {
        val host = finalUrl.host.lowercase()
        if (host.contains("witanime") && !host.startsWith("images.")) {
            val newBase = "${finalUrl.scheme}://${finalUrl.host}"
            if (newBase != mainUrl) {
                mainUrl = newBase
                saveSessionToPrefs()
            }
        }
    }

    // ── Silent Android WebView Solver & TLS-Bound Fetcher ────────────────────

    private data class WebViewHolder(
        val webView: WebView,
        val attachedContainer: ViewGroup?,
        val parentGroup: ViewGroup?
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun createStealthWebViewOnMain(): WebViewHolder? {
        val ctx = getAppContext() ?: return null
        var container: FrameLayout? = null
        var parentGroup: ViewGroup? = null

        val webView = WebView(ctx)
        if (ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed) {
            parentGroup = ctx.window?.decorView?.findViewById(android.R.id.content)
            if (parentGroup != null) {
                container = FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    alpha = 0f
                    visibility = View.INVISIBLE
                }
                container.addView(webView, FrameLayout.LayoutParams(1, 1))
                parentGroup.addView(container)
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            val defaultUa = userAgentString ?: DEFAULT_UA
            val cleanUa = defaultUa
                .replace("; wv)", ")")
                .replace(" wv ", " ")
                .replace(Regex("""Version/\d+\.\d+\s*"""), "")
                .trim()
            userAgentString = cleanUa
            userAgent = cleanUa
        }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        return WebViewHolder(webView, container, parentGroup)
    }

    private fun destroyWebViewOnMain(holder: WebViewHolder?) {
        if (holder == null) return
        try {
            holder.webView.stopLoading()
            holder.webView.loadUrl("about:blank")
            holder.attachedContainer?.removeAllViews()
            if (holder.parentGroup != null && holder.attachedContainer != null) {
                holder.parentGroup.removeView(holder.attachedContainer)
            }
            holder.webView.destroy()
        } catch (_: Exception) {
        }
    }

    private fun syncWebViewCookiesForUrl(targetUrl: String) {
        try {
            val rawCookies = CookieManager.getInstance().getCookie(targetUrl) ?: return
            for (part in rawCookies.split(";")) {
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    if (k.isNotEmpty()) cookieJar.setRawCookie(targetUrl, k, v)
                }
            }
            saveSessionToPrefs()
        } catch (_: Exception) {
        }
    }

    private suspend fun solveCloudflareInWebView(targetUrl: String, timeoutMs: Long = 22_000L): String? {
        if (getAppContext() == null) return null
        return cfMutex.withLock {
            withTimeoutOrNull(timeoutMs) {
                val deferred = CompletableDeferred<String?>()
                var holder: WebViewHolder? = null
                val mainHandler = Handler(Looper.getMainLooper())

                try {
                    withContext(Dispatchers.Main) {
                        holder = createStealthWebViewOnMain()
                        val wv = holder?.webView
                        if (wv == null) {
                            deferred.complete(null)
                            return@withContext
                        }

                        var pollCount = 0
                        fun pollHtml() {
                            if (deferred.isCompleted) return
                            pollCount++
                            wv.evaluateJavascript(
                                "(function(){ return JSON.stringify({h: document.documentElement ? document.documentElement.outerHTML : '', t: document.title || '', u: location.href || ''}); })();"
                            ) { raw ->
                                if (deferred.isCompleted) return@evaluateJavascript
                                try {
                                    if (raw != null && raw != "null") {
                                        val unquoted = JSONArray("[$raw]").optString(0)
                                        val obj = JSONObject(unquoted)
                                        val html = obj.optString("h")
                                        val title = obj.optString("t")
                                        val loc = obj.optString("u", targetUrl)
                                        val challenging = html.contains("_cf_chl_opt") ||
                                            title.contains("Just a moment", ignoreCase = true) ||
                                            title.contains("لحظة")

                                        if (!challenging && html.length > 600) {
                                            syncWebViewCookiesForUrl(loc)
                                            syncWebViewCookiesForUrl(targetUrl)
                                            CookieManager.getInstance().flush()
                                            deferred.complete(html)
                                            return@evaluateJavascript
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                                if (pollCount < 32 && !deferred.isCompleted) {
                                    mainHandler.postDelayed({ pollHtml() }, 650L)
                                } else if (!deferred.isCompleted) {
                                    deferred.complete(null)
                                }
                            }
                        }

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let { syncWebViewCookiesForUrl(it) }
                                mainHandler.postDelayed({ pollHtml() }, 450L)
                            }
                        }

                        wv.loadUrl(encodeUrlIfNeeded(targetUrl))
                    }
                    deferred.await()
                } catch (_: Exception) {
                    null
                } finally {
                    withContext(Dispatchers.Main) {
                        destroyWebViewOnMain(holder)
                    }
                }
            }
        }
    }

    // ── Proxy Pool Harvester & Sticky Proxy Session Client ───────────────────

    class ProxySessionClient(
        val spec: String,
        val proxy: Proxy
    ) {
        val jar = SessionCookieJar()

        val client: OkHttpClient = OkHttpClient.Builder()
            .proxy(proxy)
            .cookieJar(jar)
            .addInterceptor(browserClientHintsInterceptor)
            .sslSocketFactory(trustAllSslSocketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

        val noRedirectClient: OkHttpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private fun parseProxySpec(spec: String): Proxy? {
        val parts = spec.split("://", limit = 2)
        if (parts.size != 2) return null
        val scheme = parts[0].lowercase()
        if (scheme == "socks4") return null // Android SocksSocketImpl hangs on SOCKS4 servers
        val hostPort = parts[1].trim()
        val colonIdx = hostPort.lastIndexOf(':')
        if (colonIdx <= 0) return null
        val host = hostPort.substring(0, colonIdx).trim()
        val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val type = when {
            scheme == "socks5" || scheme == "socks" -> Proxy.Type.SOCKS
            scheme == "http" || scheme == "https" -> Proxy.Type.HTTP
            else -> return null
        }
        val resolvedAddr = runCatching { InetSocketAddress(InetAddress.getByName(host), port) }.getOrNull()
            ?: return null
        return Proxy(type, resolvedAddr)
    }

    private fun getOrCreateProxySession(spec: String): ProxySessionClient? {
        proxyClients[spec]?.let { return it }
        val proxy = parseProxySpec(spec) ?: return null
        val created = ProxySessionClient(spec, proxy)
        proxyClients[spec] = created
        return created
    }

    private fun markProxySuccess(spec: String) {
        proxyFailCounts.remove(spec)
        if (!workingProxies.contains(spec)) {
            workingProxies.add(0, spec)
        }
    }

    private fun markProxyFailure(spec: String) {
        val fails = (proxyFailCounts[spec] ?: 0) + 1
        proxyFailCounts[spec] = fails
        if (fails >= 2) {
            workingProxies.remove(spec)
            deadProxies.add(spec)
            proxyClients.remove(spec)
        }
    }

    private suspend fun ensureWorkingProxies(
        probeUrl: String = "$mainUrl/",
        minRequired: Int = 1,
        maxBudgetMs: Long = 7_500L
    ) {
        if (workingProxies.size >= minRequired) return
        proxyMutex.withLock {
            if (workingProxies.size >= minRequired) return
            withTimeoutOrNull(maxBudgetMs) {
                withContext(Dispatchers.IO) {
                    val candidates = LinkedHashSet<String>()
                    withTimeoutOrNull(2_800L) {
                        coroutineScope {
                            PROXY_FEEDS.map { (scheme, feedUrl) ->
                                async<List<String>> {
                                    try {
                                        val req = Request.Builder().url(feedUrl).header("User-Agent", DEFAULT_UA).build()
                                        feedClient.newCall(req).execute().use { resp ->
                                            if (!resp.isSuccessful) return@use emptyList()
                                            val stream = resp.body?.byteStream() ?: return@use emptyList()
                                            val picked = ArrayList<String>(60)
                                            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                                                while (picked.size < 60) {
                                                    val line = reader.readLine() ?: break
                                                    val clean = line.trim()
                                                    if (clean.contains(':') && !clean.startsWith('#')) {
                                                        picked.add("$scheme://$clean")
                                                    }
                                                }
                                            }
                                            picked
                                        }
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }
                            }.awaitAll().flatten().shuffled().forEach { spec ->
                                if (!deadProxies.contains(spec)) candidates.add(spec)
                            }
                        }
                    }

                    val batch = candidates.take(24)
                    val sem = Semaphore(12)
                    coroutineScope {
                        batch.map { spec ->
                            async {
                                if (workingProxies.size >= minRequired + 1) return@async
                                sem.withPermit {
                                    if (workingProxies.size >= minRequired + 1) return@withPermit
                                    val pClient = getOrCreateProxySession(spec) ?: return@withPermit
                                    try {
                                        val req = Request.Builder()
                                            .url(encodeUrlIfNeeded(probeUrl))
                                            .header("User-Agent", userAgent)
                                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                                            .build()
                                        pClient.client.newCall(req).execute().use { resp ->
                                            val body = resp.body?.string().orEmpty()
                                            if (resp.code == 200 &&
                                                body.length > 500 &&
                                                !isCloudflareChallenge(resp.code, body, resp.header("Server"))
                                            ) {
                                                markProxySuccess(spec)
                                            } else {
                                                deadProxies.add(spec)
                                                proxyClients.remove(spec)
                                            }
                                        }
                                    } catch (_: Exception) {
                                        deadProxies.add(spec)
                                        proxyClients.remove(spec)
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }
    }

    private suspend fun fetchHtmlViaProxy(url: String, referer: String? = null): String? = withContext(Dispatchers.IO) {
        ensureWorkingProxies(url, minRequired = 1)
        for (spec in workingProxies.toList()) {
            val pSession = getOrCreateProxySession(spec) ?: continue
            try {
                val reqBuilder = Request.Builder()
                    .url(encodeUrlIfNeeded(url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Upgrade-Insecure-Requests", "1")
                if (!referer.isNullOrBlank()) {
                    reqBuilder.header("Referer", encodeUrlIfNeeded(referer))
                }
                pSession.client.newCall(reqBuilder.build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.code == 200 &&
                        body.length > 400 &&
                        !isCloudflareChallenge(resp.code, body, resp.header("Server"))
                    ) {
                        markProxySuccess(spec)
                        return@withContext body
                    }
                    if (isCloudflareChallenge(resp.code, body, resp.header("Server"))) {
                        markProxyFailure(spec)
                    }
                }
            } catch (_: Exception) {
                markProxyFailure(spec)
            }
        }
        null
    }

    // ── Core Page & JSON Fetchers ────────────────────────────────────────────

    private fun buildDocumentRequest(url: String, referer: String?): Request {
        val builder = Request.Builder()
            .url(encodeUrlIfNeeded(url))
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-User", "?1")
            .header("Sec-Fetch-Site", if (referer != null) "same-origin" else "none")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", encodeUrlIfNeeded(referer))
        }
        return builder.build()
    }

    suspend fun fetchDocument(url: String, referer: String? = "$mainUrl/"): Document? = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()
        val fullUrl = resolveUrl(url) ?: return@withContext null

        // Tier 1: Direct OkHttp with polite pacing
        if (!preferWebViewTransport) {
            try {
                paceMainHost()
                directClient.newCall(buildDocumentRequest(fullUrl, referer)).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    updateMainOriginIfRedirected(resp.request.url)
                    if (resp.isSuccessful && !isCloudflareChallenge(resp.code, body, resp.header("Server")) && body.length > 400) {
                        saveSessionToPrefs()
                        return@withContext Jsoup.parse(body, resp.request.url.toString())
                    }
                    if (resp.code == 404) return@withContext null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Direct fetch failed for $fullUrl: ${e.message}")
            }
        }

        // Tier 2: Silent Android WebView solver
        val wvHtml = solveCloudflareInWebView(fullUrl)
        if (!wvHtml.isNullOrBlank() && wvHtml.length > 500) {
            // Test whether cf_clearance works on directClient or is TLS-bound to WebView
            if (!preferWebViewTransport) {
                try {
                    directClient.newCall(buildDocumentRequest(fullUrl, referer)).execute().use { probe ->
                        val pb = probe.body?.string().orEmpty()
                        if (isCloudflareChallenge(probe.code, pb, probe.header("Server"))) {
                            preferWebViewTransport = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return@withContext Jsoup.parse(wvHtml, fullUrl)
        }

        // Tier 3: CloudStream CloudflareKiller
        try {
            cfKillerClient.newCall(buildDocumentRequest(fullUrl, referer)).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && !isCloudflareChallenge(resp.code, body, resp.header("Server")) && body.length > 400) {
                    return@withContext Jsoup.parse(body, resp.request.url.toString())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }

        // Tier 4: Sticky proxy pool
        val proxyHtml = fetchHtmlViaProxy(fullUrl, referer)
        if (!proxyHtml.isNullOrBlank()) {
            return@withContext Jsoup.parse(proxyHtml, fullUrl)
        }

        null
    }

    /**
     * Fetches `/new-episodes?page=<page>` JSON (`{"page":N,"html":"...","hasOlder":true}`).
     * Requires an active `witanime-session` cookie on the client performing the call.
     */
    suspend fun fetchNewEpisodesPage(page: Int): Pair<Document, Boolean>? = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()
        val apiUrl = "$mainUrl/new-episodes?page=$page"

        fun parseNewEpJson(raw: String): Pair<Document, Boolean>? {
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val html = obj.optString("html")
            if (html.isBlank()) return null
            val hasOlder = obj.optBoolean("hasOlder", false)
            return Jsoup.parseBodyFragment(html, mainUrl) to hasOlder
        }

        // Tier 1: Direct session (prime `/` if witanime-session is missing)
        if (!preferWebViewTransport) {
            try {
                if (!cookieJar.getCookieMap(mainUrl).containsKey("witanime-session")) {
                    paceMainHost()
                    directClient.newCall(buildDocumentRequest("$mainUrl/", null)).execute().close()
                }
                paceMainHost()
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Referer", "$mainUrl/")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()
                directClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        parseNewEpJson(body)?.let {
                            saveSessionToPrefs()
                            return@withContext it
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }

        // Tier 4: Proxy session (self-primes `/` on the sticky proxy CookieJar if needed)
        ensureWorkingProxies("$mainUrl/", minRequired = 1)
        for (spec in workingProxies.toList()) {
            val pSession = getOrCreateProxySession(spec) ?: continue
            try {
                if (!pSession.jar.getCookieMap(mainUrl).containsKey("witanime-session")) {
                    pSession.client.newCall(buildDocumentRequest("$mainUrl/", null)).execute().close()
                }
                val req = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Referer", "$mainUrl/")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()
                pSession.client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        parseNewEpJson(body)?.let {
                            markProxySuccess(spec)
                            return@withContext it
                        }
                    }
                }
            } catch (_: Exception) {
                markProxyFailure(spec)
            }
        }

        null
    }

    // ── Watch Page Gate Resolution Engine ────────────────────────────────────

    data class ResolvedWitSource(
        val kind: String,         // "stream" or "download"
        val serverName: String,   // e.g. "videas", "videa", "mega", "hgcloud", "mp4upload", "ok", "yonaplay", "4shared", "gofile", "workupload", "mediafire"
        val qualityLabel: String, // e.g. "4K", "FHD", "HD", "SD"
        val version: String,      // "sub" or "dub"
        val lang: String,         // "jpn", "ara", etc.
        val resolvedUrl: String,  // Final unlocked third-party embed/download URL
        val referer: String?      // Recommended Referer for the third-party host
    )

    private data class RawGateToken(
        val kind: String,         // "stream" or "download"
        val serverName: String,
        val qualityLabel: String,
        val version: String,
        val lang: String,
        val token: String,
        val occurrence: Int = 0
    ) {
        val slotKey: String
            get() = "$kind|$version|$lang|${qualityLabel.uppercase()}|${serverName.lowercase()}|$occurrence"
    }

    private data class ActiveWatchSession(
        val label: String,
        val client: OkHttpClient,
        val noRedirect: OkHttpClient,
        val watchUrl: String,
        val csrfToken: String,
        val tokensBySlot: Map<String, RawGateToken>,
        val orderedTokens: List<RawGateToken>,
        val proxySpec: String? = null
    )

    private fun qualityRank(q: String): Int = when (q.uppercase().trim()) {
        "4K", "2160P", "UHD" -> 400
        "FHD", "1080P" -> 300
        "HD", "720P" -> 200
        "SD", "480P" -> 100
        else -> 50
    }

    private fun hostFamily(name: String): String {
        val n = name.lowercase().trim()
        return when {
            n.contains("videas") || n == "svidea" -> "videas"
            n == "videa" -> "videa"
            n == "ok" || n.contains("okru") -> "ok"
            n.contains("mp4upload") -> "mp4upload"
            n.contains("hgcloud") || n.contains("streamhg") -> "hgcloud"
            n.contains("mega") -> "mega"
            n.contains("4shared") -> "4shared"
            n.contains("yonaplay") -> "yonaplay"
            n.contains("gofile") -> "gofile"
            n.contains("workupload") -> "workupload"
            n.contains("mediafire") -> "mediafire"
            else -> n
        }
    }

    private fun serverPriority(kind: String, name: String): Int {
        val fam = hostFamily(name)
        return if (kind == "stream") {
            when (fam) {
                "videas" -> 100
                "videa" -> 96
                "ok" -> 92
                "mp4upload" -> 90
                "hgcloud" -> 88
                "mega" -> 84
                "4shared" -> 80
                "yonaplay" -> 74
                else -> 60
            }
        } else {
            when (fam) {
                "gofile" -> 72
                "mp4upload" -> 70
                "mega" -> 66
                "workupload" -> 62
                "mediafire" -> 30
                else -> 40
            }
        }
    }

    private fun parseWatchSourcesPayload(jsonText: String): List<RawGateToken> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()

        val streamTokens = mutableListOf<RawGateToken>()
        val downloadTokens = mutableListOf<RawGateToken>()
        val streamKeys = HashSet<String>()
        val slotCounts = HashMap<String, Int>()

        fun collectGroup(playersObj: JSONObject?, downloadsObj: JSONObject?, defaultVer: String = "sub") {
            if (playersObj != null) {
                val qKeys = playersObj.keys()
                while (qKeys.hasNext()) {
                    val qLabel = qKeys.next()
                    val arr = playersObj.optJSONArray(qLabel) ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("label").ifBlank { item.optString("name") }.trim()
                        val tok = item.optString("token").trim()
                        val ver = item.optString("version", defaultVer).ifBlank { defaultVer }
                        val lang = item.optString("lang", if (ver == "dub") "ar" else "jp")
                        if (name.isNotEmpty() && tok.isNotEmpty()) {
                            val baseKey = "stream|$ver|$lang|${qLabel.uppercase()}|${name.lowercase()}"
                            val occ = slotCounts[baseKey] ?: 0
                            slotCounts[baseKey] = occ + 1
                            streamTokens.add(RawGateToken("stream", name, qLabel, ver, lang, tok, occ))
                            streamKeys.add("$ver|$lang|${qLabel.uppercase()}|${name.lowercase()}")
                        }
                    }
                }
            }

            if (downloadsObj != null) {
                val qKeys = downloadsObj.keys()
                while (qKeys.hasNext()) {
                    val qLabel = qKeys.next()
                    val arr = downloadsObj.optJSONArray(qLabel) ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val name = item.optString("label").ifBlank { item.optString("name") }.trim()
                        val tok = item.optString("token").trim()
                        val ver = item.optString("version", defaultVer).ifBlank { defaultVer }
                        val lang = item.optString("lang", if (ver == "dub") "ar" else "jp")
                        if (name.isNotEmpty() && tok.isNotEmpty()) {
                            // Deduplicate exact same (version, lang, quality, serverName) already in stream players
                            val dedupKey = "$ver|$lang|${qLabel.uppercase()}|${name.lowercase()}"
                            if (!streamKeys.contains(dedupKey)) {
                                val baseKey = "download|$ver|$lang|${qLabel.uppercase()}|${name.lowercase()}"
                                val occ = slotCounts[baseKey] ?: 0
                                slotCounts[baseKey] = occ + 1
                                downloadTokens.add(RawGateToken("download", name, qLabel, ver, lang, tok, occ))
                            }
                        }
                    }
                }
            }
        }

        // Primary WitAnime schema: top-level `players` & `downloads` maps keyed by quality ("4K", "FHD", "HD", "SD")
        if (root.has("players") || root.has("downloads")) {
            collectGroup(root.optJSONObject("players"), root.optJSONObject("downloads"))
        }

        // Legacy/nested schema fallback: `versions.<sub|dub>.{players, downloads}`
        root.optJSONObject("versions")?.let { versionsObj ->
            val verKeys = versionsObj.keys().asSequence().toList()
            for (ver in verKeys) {
                val vObj = versionsObj.optJSONObject(ver) ?: continue
                collectGroup(vObj.optJSONObject("players"), vObj.optJSONObject("downloads"), ver)
            }
        }

        // Interleave best quality per distinct server host FIRST so the initial 5-8 gate unlocks
        // guarantee maximum server & CDN diversity within WitAnime's per-minute rate limit!
        val streamsBestPerHost = mutableListOf<RawGateToken>()
        val streamsSecondary = mutableListOf<RawGateToken>()
        val seenStreamFamilies = HashSet<String>()

        val streamByQualityThenPriority = streamTokens.sortedWith(
            compareByDescending<RawGateToken> { serverPriority(it.kind, it.serverName) }
                .thenByDescending { qualityRank(it.qualityLabel) }
        )
        for (tok in streamByQualityThenPriority) {
            val famKey = "${tok.version}|${hostFamily(tok.serverName)}"
            if (seenStreamFamilies.add(famKey)) {
                streamsBestPerHost.add(tok)
            } else {
                streamsSecondary.add(tok)
            }
        }
        streamsSecondary.sortWith(
            compareByDescending<RawGateToken> { qualityRank(it.qualityLabel) }
                .thenByDescending { serverPriority(it.kind, it.serverName) }
        )

        val sortedDownloads = downloadTokens.sortedWith(
            compareByDescending<RawGateToken> { serverPriority(it.kind, it.serverName) }
                .thenByDescending { qualityRank(it.qualityLabel) }
        )
        val topGoFile = sortedDownloads.firstOrNull { hostFamily(it.serverName) == "gofile" }
        val otherDownloads = if (topGoFile != null) sortedDownloads.filter { it !== topGoFile } else sortedDownloads

        return buildList {
            addAll(streamsBestPerHost)
            if (topGoFile != null) add(topGoFile)
            addAll(streamsSecondary)
            addAll(otherDownloads)
        }
    }

    private val SOURCES_URL_REGEX = Regex("""sourcesUrl\s*:\s*['"]([^'"]+)['"]""")

    private fun openWatchSessionWithClient(
        label: String,
        client: OkHttpClient,
        noRedirect: OkHttpClient,
        watchUrl: String,
        proxySpec: String? = null
    ): ActiveWatchSession? {
        return try {
            val pageReq = buildDocumentRequest(watchUrl, "$mainUrl/")
            val html = client.newCall(pageReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || isCloudflareChallenge(resp.code, body, resp.header("Server")) || body.length < 500) {
                    return null
                }
                body
            }

            val doc = Jsoup.parse(html, watchUrl)
            val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null

            val rawSourcesPath = SOURCES_URL_REGEX.find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.trim()
                ?: doc.selectFirst("[data-watch-config]")?.attr("data-watch-config")
                    ?.let { runCatching { JSONObject(it).optString("sourcesUrl") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                ?: "${watchUrl.trimEnd('/')}/sources"

            val sourcesUrl = resolveUrl(rawSourcesPath, mainUrl) ?: return null

            val emptyBody = ByteArray(0).toRequestBody(null)
            val srcReq = Request.Builder()
                .url(sourcesUrl)
                .post(emptyBody)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("X-CSRF-TOKEN", csrf)
                .header("Origin", mainUrl)
                .header("Referer", encodeUrlIfNeeded(watchUrl))
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .build()

            val sourcesJson = client.newCall(srcReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty()
            }

            val ordered = parseWatchSourcesPayload(sourcesJson)
            if (ordered.isEmpty()) return null

            ActiveWatchSession(
                label = label,
                client = client,
                noRedirect = noRedirect,
                watchUrl = watchUrl,
                csrfToken = csrf,
                tokensBySlot = ordered.associateBy { it.slotKey },
                orderedTokens = ordered,
                proxySpec = proxySpec
            )
        } catch (_: Exception) {
            null
        }
    }

    private enum class GateAttemptOutcome {
        SUCCESS,
        RATE_LIMITED,
        FAILED
    }

    private data class GateAttemptResult(
        val outcome: GateAttemptOutcome,
        val resolved: ResolvedWitSource? = null,
        val rateLimitRemaining: Int = 0
    )

    private fun unlockSingleGate(
        session: ActiveWatchSession,
        item: RawGateToken
    ): GateAttemptResult {
        val emptyBody = ByteArray(0).toRequestBody(null)
        return try {
            if (item.kind == "stream") {
                val postUrl = "$mainUrl/watch/stream-source/${URLEncoder.encode(item.token, "UTF-8")}"
                val postReq = Request.Builder()
                    .url(postUrl)
                    .post(emptyBody)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", session.csrfToken)
                    .header("Origin", mainUrl)
                    .header("Referer", encodeUrlIfNeeded(session.watchUrl))
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()

                var gatePath = "/watch/stream-gate/${URLEncoder.encode(item.token, "UTF-8")}"
                var refPolicy = "no-referrer"

                session.client.newCall(postReq).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateAttemptResult(GateAttemptOutcome.RATE_LIMITED, null, rem)
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return GateAttemptResult(GateAttemptOutcome.FAILED)
                    runCatching { JSONObject(body) }.getOrNull()?.let { j ->
                        j.optString("gateUrl").takeIf { it.isNotBlank() }?.let { gatePath = it }
                        j.optString("referrerPolicy").takeIf { it.isNotBlank() }?.let { refPolicy = it }
                    }
                }

                val fullGateUrl = resolveUrl(gatePath, mainUrl) ?: return GateAttemptResult(GateAttemptOutcome.FAILED)
                val gateReqBuilder = Request.Builder()
                    .url(fullGateUrl)
                    .get()
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "iframe")
                    .header("Upgrade-Insecure-Requests", "1")
                if (refPolicy.equals("origin", ignoreCase = true)) {
                    gateReqBuilder.header("Referer", "$mainUrl/")
                }

                session.noRedirect.newCall(gateReqBuilder.build()).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateAttemptResult(GateAttemptOutcome.RATE_LIMITED, null, rem)
                    val loc = resp.header("Location")?.trim()
                    if (resp.code in 300..399 && !loc.isNullOrEmpty()) {
                        val resolved = resolveUrl(loc, fullGateUrl) ?: loc
                        val hostReferer = if (refPolicy.equals("origin", ignoreCase = true)) "$mainUrl/" else null
                        return GateAttemptResult(
                            GateAttemptOutcome.SUCCESS,
                            ResolvedWitSource(
                                kind = item.kind,
                                serverName = item.serverName,
                                qualityLabel = item.qualityLabel,
                                version = item.version,
                                lang = item.lang,
                                resolvedUrl = resolved,
                                referer = hostReferer
                            ),
                            rem
                        )
                    }
                    if (resp.code == 200) {
                        val html = resp.body?.string().orEmpty()
                        val doc = Jsoup.parse(html, fullGateUrl)
                        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
                        if (!iframeSrc.isNullOrBlank()) {
                            return GateAttemptResult(
                                GateAttemptOutcome.SUCCESS,
                                ResolvedWitSource(
                                    kind = item.kind,
                                    serverName = item.serverName,
                                    qualityLabel = item.qualityLabel,
                                    version = item.version,
                                    lang = item.lang,
                                    resolvedUrl = resolveUrl(iframeSrc, fullGateUrl) ?: iframeSrc,
                                    referer = "$mainUrl/"
                                ),
                                rem
                            )
                        }
                    }
                }
                GateAttemptResult(GateAttemptOutcome.FAILED)
            } else {
                val postUrl = "$mainUrl/watch/download-source/${URLEncoder.encode(item.token, "UTF-8")}"
                val postReq = Request.Builder()
                    .url(postUrl)
                    .post(emptyBody)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", session.csrfToken)
                    .header("Origin", mainUrl)
                    .header("Referer", encodeUrlIfNeeded(session.watchUrl))
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()

                session.client.newCall(postReq).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateAttemptResult(GateAttemptOutcome.RATE_LIMITED, null, rem)
                    if (!resp.isSuccessful) return GateAttemptResult(GateAttemptOutcome.FAILED)
                }

                val gateUrl = "$mainUrl/watch/download-gate/${URLEncoder.encode(item.token, "UTF-8")}"
                val gateReq = Request.Builder()
                    .url(gateUrl)
                    .get()
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Referer", encodeUrlIfNeeded(session.watchUrl))
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()

                session.noRedirect.newCall(gateReq).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateAttemptResult(GateAttemptOutcome.RATE_LIMITED, null, rem)
                    val loc = resp.header("Location")?.trim()
                    if (resp.code in 300..399 && !loc.isNullOrEmpty()) {
                        val resolved = resolveUrl(loc, gateUrl) ?: loc
                        return GateAttemptResult(
                            GateAttemptOutcome.SUCCESS,
                            ResolvedWitSource(
                                kind = item.kind,
                                serverName = item.serverName,
                                qualityLabel = item.qualityLabel,
                                version = item.version,
                                lang = item.lang,
                                resolvedUrl = resolved,
                                referer = "$mainUrl/"
                            ),
                            rem
                        )
                    }
                }
                GateAttemptResult(GateAttemptOutcome.FAILED)
            }
        } catch (_: Exception) {
            GateAttemptResult(GateAttemptOutcome.FAILED)
        }
    }

    /**
     * Streams unlocked watch & download server URLs for a WitAnime `/watch/...` page as soon as
     * each gate unlocks, allowing concurrent extractor invocation (`1.5–4s` time-to-first-link)
     * without ever stalling on dead public proxies or triggering CloudStream's 120s timeout.
     */
    suspend fun streamWatchPageSources(
        rawWatchUrl: String,
        allowOverflowProxies: Boolean = false,
        shouldContinue: (unlockedCount: Int) -> Boolean = { true },
        onSourceUnlocked: suspend (ResolvedWitSource) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        ensurePrefsLoaded()
        val watchUrl = resolveUrl(rawWatchUrl) ?: return@withContext 0
        val startMs = System.currentTimeMillis()

        val sessions = mutableListOf<ActiveWatchSession>()

        // 1. Direct OkHttp session (fast path on residential mobile/home networks: ~0.4-0.8s)
        if (!preferWebViewTransport) {
            withTimeoutOrNull(12_000L) {
                openWatchSessionWithClient("direct", directClient, noRedirectClient, watchUrl)
            }?.let(sessions::add)
        }

        // 2. If direct was challenged by Cloudflare, solve in WebView (bounded 12s) and retry direct
        if (sessions.isEmpty()) {
            solveCloudflareInWebView(watchUrl, timeoutMs = 12_000L)
            withTimeoutOrNull(10_000L) {
                openWatchSessionWithClient("direct-after-wv", directClient, noRedirectClient, watchUrl)
            }?.let(sessions::add)
        }

        // 3. Try CloudflareKiller (bounded 10s)
        if (sessions.isEmpty()) {
            val cfNoRedirect = cfKillerClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
            withTimeoutOrNull(10_000L) {
                openWatchSessionWithClient("cfkiller", cfKillerClient, cfNoRedirect, watchUrl)
            }?.let(sessions::add)
        }

        // 4. Strictly-bounded proxy fallback ONLY when direct/WebView/CFKiller could not open session at all
        val usedProxySpecs = HashSet<String>()
        suspend fun openNextProxyWatchSession(): ActiveWatchSession? = withTimeoutOrNull(8_500L) {
            ensureWorkingProxies(watchUrl, minRequired = 1, maxBudgetMs = 6_500L)
            for (spec in workingProxies.toList()) {
                if (!usedProxySpecs.add(spec)) continue
                val pClient = getOrCreateProxySession(spec) ?: continue
                val opened = openWatchSessionWithClient("proxy:$spec", pClient.client, pClient.noRedirectClient, watchUrl, spec)
                if (opened != null) {
                    markProxySuccess(spec)
                    return@withTimeoutOrNull opened
                } else {
                    markProxyFailure(spec)
                }
            }
            null
        }

        if (sessions.isEmpty()) {
            openNextProxyWatchSession()?.let(sessions::add)
        }

        val primary = sessions.firstOrNull()
        if (primary == null) {
            Log.w(TAG, "streamWatchPageSources: unable to open session for $watchUrl (${System.currentTimeMillis() - startMs}ms)")
            return@withContext 0
        }
        Log.i(TAG, "Opened watch session via ${primary.label} with ${primary.orderedTokens.size} tokens in ${System.currentTimeMillis() - startMs}ms")
        saveSessionToPrefs()

        val seenUrls = HashSet<String>()
        var currentSession: ActiveWatchSession = primary
        var unlockedCount = 0
        var lastGateStartMs = 0L

        for (slotItem in primary.orderedTokens) {
            if (!shouldContinue(unlockedCount)) {
                Log.i(TAG, "Stopping gate unlock early after $unlockedCount unlocked sources (sufficient links ready)")
                break
            }

            // Never spend precious rate-limit quota on MediaFire `.zip` archives once we unlocked real stream gates
            if (unlockedCount > 0 && slotItem.kind == "download" && hostFamily(slotItem.serverName) == "mediafire") {
                continue
            }

            var tokenForCurrent = currentSession.tokensBySlot[slotItem.slotKey] ?: continue
            if (lastGateStartMs > 0L) {
                // WitAnime enforces ~1.1s anti-rapid-click interval in addition to the 20/min quota
                val sincePrev = System.currentTimeMillis() - lastGateStartMs
                val waitMs = (1_150L - sincePrev).coerceAtLeast(180L)
                delay(waitMs)
            }
            lastGateStartMs = System.currentTimeMillis()

            var attempt = unlockSingleGate(currentSession, tokenForCurrent)

            if (attempt.outcome == GateAttemptOutcome.RATE_LIMITED) {
                if (attempt.rateLimitRemaining > 1) {
                    // Minute quota NOT exhausted — we only grazed the anti-rapid-click interval; 1.45s direct retry succeeds!
                    delay(1_450L)
                    lastGateStartMs = System.currentTimeMillis()
                    attempt = unlockSingleGate(currentSession, tokenForCurrent)
                }

                if (attempt.outcome == GateAttemptOutcome.RATE_LIMITED) {
                    if (unlockedCount > 0 && !allowOverflowProxies) {
                        // Direct session ALREADY unlocked priority servers; finish fast without proxy stall!
                        Log.i(TAG, "Minute quota exhausted after $unlockedCount unlocked gates; finishing fast")
                        break
                    }

                    if (unlockedCount == 0 && currentSession.proxySpec == null && !allowOverflowProxies) {
                        Log.i(TAG, "Hit HTTP 429 on first gate; retrying direct after 2.2s cooldown")
                        delay(2_200L)
                        lastGateStartMs = System.currentTimeMillis()
                        attempt = unlockSingleGate(currentSession, tokenForCurrent)
                    }

                    if (attempt.outcome == GateAttemptOutcome.RATE_LIMITED && (unlockedCount == 0 || allowOverflowProxies)) {
                        val overflow = openNextProxyWatchSession()
                        if (overflow != null) {
                            currentSession = overflow
                            tokenForCurrent = currentSession.tokensBySlot[slotItem.slotKey] ?: continue
                            delay(180L)
                            lastGateStartMs = System.currentTimeMillis()
                            attempt = unlockSingleGate(currentSession, tokenForCurrent)
                        } else {
                            break
                        }
                    }
                }
            }

            val resolved = attempt.resolved
            if (attempt.outcome == GateAttemptOutcome.SUCCESS && resolved != null) {
                if (seenUrls.add("${resolved.version}|${resolved.qualityLabel}|${resolved.resolvedUrl}")) {
                    unlockedCount++
                    onSourceUnlocked(resolved)
                }
            }
        }

        Log.i(TAG, "streamWatchPageSources finished: $unlockedCount unlocked in ${System.currentTimeMillis() - startMs}ms")
        unlockedCount
    }

    /**
     * Batch helper delegating to [streamWatchPageSources].
     */
    suspend fun resolveWatchPageSources(
        rawWatchUrl: String,
        allowOverflowProxies: Boolean = false
    ): List<ResolvedWitSource> {
        val out = CopyOnWriteArrayList<ResolvedWitSource>()
        streamWatchPageSources(
            rawWatchUrl = rawWatchUrl,
            allowOverflowProxies = allowOverflowProxies,
            onSourceUnlocked = { out.add(it) }
        )
        return out
    }

    // ── Protected Sub-Host Session Helper (YonaPlay / DotPlay / CF sub-hosts) ──

    class ProtectedHttpSession(
        private val client: OkHttpClient,
        val cookieJarRef: SessionCookieJar
    ) {
        fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String>? {
            return try {
                val reqBuilder = Request.Builder()
                    .url(encodeUrlIfNeeded(url))
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                for ((k, v) in headers) {
                    reqBuilder.header(k, if (k.equals("Referer", true) || k.equals("Origin", true)) encodeUrlIfNeeded(v) else v)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): Pair<Int, String>? {
            return try {
                val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                val reqBuilder = Request.Builder()
                    .url(encodeUrlIfNeeded(url))
                    .post(body)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                for ((k, v) in headers) {
                    reqBuilder.header(k, if (k.equals("Referer", true) || k.equals("Origin", true)) encodeUrlIfNeeded(v) else v)
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.code to resp.body?.string().orEmpty()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Opens a fast, strictly time-boxed HTTP session (`<= 9s`) for sub-hosts (`mid.yonaplay.net`,
     * `dotplay.net`, etc.), never blocking player startup on long WebView or proxy crawls.
     */
    suspend fun openProtectedSubHost(
        url: String,
        referer: String? = null
    ): Pair<ProtectedHttpSession, String>? = withTimeoutOrNull(9_000L) {
        withContext(Dispatchers.IO) {
            val baseHeaders = buildMap {
                put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                put("Upgrade-Insecure-Requests", "1")
                if (!referer.isNullOrBlank()) put("Referer", referer)
            }

            // 1. Direct fast session with isolated sticky CookieJar (~0.4s on residential IPs)
            val localJar = SessionCookieJar()
            val localClient = subHostFastClient.newBuilder()
                .cookieJar(localJar)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS)
                .build()
            val directSess = ProtectedHttpSession(localClient, localJar)
            directSess.get(url, baseHeaders)?.let { (code, html) ->
                if (code == 200 && html.isNotBlank() && !isCloudflareChallenge(code, html) && !html.contains("/error.php")) {
                    return@withContext directSess to html
                }
            }

            // 2. Fast bounded sticky proxy fallback (<= 5s) when sub-host challenges direct IP
            ensureWorkingProxies(mainUrl, minRequired = 1, maxBudgetMs = 4_500L)
            for (spec in workingProxies.take(3)) {
                val pClient = getOrCreateProxySession(spec) ?: continue
                val proxySess = ProtectedHttpSession(pClient.client, pClient.jar)
                proxySess.get(url, baseHeaders)?.let { (code, html) ->
                    if (code == 200 && html.isNotBlank() && !isCloudflareChallenge(code, html) && !html.contains("/error.php")) {
                        markProxySuccess(spec)
                        return@withContext proxySess to html
                    }
                }
            }

            null
        }
    }

    // ── General Sub-Host HTTP Helpers (Fast Time-Boxed) ──────────────────────

    suspend fun httpGet(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        clientOverride: OkHttpClient? = null
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(encodeUrlIfNeeded(url))
                .header("User-Agent", userAgent)
                .header("Accept", extraHeaders["Accept"] ?: "*/*")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", encodeUrlIfNeeded(referer))
            }
            for ((k, v) in extraHeaders) {
                reqBuilder.header(k, v)
            }
            (clientOverride ?: subHostFastClient).newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun httpGetWithHeaders(
        url: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Triple<Int, okhttp3.Headers, String>? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(encodeUrlIfNeeded(url))
                .header("User-Agent", userAgent)
                .header("Accept", extraHeaders["Accept"] ?: "*/*")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", encodeUrlIfNeeded(referer))
            }
            for ((k, v) in extraHeaders) {
                reqBuilder.header(k, v)
            }
            subHostFastClient.newCall(reqBuilder.build()).execute().use { resp ->
                Triple(resp.code, resp.headers, resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun httpPostJson(
        url: String,
        jsonBody: String,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val reqBuilder = Request.Builder()
                .url(encodeUrlIfNeeded(url))
                .post(body)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", encodeUrlIfNeeded(referer))
            }
            for ((k, v) in extraHeaders) {
                reqBuilder.header(k, v)
            }
            subHostFastClient.newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun httpPostForm(
        url: String,
        fields: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        clientOverride: OkHttpClient? = null
    ): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder()
            for ((k, v) in fields) formBuilder.add(k, v)
            val reqBuilder = Request.Builder()
                .url(encodeUrlIfNeeded(url))
                .post(formBuilder.build())
                .header("User-Agent", userAgent)
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", encodeUrlIfNeeded(referer))
            }
            for ((k, v) in extraHeaders) {
                reqBuilder.header(k, v)
            }
            (clientOverride ?: subHostFastClient).newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads up to the first 16 bytes + Content-Type / Content-Disposition of a download link
     * to reject `.zip` (`PK\x03\x04`), `.rar` (`Rar!`), `.7z`, or HTML error pages before
     * emitting a video `ExtractorLink` to ExoPlayer.
     */
    suspend fun isPlayableDirectMedia(
        url: String,
        referer: String? = null,
        cookie: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val lowerPath = url.substringBefore('?').lowercase()
        if (lowerPath.endsWith(".zip") ||
            lowerPath.endsWith(".rar") ||
            lowerPath.endsWith(".7z") ||
            lowerPath.endsWith(".tar") ||
            lowerPath.endsWith(".gz")
        ) {
            return@withContext false
        }

        try {
            val reqBuilder = Request.Builder()
                .url(encodeUrlIfNeeded(url))
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-31")
            if (!referer.isNullOrBlank()) reqBuilder.header("Referer", encodeUrlIfNeeded(referer))
            if (!cookie.isNullOrBlank()) reqBuilder.header("Cookie", cookie)

            subHostFastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299) return@withContext false
                val cDisp = resp.header("Content-Disposition").orEmpty().lowercase()
                if (cDisp.contains(".zip") || cDisp.contains(".rar") || cDisp.contains(".7z")) {
                    return@withContext false
                }
                val cType = resp.header("Content-Type").orEmpty().lowercase()
                if (cType.contains("application/zip") ||
                    cType.contains("application/x-rar") ||
                    cType.contains("text/html")
                ) {
                    return@withContext false
                }
                val stream = resp.body?.byteStream() ?: return@withContext false
                val magic = ByteArray(8)
                val read = stream.read(magic)
                if (read >= 4) {
                    // ZIP: PK\x03\x04 (0x50 0x4B 0x03 0x04)
                    if (magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                    ) {
                        return@withContext false
                    }
                    // RAR: Rar! (0x52 0x61 0x72 0x21)
                    if (magic[0] == 0x52.toByte() && magic[1] == 0x61.toByte() &&
                        magic[2] == 0x72.toByte() && magic[3] == 0x21.toByte()
                    ) {
                        return@withContext false
                    }
                    // HTML: <!DO or <htm
                    val ascii = String(magic, 0, read, Charsets.ISO_8859_1).trimStart().lowercase()
                    if (ascii.startsWith("<!do") || ascii.startsWith("<htm")) {
                        return@withContext false
                    }
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}

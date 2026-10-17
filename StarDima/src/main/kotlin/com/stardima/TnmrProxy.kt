package com.stardima

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** JNI bridge to the native libbrowserfetch.so (dlopens libcurl-impersonate). */
object BrowserFetch {
    external fun setNativePath(libPath: String, caPath: String, cxxPath: String): Boolean
    external fun fetch(
        url: String,
        referer: String?,
        ua: String,
        timeoutSec: Int,
        headers: Array<String>
    ): ByteArray?

    external fun lastStatus(): Int
    external fun lastHttp(): Int
    external fun lastError(): String?
    external fun version(): String?
}

/**
 * Local HTTP proxy (127.0.0.1) that answers HLS playlist/segment/key requests
 * with bytes fetched upstream through the native browser-TLS client, so the
 * player never touches tnmr and tnmr sees a chrome TLS fingerprint.
 *
 * The returned master URL is localhost; every URI inside the playlists is
 * rewritten to `http://127.0.0.1:<port>/P<sid>?u=<urlencoded upstream URL>`,
 * which keeps the original token/query intact (URL-encoded) and lets the
 * proxy relay content with a single opaque parameter.
 */
object TnmrProxy {
    private const val TAG = "TnmrProxy"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val nativeLoaded = AtomicBoolean(false)
    private val nativeFailed = AtomicBoolean(false)

    @Volatile
    private var port = 0

    @Volatile
    private var serverUp = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sidCounter = AtomicInteger(0)
    private val sessionReferers = ConcurrentHashMap<Int, String>()

    /** Tries to make the proxy available; returns a playable localhost master URL or null. */
    fun serve(masterUrl: String, referer: String?): String? {
        return try {
            if (nativeFailed.get()) return null
            val context = appContext() ?: return null
            if (!ensureNative(context)) return null
            if (!ensureServer()) return null
            val sid = sidCounter.incrementAndGet()
            val localPrefix = "http://127.0.0.1:$port/P$sid"
            val abs = fixUpstreamHost(masterUrl, referer)
            val (body, ref) = upstreamFetch(abs, hlsReferers(referer)) ?: return null
            if (ref != null) sessionReferers[sid] = ref
            val newBody = if (isPlaylist(body)) rewrite(String(body, StandardCharsets.UTF_8), abs, localPrefix) else null
            if (newBody == null) return null
            Log.i(TAG, "proxy master ready (${newBody.length} chars) on port $port")
            "$localPrefix?u=${enc(abs)}"
        } catch (e: Throwable) {
            Log.i(TAG, "serve failed: $e")
            null
        }
    }

    private fun fixUpstreamHost(url: String, referer: String?): String {
        return url
    }

    /**
     * Candidate Referer values for tnmr.org HLS requests. The embed page
     * 301-redirects lulustream.com -> luluvdo.com, and Chrome then sends the
     * origin-only Referer ("strict-origin-when-cross-origin") for this
     * cross-site CDN fetch, e.g. `https://luluvdo.com/`. Copying that exact
     * value (a full embed path or the wrong sibling domain is rejected with
     * 403) is what makes the CDN accept our request.
     */
    private fun hlsReferers(embed: String?): List<String> =
        buildList {
            val host = try {
                embed?.let { URI(it).host?.lowercase() }
            } catch (_: Throwable) {
                null
            }
            if (host == null || host == "luluvdo.com" || host == "lulustream.com") {
                add("https://luluvdo.com/")
            }
            if (host != null) add("https://$host/")
            if (!embed.isNullOrBlank()) add(embed)
        }.distinct()

    // ------------------------------------------------------------------
    // Server
    // ------------------------------------------------------------------

    private fun ensureServer(): Boolean {
        if (serverUp && port > 0) return true
        return synchronized(this) {
            if (serverUp && port > 0) return true
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16)
                port = s.localPort
                scope.launch { acceptLoop(s) }
                serverUp = true
                Log.i(TAG, "proxy listening on $port")
                true
            } catch (e: Throwable) {
                Log.i(TAG, "server start failed: $e")
                false
            }
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        try {
            while (true) {
                val sock = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                sock.soTimeout = 20_000
                scope.launch { handleSocket(sock) }
            }
        } catch (_: Throwable) {
            // server closed
        }
    }

    private fun handleSocket(sock: Socket) {
        try {
            sock.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input) ?: return
                val first = head.firstOrNull() ?: return
                if (!first.startsWith("GET ")) {
                    respond(s, 400, "text/plain", "method not allowed".toByteArray())
                    return
                }
                val target = first.removePrefix("GET ")
                    .substringBefore(" HTTP")
                    .takeIf { it.startsWith("/P") } ?: run {
                    respond(s, 404, "text/plain", "not found".toByteArray())
                    return
                }

                val prefix = target.substringBefore("?u=")
                val query = target.substringAfter("?u=", missingDelimiterValue = "")
                if (query.isBlank()) {
                    respond(s, 404, "text/plain", "missing u".toByteArray())
                    return
                }
                val sid = prefix.removePrefix("/P").toIntOrNull()
                val upstream = try {
                    URLDecoder.decode(query, "UTF-8")
                } catch (_: Throwable) {
                    respond(s, 400, "text/plain", "bad query".toByteArray())
                    return
                }
                val body = upstreamFetch(upstream, hlsReferers(sid?.let { sessionReferers[it] }))?.first
                if (body == null) {
                    respond(s, 502, "text/plain", "upstream unavailable".toByteArray())
                    return
                }
                val localPrefix = "http://127.0.0.1:$port$prefix"
                val outBytes: ByteArray = if (isPlaylist(body)) {
                    rewrite(String(body, StandardCharsets.UTF_8), upstream, localPrefix)
                        ?.toByteArray(StandardCharsets.UTF_8) ?: body
                } else {
                    body
                }
                respond(s, 200, contentTypeOf(upstream, body), outBytes)
            }
        } catch (e: Throwable) {
            Log.i(TAG, "socket error: $e")
        }
    }

    private fun readHead(input: BufferedInputStream): List<String>? {
        val lines = mutableListOf<String>()
        repeat(64) {
            val line = readLine(input) ?: return lines.takeIf { it.isNotEmpty() }
            if (line.isEmpty()) return lines
            lines.add(line)
        }
        return lines
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun respond(sock: Socket, status: Int, type: String, body: ByteArray) {
        val out = BufferedOutputStream(sock.getOutputStream())
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"
            404 -> "Not Found"; 502 -> "Bad Gateway"; else -> "Status"
        }
        out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
        out.write("Content-Type: $type\r\n".toByteArray())
        out.write("Content-Length: ${body.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private fun contentTypeOf(url: String, body: ByteArray): String {
        if (isPlaylist(body)) return "application/vnd.apple.mpegurl; charset=utf-8"
        val p = url.substringBefore('?').lowercase()
        return when {
            p.endsWith(".ts") -> "video/MP2T"
            p.endsWith(".m4s") -> "video/iso.segment"
            else -> "application/octet-stream"
        }
    }

    // ------------------------------------------------------------------
    // Upstream fetch through native browser-TLS client
    // ------------------------------------------------------------------

    /**
     * Try each candidate Referer in order and return the first upstream byte
     * array that answers 200 together with the Referer that won (kept per
     * session so segment/key requests reuse it). Failures are logged with a
     * small body snippet so the exact nginx rejection reason stays visible.
     */
    private fun upstreamFetch(url: String, referers: List<String>): Pair<ByteArray, String>? =
        synchronized(ALL) {
            for (ref in referers) {
                val bytes = try {
                    // ua="" keeps curl-impersonate's own self-consistent header
                    // set (User-Agent + sec-ch-ua + sec-fetch-*); overriding only
                    // the UA breaks the chrome version consistency and gets 403.
                    BrowserFetch.fetch(url, ref, "", 20, emptyArray())
                } catch (e: Throwable) {
                    Log.i(TAG, "fetch error for ${url.take(120)}: $e")
                    null
                }
                val status = BrowserFetch.lastStatus()
                if (status == 200) {
                    if (bytes != null) {
                        Log.i(TAG, "UPSTREAM_OK[$status] ref=$ref ${url.take(100)}")
                        return Pair(bytes, ref)
                    }
                    break
                }
                val snippet = if (bytes != null && bytes.isNotEmpty()) {
                    String(bytes, 0, minOf(160, bytes.size), StandardCharsets.UTF_8)
                } else {
                    "(empty)"
                }
                Log.i(TAG, "upstream $status ref=$ref ${url.take(100)} body=$snippet [${BrowserFetch.lastError()}]")
            }
            null
        }

    private val ALL = Any()

    // ------------------------------------------------------------------
    // HLS playlist rewriting
    // ------------------------------------------------------------------

    private fun isPlaylist(body: ByteArray): Boolean {
        val head = String(body, 0, minOf(body.size, 16), StandardCharsets.UTF_8)
        return head.startsWith("#EXTM3U")
    }

    /** Replaces every URI in the playlist with a local proxy request. */
    private fun rewrite(text: String, upstream: String, localPrefix: String): String? {
        val master = try {
            URI(upstream)
        } catch (_: Throwable) {
            return null
        }
        val origin = master.scheme + "://" + master.authority
        val baseDir = origin + master.path.substringBeforeLast('/')

        return buildString {
            val escaped = { abs: String -> localPrefix + "?u=" + enc(abs) }
            for (raw in text.lines()) {
                val line = raw.trim()
                append(
                    when {
                        line.isEmpty() || line.startsWith("#EXTM3U") -> raw
                        line.startsWith("#") && line.contains("URI=\"") -> {
                            var out = raw
                            val uriRe = Regex("""URI="([^"]*)"""")
                            for (m in uriRe.findAll(raw)) {
                                val rel = m.groupValues[1]
                                val abs = resolveTo(rel, master, origin, baseDir) ?: rel
                                out = out.replace("URI=\"$rel\"", "URI=\"${escaped(abs)}\"")
                            }
                            out
                        }
                        line.startsWith("#") -> raw
                        else -> {
                            val abs = resolveTo(line, master, origin, baseDir)
                            if (abs == null) raw else escaped(abs)
                        }
                    }
                )
                append('\n')
            }
        }
    }

    private fun resolveTo(rel: String, master: URI, origin: String, baseDir: String): String? =
        try {
            when {
                rel.startsWith("http://") || rel.startsWith("https://") -> rel
                rel.startsWith("//") -> master.scheme + ":" + rel
                rel.startsWith("/") -> origin + rel
                else -> master.resolve(rel).toString()
            }
        } catch (_: Throwable) {
            if (rel.startsWith("http") || rel.startsWith("/")) null
            else "$baseDir/$rel"
        }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    // ------------------------------------------------------------------
    // Native library loading
    // ------------------------------------------------------------------

    private fun ensureNative(context: Context): Boolean {
        if (nativeLoaded.get()) return true
        if (nativeFailed.get()) return false
        return synchronized(this) {
            if (nativeLoaded.get()) return true
            if (nativeFailed.get()) return false
            try {
                val abi = findAbi() ?: throw IllegalStateException("unsupported abi")
                val dir = File(context.filesDir, "native-$abi").apply { mkdirs() }
                val wrapSo = File(dir, "libbrowserfetch.so")
                val curlSo = File(dir, "libcurl_impersonate.so")
                val cxxSo = File(dir, "libc++_shared.so")
                val caPem = File(dir, "cacert.pem")
                extractAsset(context, "native/$abi/libbrowserfetch.so", wrapSo)
                extractAsset(context, "native/$abi/libcurl_impersonate.so", curlSo)
                extractAsset(context, "native/$abi/libc++_shared.so", cxxSo)
                extractAsset(context, "native/cacert.pem", caPem)
                System.load(wrapSo.absolutePath)
                val ok = BrowserFetch.setNativePath(
                    curlSo.absolutePath, caPem.absolutePath, cxxSo.absolutePath
                )
                if (!ok) throw IllegalStateException("setNativePath: ${BrowserFetch.lastError()}")
                nativeLoaded.set(true)
                Log.i(TAG, "native loaded: ${BrowserFetch.version()}")
                true
            } catch (e: Throwable) {
                nativeFailed.set(true)
                Log.i(TAG, "native load failed: $e")
                false
            }
        }
    }

    private fun findAbi(): String? {
        val supported = Build.SUPPORTED_ABIS ?: emptyArray()
        for (abi in supported) {
            when (abi) {
                "arm64-v8a" -> return "arm64-v8a"
                "armeabi-v7a" -> return "armeabi-v7a"
            }
        }
        return null
    }

    /**
     * Resolve the host Application context without linking against any
     * CloudStream API that may be absent in the installed app version.
     */
    private fun appContext(): Context? {
        try {
            val cls = Class.forName("com.lagradost.api.ContextHelper_jvmKt")
            val r = cls.getDeclaredMethod("getContext").invoke(null)
            if (r is Context) return r
        } catch (_: Throwable) {
        }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val r = at.getDeclaredMethod("currentApplication").invoke(null)
            if (r is Context) return r
        } catch (_: Throwable) {
        }
        return null
    }

    private fun extractAsset(context: Context, assetPath: String, target: File) {
        if (target.exists() && target.length() > 0) return
        val input = resourceStream(assetPath) ?: context.assets.open(assetPath)
        input.use { copyTo(it, target) }
    }

    /**
     * The plugin is loaded as a `.cs3` zip through a PathClassLoader, so native
     * blobs shipped inside that zip are readable as classpath resources.
     */
    private fun resourceStream(path: String): InputStream? =
        TnmrProxy::class.java.classLoader?.getResourceAsStream(path)

    private fun copyTo(input: InputStream, target: File) {
        BufferedOutputStream(target.outputStream()).use { out ->
            input.copyTo(out)
        }
    }
}
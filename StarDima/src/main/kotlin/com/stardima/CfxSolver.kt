package com.stardima

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Bounded, fail-closed Cloudflare helper for the hosts that front their embed
 * pages with a JS challenge (luluvdo.com, savefiles.com, ...).
 *
 * The current CloudStream runtime no longer ships a working WebView resolver,
 * so we drive a hidden WebView ourselves to earn the challenge cookies. It is
 * used ONLY as a fallback after the plain OkHttp path fails, and every failure
 * point resumes with `null` so the caller simply falls through to its existing
 * behaviour (nothing crashes, nothing hangs).
 *
 * The WebView is configured with the same desktop Chrome User-Agent the OkHttp
 * requests use, because `cf_clearance` is bound to the UA that solved the
 * challenge; with mismatched UAs Cloudflare silently rejects the cookie.
 */
object CfxSolver {
    private const val TAG = "StarDimaCfx"
    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 250L
    private const val STABLE_MS = 1_200L
    private const val CFX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var currentActivity: Activity? = null

    private val callbacksAttached = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    /** Call once from [StarDimaPlugin.load]. Safe to call many times. */
    fun attach(context: Context) {
        appContext = context.applicationContext ?: context
        if (callbacksAttached.compareAndSet(false, true)) {
            try {
                (appContext as? Application)?.registerActivityLifecycleCallbacks(
                    object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(activity: Activity) {
                            currentActivity = activity
                        }

                        override fun onActivityPaused(activity: Activity) {
                            if (currentActivity === activity) currentActivity = null
                        }

                        override fun onActivityDestroyed(activity: Activity) {
                            if (currentActivity === activity) currentActivity = null
                        }

                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                        override fun onActivityStarted(activity: Activity) = Unit
                        override fun onActivityStopped(activity: Activity) = Unit
                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    }
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Loads [url] in a hidden WebView until the Cloudflare challenge clears and
     * returns the accumulated cookies for the domain as a `Cookie` header, or
     * `null` on timeout/failure. Never throws.
     */
    suspend fun cookiesFor(url: String): String? {
        val ctx = appContext ?: return null
        if (!busy.compareAndSet(false, true)) return null
        try {
            return suspendCoroutine { cont ->
                Handler(Looper.getMainLooper()).post {
                    val finished = AtomicBoolean(false)
                    val pollHandler = Handler(Looper.getMainLooper())
                    lateinit var poll: Runnable
                    lateinit var finishTimeout: Runnable

                    val rootView: ViewGroup? = try {
                        currentActivity
                            ?.takeIf { !it.isFinishing }
                            ?.findViewById<ViewGroup>(android.R.id.content)
                    } catch (_: Throwable) {
                        null
                    }

                    val webView: WebView? = try {
                        WebView(rootView?.context ?: ctx).apply {
                            alpha = 0f
                            translationX = 5000f
                            isFocusable = false
                            isFocusableInTouchMode = false
                            isClickable = false
                            settings.apply {
                                userAgentString = CFX_UA
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        }
                    } catch (_: Throwable) {
                        null
                    }

                    val finish: (String?) -> Unit = { cookies ->
                        if (finished.compareAndSet(false, true)) {
                            pollHandler.post {
                                try {
                                    pollHandler.removeCallbacks(finishTimeout)
                                } catch (_: Throwable) {
                                }
                                try {
                                    pollHandler.removeCallbacks(poll)
                                } catch (_: Throwable) {
                                }
                                try {
                                    rootView?.removeView(webView)
                                    webView?.destroy()
                                } catch (_: Throwable) {
                                }
                                cont.resume(cookies)
                            }
                        }
                    }

                    if (webView == null) {
                        finish(null)
                        return@post
                    }
                    webView.webViewClient = WebViewClient()

                    try {
                        rootView?.addView(
                            webView,
                            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        )
                    } catch (_: Throwable) {
                    }

                    finishTimeout = Runnable { finish(null) }
                    pollHandler.postDelayed(finishTimeout, TIMEOUT_MS)

                    var lastUrl: String? = null
                    var lastChange = SystemClock.uptimeMillis()

                    poll = object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            try {
                                webView.evaluateJavascript(
                                    """(function(){
                                        try {
                                            var html = document.documentElement.innerHTML || "";
                                            var challenge = /cf-chl-|challenge-platform|checking your browser|Just a moment|Attention Required/i.test(html);
                                            return location.href + "|" + document.readyState + "|" + challenge;
                                        } catch (e) { return location.href + "|loading|true"; }
                                    })()"""
                                ) { res ->
                                    if (finished.get()) return@evaluateJavascript
                                    try {
                                        val clean = res?.trim('"')?.replace("\\\"", "\"") ?: ""
                                        val parts = clean.split("|")
                                        if (parts.size < 3) {
                                            pollHandler.postDelayed(poll, POLL_MS)
                                            return@evaluateJavascript
                                        }
                                        val now = SystemClock.uptimeMillis()
                                        if (parts[0] != lastUrl) {
                                            lastUrl = parts[0]
                                            lastChange = now
                                        }
                                        if (parts[2] == "false" && parts[1] == "complete" && (now - lastChange) > STABLE_MS) {
                                            finish(currentCookies(url))
                                        } else {
                                            pollHandler.postDelayed(poll, POLL_MS)
                                        }
                                    } catch (_: Throwable) {
                                        pollHandler.postDelayed(poll, POLL_MS)
                                    }
                                }
                            } catch (_: Throwable) {
                                pollHandler.postDelayed(poll, POLL_MS)
                            }
                        }
                    }

                    try {
                        webView.loadUrl(url)
                    } catch (_: Throwable) {
                        finish(null)
                    }
                }
            }
        } finally {
            busy.set(false)
        }
    }

    private fun currentCookies(url: String): String? {
        val host = try {
            android.net.Uri.parse(url).host ?: return null
        } catch (_: Throwable) {
            return null
        }
        return try {
            val manager = CookieManager.getInstance()
            val withSlash = manager.getCookie("https://$host/")?.trim()
            val noSlash = manager.getCookie("https://$host")?.trim()
            listOfNotNull(withSlash, noSlash)
                .distinct()
                .joinToString("; ")
                .takeIf { it.isNotBlank() }
                ?.takeIf { it.contains("cf_clearance") || it.contains("__cf_bm") }
        } catch (_: Throwable) {
            null
        }
    }
}
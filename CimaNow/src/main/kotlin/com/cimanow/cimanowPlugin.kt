package com.cimanow
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cimanow.CimaNowProvider

@CloudstreamPlugin
class cimanowPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("CimaNow", Context.MODE_PRIVATE)
        registerMainAPI(CimaNowProvider(prefs))
        openSettings = { ctx ->
            showCookieDialog(ctx, prefs)
        }
    }
}

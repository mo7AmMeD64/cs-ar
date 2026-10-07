package com.dramalive
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.dramalive.DramaLiveProvider

@CloudstreamPlugin
class dramalivePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaLiveProvider())
    }
}

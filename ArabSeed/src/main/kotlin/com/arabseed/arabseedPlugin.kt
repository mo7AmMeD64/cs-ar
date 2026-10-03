package com.arabseed
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.arabseed.ArabSeedProvider

@CloudstreamPlugin
class arabseedPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ArabSeedProvider())
    }
}

package com.ahwak
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.ahwak.AhwakProvider

@CloudstreamPlugin
class ahwakPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AhwakProvider())
    }
}

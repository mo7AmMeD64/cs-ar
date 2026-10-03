package com.anslayer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.anslayer.AnimeSlayerProvider

@CloudstreamPlugin
class anslayerPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeSlayerProvider())
    }
}

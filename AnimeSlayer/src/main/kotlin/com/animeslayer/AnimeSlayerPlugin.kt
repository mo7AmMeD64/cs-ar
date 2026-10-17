package com.animeslayer

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeSlayerPlugin : Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(AnimeSlayerMediafire())

        registerMainAPI(AnimeSlayer())
    }
}

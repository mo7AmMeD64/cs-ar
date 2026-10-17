package com.animeday

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeDayPlugin : Plugin() {
    override fun load(context: Context) {
        AnimeDay.appContext = context.applicationContext ?: context
        registerMainAPI(AnimeDay())
        registerExtractorAPI(GooglePhotosExtractor())
    }
}

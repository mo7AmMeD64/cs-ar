package com.animerift

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeRiftPlugin : Plugin() {
    override fun load(context: Context) {
        // مستخرج الستريم تيب — يحوّل التذكرة إلى الرابط النهائي عند الحاجة.
        registerExtractorAPI(RiftStreamtapeExtractor())

        registerMainAPI(AnimeRift(context))
    }
}

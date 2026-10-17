package com.dramaworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaWorld())
    }
}

package com.egywatch

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EgywatchPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Egywatch())
    }
}

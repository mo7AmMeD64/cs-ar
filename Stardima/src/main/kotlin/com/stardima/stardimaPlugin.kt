package com.stardima
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.stardima.StardimaProvider

@CloudstreamPlugin
class stardimaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StardimaProvider())
    }
}

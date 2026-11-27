package com.ohlive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OhLivePlugin : Plugin() {
    override fun load() {
        registerMainAPI(OhLiveProvider())
    }
}

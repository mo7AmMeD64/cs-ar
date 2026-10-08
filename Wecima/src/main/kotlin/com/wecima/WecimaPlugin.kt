package com.wecima

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class WecimaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(WecimaProvider())
    }
}

package com.faselmeta

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FaselMetaPlugin : Plugin() {
    override fun load() {
        registerMainAPI(FaselMetaProvider())
    }
}

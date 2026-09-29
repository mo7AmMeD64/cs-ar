package com.cinemaplus

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinemaPlusPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinemaPlusProvider())
    }
}

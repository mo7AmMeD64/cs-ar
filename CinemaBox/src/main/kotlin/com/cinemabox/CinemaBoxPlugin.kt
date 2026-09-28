package com.cinemabox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CinemaBoxPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CinemaBoxProvider())
    }
}

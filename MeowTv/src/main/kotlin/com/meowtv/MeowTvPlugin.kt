package com.meowtv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MeowTvPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MeowTvProvider())
    }
}

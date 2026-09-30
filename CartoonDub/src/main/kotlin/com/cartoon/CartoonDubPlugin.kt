package com.cartoon

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CartoonDubPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CartoonDubProvider())
    }
}

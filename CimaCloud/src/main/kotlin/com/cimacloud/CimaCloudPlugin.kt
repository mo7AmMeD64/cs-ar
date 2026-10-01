package com.cimacloud

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CimaCloudPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CimaCloudProvider())
    }
}

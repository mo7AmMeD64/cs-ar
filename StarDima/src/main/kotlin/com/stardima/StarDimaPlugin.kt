package com.stardima

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StarDimaPlugin : Plugin() {
    override fun load(context: Context) {
        CfxSolver.attach(context)

        registerExtractorAPI(StarDimaLuluStreamExtractor())
        registerExtractorAPI(StarDimaLuluvdoExtractor())
        registerExtractorAPI(SaveFilesExtractor())
        registerExtractorAPI(UqloadExtractor())
        registerExtractorAPI(MixdropExtractor())
        registerExtractorAPI(StreamHgExtractor())

        registerMainAPI(StarDima())
    }
}

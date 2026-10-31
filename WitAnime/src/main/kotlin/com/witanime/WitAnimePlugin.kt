package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        WitAnimeNetwork.init(context)
        registerMainAPI(WitAnime())
        registerExtractorAPI(WitAnimeVideasExtractor())
        registerExtractorAPI(WitAnimeVideasFrExtractor())
        registerExtractorAPI(WitAnimeVideaExtractor())
        registerExtractorAPI(WitAnimeMegaExtractor())
        registerExtractorAPI(WitAnimeHgCloudExtractor())
        registerExtractorAPI(WitAnimeYonaPlayExtractor())
        registerExtractorAPI(WitAnimeDotPlayExtractor())
        registerExtractorAPI(WitAnimeMp4UploadExtractor())
        registerExtractorAPI(WitAnimeOkRuExtractor())
        registerExtractorAPI(WitAnimeFourSharedExtractor())
        registerExtractorAPI(WitAnimeGoFileExtractor())
        registerExtractorAPI(WitAnimeWorkUploadExtractor())
        registerExtractorAPI(WitAnimeMediaFireExtractor())
    }
}

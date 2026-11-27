package com.ohlive

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class OhLiveProvider : MainAPI() {
    companion object {
        private const val PRIMARY = "https://mob.ytvplus.buzz/ytvplus"
        private const val MIRROR = "https://ytv-42qp.onrender.com/ytvplus"
        private val FEATURED = listOf("hot_now", "bein_sport", "shahid", "alwan", "arabic_sport")
    }

    override var mainUrl = PRIMARY
    override var name = "OhLIVE"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "home" to "الرئيسية",
        "all" to "كل الأقسام",
    )

    @Volatile private var activeBase = PRIMARY

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        withContext(Dispatchers.IO) {
            val topics = getTopics()
            if (topics.isEmpty()) {
                return@withContext newHomePageResponse(emptyList<HomePageList>(), false)
            }

            val selected = if (request.data == "all") {
                topics
            } else {
                topics.filter { topic -> FEATURED.any { it == topic.id } }
            }

            val lists = selected.map { topic ->
                async {
                    val topicId = topic.id ?: return@async null
                    val channels = getChannels(topicId)
                    if (channels.isEmpty()) return@async null
                    val items = channels.mapNotNull { it.toSearch() }
                    if (items.isEmpty()) null
                    else HomePageList(topic.name?.ifBlank { topicId } ?: topicId, items)
                }
            }.awaitAll().filterNotNull()

            newHomePageResponse(lists, false)
        }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val body = fetch("/search?q=${URLEncoder.encode(q, "UTF-8")}")
            ?: return@withContext emptyList()
        runCatching { parseJson<List<Channel>>(body) }
            .getOrDefault(emptyList())
            .mapNotNull { it.toSearch() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val channel = parseChannelData(url) ?: return null
        val id = channel.id ?: return null
        val title = channel.name?.ifBlank { id } ?: id
        return newLiveStreamLoadResponse(title, "$mainUrl/channel/$id", url) {
            this.posterUrl = channel.poster.secure()
            this.plot = "بث مباشر — $title"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val channel = parseChannelData(data) ?: return@withContext false
        val id = channel.id ?: return@withContext false
        val servers = fetchServers(id)
        if (servers.isEmpty()) return@withContext false

        var found = false
        servers.forEachIndexed { index, (label, info) ->
            val streamUrl = info.httpUrl() ?: return@forEachIndexed
            val headers = info.headersMap()
            val referer = headers.entries
                .firstOrNull { it.key.equals("Referer", true) }
                ?.value
                .orEmpty()

            callback(
                newExtractorLink(
                    source = name,
                    name = label.ifBlank { "سيرفر ${index + 1}" },
                    url = streamUrl,
                    type = info.linkType(streamUrl),
                ) {
                    this.headers = headers
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        found
    }

    private suspend fun getTopics(): List<Topic> {
        val body = fetch("/get-all-topics") ?: return emptyList()
        return runCatching { parseJson<List<Topic>>(body) }.getOrDefault(emptyList())
    }

    private suspend fun getChannels(topic: String): List<Channel> {
        val body = fetch("/channels?topic=${URLEncoder.encode(topic, "UTF-8")}")
            ?: return emptyList()
        return runCatching { parseJson<List<Channel>>(body) }.getOrDefault(emptyList())
    }

    private suspend fun fetchServers(id: String): List<Pair<String, StreamInfo>> {
        val body = fetch("/stream?id_live=${URLEncoder.encode(id, "UTF-8")}")
        val direct = body?.let { parseStreamItems(it) }.orEmpty()
        if (direct.isNotEmpty()) return direct

        val fallback = fetch("/live_id/${URLEncoder.encode(id, "UTF-8")}")
            ?: return emptyList()
        return parseLiveId(fallback)
    }

    private fun parseStreamItems(body: String): List<Pair<String, StreamInfo>> {
        val items = runCatching { parseJson<List<StreamItem>>(body) }
            .getOrDefault(emptyList())
        return items.mapNotNull { item ->
            val info = parseStreamInfo(item.data?.url) ?: return@mapNotNull null
            if (info.httpUrl() == null) return@mapNotNull null
            (item.name ?: item.data?.name ?: "سيرفر") to info
        }
    }

    private fun parseLiveId(body: String): List<Pair<String, StreamInfo>> {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return parseStreamItems(trimmed)

        val response = runCatching { parseJson<LiveIdResponse>(trimmed) }
            .getOrNull() ?: return emptyList()

        val streams = response.streams.orEmpty().mapNotNull { stream ->
            val info = StreamInfo(
                url = stream.url,
                agent = stream.agent,
                headers = stream.headers,
                mediatype = stream.mediatype,
            )
            if (info.httpUrl() == null) return@mapNotNull null
            (stream.serverName ?: "سيرفر") to info
        }
        if (streams.isNotEmpty()) return streams

        val info = parseStreamInfo(response.data?.url) ?: return emptyList()
        if (info.httpUrl() == null) return emptyList()
        return listOf((response.data?.name ?: "سيرفر") to info)
    }

    private fun parseStreamInfo(raw: String?): StreamInfo? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) return StreamInfo(url = text)
        return runCatching { parseJson<StreamInfo>(text) }.getOrNull()
    }

    private suspend fun fetch(path: String): String? {
        val bases = if (activeBase == PRIMARY) {
            listOf(PRIMARY, MIRROR)
        } else {
            listOf(activeBase, PRIMARY, MIRROR).distinct()
        }

        for (base in bases) {
            try {
                val response = app.get(
                    "$base$path",
                    headers = mapOf("User-Agent" to "okhttp/5.1.0"),
                )
                if (response.code in 200..299 && response.text.isNotBlank()) {
                    activeBase = base
                    return response.text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun parseChannelData(raw: String): ChannelData? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        return runCatching { parseJson<ChannelData>(raw.substring(start)) }.getOrNull()
    }

    private fun Channel.toSearch(): SearchResponse? {
        val channelId = id ?: return null
        val title = name?.ifBlank { channelId } ?: channelId
        val payload = ChannelData(channelId, title, poster).toJson()
        return newLiveSearchResponse(title, payload, TvType.Live) {
            this.posterUrl = poster.secure()
        }
    }

    private fun StreamInfo.httpUrl(): String? {
        val raw = url?.trim().orEmpty()
        val fixed = if (raw.startsWith("//")) "https:$raw" else raw
        return fixed.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun StreamInfo.headersMap(): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        this.headers?.forEach { (key, value) ->
            if (key.isNotBlank() && value != null) headers[key] = value.toString()
        }

        val uaKey = headers.keys.firstOrNull { it.equals("User-Agent", true) }
        if (uaKey == null || headers[uaKey].isNullOrBlank()) {
            val agent = this.agent
            if (!agent.isNullOrBlank() && agent != "advanced") headers["User-Agent"] = agent
        }

        val drop = setOf("host", "connection", "accept-encoding", "range")
        headers.keys.removeAll { it.lowercase() in drop }
        return headers
    }

    private fun StreamInfo.linkType(streamUrl: String): ExtractorLinkType {
        val media = mediatype?.lowercase()
        return when {
            media == "dash" || streamUrl.contains(".mpd", true) -> ExtractorLinkType.DASH
            media == "hls" ||
                streamUrl.contains(".m3u8", true) ||
                streamUrl.contains(".m3u", true) ||
                streamUrl.contains(".html", true) ||
                streamUrl.contains(".htm", true) ||
                streamUrl.contains(".css", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
    }

    private fun String?.secure(): String? =
        this?.let {
            if (it.startsWith("http://logo.twoapistack.work/")) {
                it.replaceFirst("http://", "https://")
            } else {
                it
            }
        }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Topic(
        @JsonProperty("id_topic") val id: String? = null,
        @JsonProperty("name_topic") val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Channel(
        @JsonProperty("id_live") val id: String? = null,
        val name: String? = null,
        @JsonProperty("img_url") val poster: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamItem(
        val data: StreamData? = null,
        val name: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamData(
        val url: String? = null,
        val name: String? = null,
        val agent: String? = null,
        val headers: Map<String, Any?>? = null,
        val mediatype: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamInfo(
        val url: String? = null,
        val agent: String? = null,
        val headers: Map<String, Any?>? = null,
        val mediatype: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveIdResponse(
        val streams: List<LiveStream>? = null,
        val data: StreamData? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LiveStream(
        @JsonProperty("server_name") val serverName: String? = null,
        val url: String? = null,
        val agent: String? = null,
        val headers: Map<String, Any?>? = null,
        val mediatype: String? = null,
    )

    data class ChannelData(
        val id: String? = null,
        val name: String? = null,
        val poster: String? = null,
    )
}

package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class YoutubeExtension : ExtensionClient, SearchFeedClient, TrackClient {
    companion object {
        private const val API_SETTING = "piped_api_url"
        private val FALLBACK_APIS = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee",
            "https://pipedapi.tokhmi.xyz"
        )
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private var settings: Settings? = null

    override fun setSettings(settings: Settings) { this.settings = settings }

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingTextInput(
            title = "Piped API URL",
            key = API_SETTING,
            summary = "Optional custom Piped API base URL. Leave blank to use fallback instances.",
            defaultValue = ""
        )
    )

    private fun apiBases(): List<String> {
        val custom = settings?.getString(API_SETTING)?.trim()?.trimEnd('/')
        return (listOfNotNull(custom?.takeIf { it.isNotBlank() }) + FALLBACK_APIS).distinct()
    }

    private suspend fun getJson(path: String): JSONObject {
        var lastError: Throwable? = null
        for (base in apiBases()) {
            try {
                val request = Request.Builder().url(base + path)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json").build()
                http.newCall(request).await().use { response ->
                    if (!response.isSuccessful) error("Piped API HTTP ${response.code}")
                    val body = response.body?.string() ?: error("Empty Piped response")
                    return JSONObject(body)
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("All Piped API instances failed. Check network or set a custom API URL.", lastError)
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) return emptyList<Shelf>().toFeed()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response = getJson("/search?q=$encoded&filter=music_songs")
        val items = response.optJSONArray("items")
        val tracks = buildList {
            if (items != null) for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val itemUrl = item.optString("url")
                val videoId = item.optString("videoId").ifBlank {
                    item.optString("id").ifBlank { itemUrl.substringAfter("v=", "").substringBefore("&") }
                }
                if (videoId.isBlank() || videoId == "null") continue
                val title = item.optString("title").ifBlank { "Unknown title" }
                val artist = item.optString("uploaderName").ifBlank {
                    item.optString("uploader").ifBlank { "Unknown artist" }
                }
                val thumbnail = item.optString("thumbnail").takeIf { it.startsWith("http") }
                val durationMs = item.optLong("duration", 0L).coerceAtLeast(0L) * 1000L
                add(Track(
                    id = videoId,
                    title = title,
                    type = Track.Type.Song,
                    artists = listOf(Artist(id = artist, name = artist)),
                    duration = durationMs,
                    cover = thumbnail?.toImageHolder(),
                    subtitle = artist,
                    streamables = listOf(Streamable.server(videoId, 1, "YouTube audio")),
                    extras = mapOf("source" to "piped")
                ))
            }
        }
        return listOf<Shelf>(Shelf.Lists.Tracks(
            id = "youtube_search_${query.hashCode()}",
            title = "YouTube Music results",
            list = tracks
        )).toFeed()
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track =
        track.copy(streamables = listOf(Streamable.server(track.id, 1, "YouTube audio")))

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val response = getJson("/streams/${streamable.id}")
        val streams = response.optJSONArray("audioStreams") ?: error("No audio streams returned")
        data class AudioOption(val url: String, val bitrate: Int, val mimeType: String)
        val options = buildList {
            for (i in 0 until streams.length()) {
                val item = streams.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val mime = item.optString("mimeType")
                if (!url.startsWith("http") || !mime.startsWith("audio/")) continue
                add(AudioOption(url, item.optInt("bitrate", 0), mime))
            }
        }
        val selected = options.maxByOrNull { it.bitrate } ?: error("No compatible audio stream found")
        val source = Streamable.Source.Http(
            request = NetworkRequest(url = selected.url, headers = mapOf("User-Agent" to USER_AGENT)),
            type = Streamable.SourceType.Progressive,
            quality = selected.bitrate,
            title = "${selected.mimeType} • ${selected.bitrate} bps",
            isVideo = false
        )
        return Streamable.Media.Server(listOf(source), merged = false)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null
}

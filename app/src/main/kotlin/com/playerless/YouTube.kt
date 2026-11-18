package com.playerless

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// A YouTube failure the player shouldn't retry, with a message for the on-screen log.
class YtException(message: String) : IOException(message)

// YouTube Music's own API, as its web app and the VR app call it. Ported from Convx's innertube.
// Songs from here have ids and URLs "yt:<videoId>"; YtStream turns those into streams at play time.
object YouTube {
    const val PREFIX = "yt:"
    const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private const val ORIGIN = "https://music.youtube.com"
    private const val SONGS_ONLY = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    private val COVER_SIZE = Regex("=w\\d+-h\\d+")
    private val DURATION = Regex("\\d+(:\\d{2}){1,2}")
    private val VISITOR = Regex("\"(Cg[ts][A-Za-z0-9%_-]{20,})\"")

    enum class Client(
        val clientName: String,
        val version: String,
        val id: String,
        val userAgent: String,
        val api: String,
    ) {
        WEB_REMIX("WEB_REMIX", "1.20260213.01.00", "67", WEB_UA, "$ORIGIN/youtubei/v1/"),

        // Answers with plain stream URLs, nothing to decipher; YouTube doesn't always let it.
        ANDROID_VR(
            "ANDROID_VR",
            "1.43.32",
            "28",
            "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; Quest 3; " +
                "Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            "https://www.youtube.com/youtubei/v1/",
        ),
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    }

    fun isYouTube(song: Song) = song.id.startsWith(PREFIX)

    // YouTube's id for this anonymous visitor. Streams and tokens are tied to it, so it's kept.
    // Blocking the first time.
    fun visitorData(): String {
        prefs.getString("visitor", null)?.let { return it }
        val page = Http.get("$ORIGIN/sw.js_data", mapOf("User-Agent" to WEB_UA))
        val visitor = VISITOR.find(page)?.groupValues?.get(1) ?: throw YtException("YouTube non ha dato un visitorData")
        prefs.edit().putString("visitor", visitor).apply()
        return visitor
    }

    // Blocking: call off the main thread.
    fun search(query: String): List<Song> {
        val body = JSONObject()
            .put("context", context(Client.WEB_REMIX, prefs.getString("visitor", null)))
            .put("query", query)
            .put("params", SONGS_ONLY)
        val json = JSONObject(Http.post("${Client.WEB_REMIX.api}search?prettyPrint=false", body.toString(), headers(Client.WEB_REMIX, null)))

        // A search hands out a visitor id too, which saves fetching one at the first play.
        val visitor = json.optJSONObject("responseContext")?.optString("visitorData").orEmpty()
        if (visitor.isNotEmpty() && !prefs.contains("visitor")) prefs.edit().putString("visitor", visitor).apply()

        val sections = json.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return emptyList()
        val songs = ArrayList<Song>()
        for (i in 0 until sections.length()) {
            val items = sections.optJSONObject(i)?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")?.let(::parseSong)?.let(songs::add)
            }
        }
        return songs
    }

    // Blocking. The answer to "how do I play this video", streams included.
    fun player(client: Client, videoId: String, visitor: String, signatureTimestamp: Int?): JSONObject {
        val body = JSONObject()
            .put("context", context(client, visitor))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (signatureTimestamp != null) {
            body.put(
                "playbackContext",
                JSONObject().put("contentPlaybackContext", JSONObject().put("signatureTimestamp", signatureTimestamp)),
            )
        }
        return JSONObject(Http.post("${client.api}player?prettyPrint=false", body.toString(), headers(client, visitor)))
    }

    private fun context(client: Client, visitor: String?): JSONObject {
        val info = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", client.version)
            .put("hl", "it")
            .put("gl", "IT")
        if (visitor != null) info.put("visitorData", visitor)
        if (client == Client.ANDROID_VR) {
            info.put("deviceMake", "Oculus")
                .put("deviceModel", "Quest 3")
                .put("osName", "Android")
                .put("osVersion", "12")
                .put("androidSdkVersion", 32)
        }
        return JSONObject().put("client", info)
    }

    private fun headers(client: Client, visitor: String?) = buildMap {
        put("Content-Type", "application/json")
        put("User-Agent", client.userAgent)
        put("X-YouTube-Client-Name", client.id)
        put("X-YouTube-Client-Version", client.version)
        put("X-Origin", ORIGIN)
        put("Referer", "$ORIGIN/")
        if (visitor != null) put("X-Goog-Visitor-Id", visitor)
    }

    private fun parseSong(item: JSONObject): Song? {
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (videoId.isEmpty()) return null
        val columns = item.optJSONArray("flexColumns")
        val title = runs(columns, 0)?.optJSONObject(0)?.optString("text").orEmpty()
        if (title.isEmpty()) return null

        // "Artist • Album • 3:20": the artists are the runs that link to an artist page.
        val info = runs(columns, 1) ?: JSONArray()
        val runs = (0 until info.length()).mapNotNull { info.optJSONObject(it) }
        val artists = runs.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }.map { it.optString("text") }
        val artist = artists.joinToString(", ").ifEmpty { runs.firstOrNull()?.optString("text").orEmpty() }

        val thumbnails = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val image = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()

        return Song(
            id = PREFIX + videoId,
            title = title,
            artist = artist,
            // The size is in the URL; 150 matches what JioSaavn gives for rows.
            image = image.replace(COVER_SIZE, "=w150-h150"),
            url = PREFIX + videoId,
            seconds = runs.lastOrNull()?.optString("text")?.let(::seconds) ?: 0,
        )
    }

    // "3:20" or "1:02:03", the last thing in the info line.
    private fun seconds(text: String): Int? =
        if (DURATION.matches(text)) text.split(':').fold(0) { total, part -> total * 60 + part.toInt() } else null

    private fun runs(columns: JSONArray?, index: Int): JSONArray? = columns?.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")

    private fun pageType(run: JSONObject): String? = run.optJSONObject("navigationEndpoint")
        ?.optJSONObject("browseEndpoint")?.optJSONObject("browseEndpointContextSupportedConfigs")
        ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
}

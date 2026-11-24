package com.playerless

import android.content.Context
import android.content.SharedPreferences
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

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
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")
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
        val visitor = VISITOR.find(page)?.groupValues?.get(1) ?: throw YtException(Lang.get(R.string.yt_no_visitor))
        prefs.edit().putString("visitor", visitor).apply()
        return visitor
    }

    // An artist as songs credit them: their YouTube Music page and their name.
    class ArtistRef(val id: String, val name: String)

    // A search's songs, and the artist the query names, when it names one exactly.
    class Found(val songs: List<Song>, val artist: ArtistRef?)

    // An album or a single on an artist's page. year is empty when YouTube doesn't say.
    class AlbumRef(val id: String, val title: String, val year: String, val image: String)

    // A row of albums on an artist's page, under YouTube's own title for it, e.g. "Singoli ed EP".
    class Shelf(val title: String, val albums: List<AlbumRef>)

    class ArtistPage(val id: String, val name: String, val image: String, val shelves: List<Shelf>)

    // Artist pages and albums seen in this run, so going back and forth asks YouTube once.
    private val pages = LruCache<String, Any>(24)

    // Blocking: call off the main thread.
    fun search(query: String): List<Song> = find(query).songs

    // Blocking. The artist is the one whose name is the whole query, as the songs credit them; when
    // two artists share the name, the one most of the songs are by.
    fun find(query: String): Found {
        val body = JSONObject()
            .put("context", context(Client.WEB_REMIX, prefs.getString("visitor", null)))
            .put("query", query)
            .put("params", SONGS_ONLY)
        val json = JSONObject(Http.post("${Client.WEB_REMIX.api}search?prettyPrint=false", body.toString(), headers(Client.WEB_REMIX, null)))

        // A search hands out a visitor id too, which saves fetching one at the first play.
        val visitor = json.optJSONObject("responseContext")?.optString("visitorData").orEmpty()
        if (visitor.isNotEmpty() && !prefs.contains("visitor")) prefs.edit().putString("visitor", visitor).apply()
        return parseFind(json, query)
    }

    // The parsing below takes YouTube's answers as they come and asks nothing of the network, so it
    // can be checked against saved answers.
    internal fun parseFind(json: JSONObject, query: String): Found {
        val sections = json.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents") ?: return Found(emptyList(), null)
        val wanted = Match.normalize(query).replace(" ", "")
        val credits = LinkedHashMap<String, Int>()
        val names = HashMap<String, String>()
        val songs = ArrayList<Song>()
        for (i in 0 until sections.length()) {
            val items = sections.optJSONObject(i)?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseSong(item)?.let(songs::add)
                for (run in artistRuns(runs(item.optJSONArray("flexColumns"), 1))) {
                    val name = run.optString("text")
                    val id = browseOf(run)?.optString("browseId").orEmpty()
                    if (id.isEmpty() || wanted.isEmpty() || Match.normalize(name).replace(" ", "") != wanted) continue
                    credits[id] = (credits[id] ?: 0) + 1
                    names.getOrPut(id) { name }
                }
            }
        }
        // Ties go to the first one credited, as the map keeps its order.
        val artist = credits.entries.maxByOrNull { it.value }?.key?.let { ArtistRef(it, names.getValue(it)) }
        return Found(Match.oneEach(songs), artist)
    }

    fun cachedArtist(id: String): ArtistPage? = pages.get(id) as? ArtistPage

    // Blocking. Every row of albums on the artist's page, whole: the page shows ten of a row and
    // keeps the rest, newest first and up to a hundred, behind the row's "more" link.
    fun artist(id: String): ArtistPage {
        cachedArtist(id)?.let { return it }
        val page = parseArtist(id, browse(JSONObject().put("browseId", id))) { runCatching { grid(it) }.getOrNull() }
        pages.put(id, page)
        return page
    }

    // more: the whole row behind a "more" link, null when it can't be had.
    internal fun parseArtist(id: String, json: JSONObject, more: (JSONObject) -> List<AlbumRef>?): ArtistPage {
        val header = json.optJSONObject("header")?.let {
            it.optJSONObject("musicImmersiveHeaderRenderer") ?: it.optJSONObject("musicVisualHeaderRenderer")
        }
        val sections = json.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        val shelves = ArrayList<Shelf>()
        for (i in 0 until (sections?.length() ?: 0)) {
            val shelf = sections!!.optJSONObject(i)?.optJSONObject("musicCarouselShelfRenderer") ?: continue
            // Songs, videos, playlists and other artists have rows too: only albums are kept.
            var albums = albums(shelf.optJSONArray("contents"))
            if (albums.isEmpty()) continue
            val head = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val link = head?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            if (link != null) more(link)?.takeIf { it.size > albums.size }?.let { albums = it }
            shelves += Shelf(text(head?.optJSONObject("title")), albums)
        }
        // The page's picture is a wide banner; asked for square, the server crops it.
        val image = thumbnail(header?.optJSONObject("foregroundThumbnail") ?: header?.optJSONObject("thumbnail"))
        return ArtistPage(id, text(header?.optJSONObject("title")), image, shelves)
    }

    fun cachedAlbum(id: String): List<Song>? {
        @Suppress("UNCHECKED_CAST")
        return pages.get(id) as? List<Song>
    }

    // Blocking. An album's songs in order, each with the album's cover. Songs YouTube greys out,
    // which have no video, are left out.
    fun album(id: String): List<Song> {
        cachedAlbum(id)?.let { return it }
        val songs = parseAlbum(browse(JSONObject().put("browseId", id)))
        if (songs.isNotEmpty()) pages.put(id, songs)
        return songs
    }

    internal fun parseAlbum(json: JSONObject): List<Song> {
        val two = json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
        val header = two?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveHeaderRenderer")
        val cover = thumbnail(header?.optJSONObject("thumbnail"))
        val albumArtist = artistRuns(header?.optJSONObject("straplineTextOne")?.optJSONArray("runs"))
            .joinToString(", ") { it.optString("text") }
        val rows = two?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?.optJSONObject(0)?.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
        return (0 until (rows?.length() ?: 0)).mapNotNull { i ->
            val row = rows!!.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: return@mapNotNull null
            val song = parseSong(row) ?: return@mapNotNull null
            // An album's rows have no picture of their own, and the length in a column apart.
            val length = row.optJSONArray("fixedColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            song.copy(
                artist = song.artist.ifEmpty { albumArtist },
                image = song.image.ifEmpty { cover },
                seconds = song.seconds.takeIf { it > 0 } ?: seconds(text(length)) ?: 0,
            )
        }
    }

    // The full row behind a "more" link: a grid of albums.
    private fun grid(endpoint: JSONObject): List<AlbumRef> {
        val body = JSONObject().put("browseId", endpoint.optString("browseId"))
        endpoint.optString("params").takeIf { it.isNotEmpty() }?.let { body.put("params", it) }
        return parseGrid(browse(body))
    }

    internal fun parseGrid(json: JSONObject): List<AlbumRef> = albums(
        json.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")?.optJSONObject(0)
            ?.optJSONObject("gridRenderer")?.optJSONArray("items"),
    )

    private fun albums(items: JSONArray?): List<AlbumRef> = (0 until (items?.length() ?: 0)).mapNotNull { i ->
        val item = items!!.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer") ?: return@mapNotNull null
        val target = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint") ?: return@mapNotNull null
        if (pageTypeOf(target) != "MUSIC_PAGE_TYPE_ALBUM") return@mapNotNull null
        AlbumRef(
            id = target.optString("browseId").ifEmpty { return@mapNotNull null },
            title = text(item.optJSONObject("title")),
            // "2024", or "Singolo • 2024" in the language asked for.
            year = YEAR.findAll(text(item.optJSONObject("subtitle"))).lastOrNull()?.value.orEmpty(),
            image = thumbnail(item.optJSONObject("thumbnailRenderer")),
        )
    }

    private fun browse(body: JSONObject): JSONObject {
        body.put("context", context(Client.WEB_REMIX, prefs.getString("visitor", null)))
        return JSONObject(Http.post("${Client.WEB_REMIX.api}browse?prettyPrint=false", body.toString(), headers(Client.WEB_REMIX, null)))
    }

    // The largest picture, asked for at the size of a row: 150, as JioSaavn's.
    private fun thumbnail(renderer: JSONObject?): String {
        val thumbnails = renderer?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val url = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
        return url.replace(COVER_SIZE, "=w150-h150")
    }

    private fun text(formatted: JSONObject?): String {
        val runs = formatted?.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun artistRuns(runs: JSONArray?): List<JSONObject> =
        (0 until (runs?.length() ?: 0)).mapNotNull { runs!!.optJSONObject(it) }.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }

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

    // hl: the app's language, which YouTube writes its titles in ("Singles & EPs"). gl: the phone's
    // country, which weighs on what the search ranks first.
    private fun context(client: Client, visitor: String?): JSONObject {
        val info = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", client.version)
            .put("hl", Lang.locale.language.ifEmpty { "en" })
            .put("gl", Locale.getDefault().country.ifEmpty { "US" })
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
            explicit = isExplicit(item.optJSONArray("badges")),
        )
    }

    // The "E" YouTube Music shows next to a title.
    private fun isExplicit(badges: JSONArray?): Boolean = (0 until (badges?.length() ?: 0)).any {
        badges!!.optJSONObject(it)?.optJSONObject("musicInlineBadgeRenderer")?.optJSONObject("icon")
            ?.optString("iconType") == "MUSIC_EXPLICIT_BADGE"
    }

    // "3:20" or "1:02:03", the last thing in the info line.
    private fun seconds(text: String): Int? =
        if (DURATION.matches(text)) text.split(':').fold(0) { total, part -> total * 60 + part.toInt() } else null

    private fun runs(columns: JSONArray?, index: Int): JSONArray? = columns?.optJSONObject(index)
        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")

    private fun browseOf(run: JSONObject): JSONObject? = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")

    private fun pageType(run: JSONObject): String? = browseOf(run)?.let(::pageTypeOf)

    private fun pageTypeOf(browse: JSONObject): String? = browse.optJSONObject("browseEndpointContextSupportedConfigs")
        ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")
}

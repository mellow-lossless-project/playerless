package com.playerless

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// The "Lossless" quality: a song looked up on a Monochrome-style track server, the protocol Mochy
// plays FLAC from: GET /search/tracks?q=…&limit=…, then the file at GET /track/{id}.
// Tested 2026-11-18: Monochrome's own server sends FLAC for some recordings (24 bit / 96 kHz for
// EsDeeKid's "5am") and AAC in MP4 at about 320 kbps for others ("Get Lucky"), whatever quality is
// asked for. So every song is asked about: FLAC wins over every source, AAC only over YouTube's 128.
object Lossless {
    const val DEFAULT_SERVER = "https://tracks.monochrome.st"
    // 16-bit / 44.1 kHz stereo PCM: what FLAC carries before compression.
    const val KBPS = 1411

    // Downloads from the server send it too.
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private const val SEARCH_LIMIT = 8
    private const val MAX_DURATION_DIFF_S = 5
    // A server that didn't answer (or said "too many requests") is left alone for a while, so it
    // costs one song a timeout, not every song.
    private const val DOWN_MS = 3 * 60_000L

    // A recording the server lists but can't send (Fousheé's "Deep End": 403 or 502 after 30 s, on
    // 2026-11-18) is passed over for a while: only that song plays from its own source.
    private const val UNSENDABLE_MS = 30 * 60_000L

    // seconds: the recording's length on the server, 0 when it doesn't say.
    class Found(val url: String, val flac: Boolean, val label: String, val seconds: Int = 0)

    // What the server said about each song, so a song played again doesn't ask again.
    private val NONE = Found("", false, "")
    private val found = ConcurrentHashMap<String, Found>()
    private val unsendable = ConcurrentHashMap<String, Long>()
    @Volatile private var downUntil = 0L
    @Volatile private var server = ""

    // A file from the server broke off while playing: the server rests like one that didn't answer.
    fun failed() {
        downUntil = System.currentTimeMillis() + DOWN_MS
    }

    // Blocking: call off the main thread. Null when the server is down or hasn't the song.
    fun resolve(title: String, artist: String, seconds: Int, log: (String) -> Unit): Found? {
        val base = Settings.losslessServer
        if (base != server) {
            server = base
            found.clear()
            unsendable.clear()
            downUntil = 0
        }
        val now = System.currentTimeMillis()
        if (now < downUntil) return null
        val key = "$title|$artist"
        found[key]?.let { known -> return known.takeIf { it !== NONE } }
        if (now < (unsendable[key] ?: 0)) return null

        log("Cerco la versione lossless")
        // Anything going wrong here (no answer, a web page instead of JSON) must only mean
        // "play the song's own source", never a failed song. The search failing means the server
        // is out, and it rests.
        val match = try {
            search(base, title, artist, seconds)
        } catch (e: Exception) {
            downUntil = now + DOWN_MS
            log("Server lossless non risponde: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        if (match == null) {
            found[key] = NONE
            log("Non c'è sul server lossless")
            return null
        }
        // The file failing is this recording's trouble: the next songs still ask. Except "too many
        // requests", which is about all of them.
        val result = try {
            open(base, match.first, match.second)
        } catch (e: Exception) {
            if (e.message == "HTTP 429") downUntil = now + DOWN_MS else unsendable[key] = now + UNSENDABLE_MS
            log("Il server lossless ce l'ha ma non lo manda: ${e.message ?: e.javaClass.simpleName}")
            return null
        }
        found[key] = result
        if (result === NONE) {
            log("Il server lossless non manda un file audio")
            return null
        }
        return result
    }

    // The id and length in seconds of the best matching recording.
    private fun search(base: String, title: String, artist: String, seconds: Int): Pair<String, Int>? {
        val mainArtist = artist.substringBefore(',').trim()
        val query = "$mainArtist ${core(title)}".trim()
        val text = get("$base/search/tracks?q=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}&limit=$SEARCH_LIMIT")
        val tracks = JSONObject(text).optJSONArray("tracks") ?: return null
        val wantTitle = normalize(core(title))
        val wantArtist = normalize(mainArtist)
        return (0 until tracks.length()).mapNotNull { i ->
            val t = tracks.optJSONObject(i) ?: return@mapNotNull null
            if (t.has("playable") && !t.optBoolean("playable")) return@mapNotNull null
            val id = t.optString("trackId").ifEmpty { t.optString("id") }.ifEmpty { return@mapNotNull null }
            val names = t.optJSONArray("artistNames")
            val artists = normalize((0 until (names?.length() ?: 0)).joinToString(" ") { names!!.optString(it) })
            val got = normalize(core(t.optString("title")))
            // The server gives milliseconds; older answers gave seconds.
            val raw = t.optLong("duration")
            val length = (if (raw > 10_000) raw / 1000 else raw).toInt()
            val titleOk = got == wantTitle || (got.isNotEmpty() && wantTitle.isNotEmpty() && (wantTitle in got || got in wantTitle))
            val artistOk = wantArtist.isEmpty() || wantArtist in artists
            val diff = if (seconds > 0 && length > 0) abs(seconds - length) else 0
            if (titleOk && artistOk && diff <= MAX_DURATION_DIFF_S) Triple(id, length, diff + if (got == wantTitle) 0 else 100) else null
        }.minByOrNull { it.third }?.let { it.first to it.second }
    }

    // The first bytes of the file say what it is; a FLAC's STREAMINFO says how fine.
    private fun open(base: String, id: String, seconds: Int): Found {
        val url = "$base/track/$id"
        val conn = connect(url, mapOf("Range" to "bytes=0-41"))
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            val head = conn.inputStream.use { readUpTo(it, 42) }
            val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
            return when {
                head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "fLaC" -> Found(url, true, flacLabel(head), seconds)
                head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                    val kbps = if (total != null && seconds > 0) " ~${total * 8 / seconds / 1000} kbps" else ""
                    Found(url, false, "AAC$kbps", seconds)
                }
                else -> NONE
            }
        } finally {
            conn.disconnect()
        }
    }

    // "fLaC", a 4-byte block header, then STREAMINFO: after 10 bytes of block and frame sizes come
    // 20 bits of sample rate, 3 of channels - 1, 5 of bits per sample - 1.
    private fun flacLabel(head: ByteArray): String {
        if (head.size < 26) return "FLAC"
        val b = IntArray(18) { head[8 + it].toInt() and 0xff }
        val rate = (b[10] shl 12) or (b[11] shl 4) or (b[12] shr 4)
        val bits = (((b[12] and 0x01) shl 4) or (b[13] shr 4)) + 1
        if (rate !in 8_000..768_000 || bits !in 8..32) return "FLAC"
        return String.format(Locale.ITALY, "FLAC %d bit · %.1f kHz", bits, rate / 1000.0)
    }

    // One read can stop short of what's there.
    private fun readUpTo(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val n = stream.read(buffer, filled, size - filled)
            if (n < 0) break
            filled += n
        }
        return buffer.copyOf(filled)
    }

    private fun get(url: String): String {
        val conn = connect(url, mapOf("Accept" to "application/json"))
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // The song waits on these, so a slow server gives up its turn quickly.
    private fun connect(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 4_000
            useCaches = false
            setRequestProperty("User-Agent", UA)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    // The title without "(feat. …)", "[Remastered]", "(From …)" or " - Radio Edit".
    private fun core(title: String): String =
        title.replace(BRACKETED, "").substringBefore(" - ").trim()

    // As the search compares titles: "Shotta's." and "Shottas" are the same title.
    private fun normalize(text: String): String = Match.normalize(text)

    private val BRACKETED = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
}

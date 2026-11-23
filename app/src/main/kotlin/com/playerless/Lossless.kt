package com.playerless

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

// The "Lossless" quality: a song looked up on a Monochrome-style track server, the protocol Mochy
// plays FLAC from: GET /search/tracks?q=…&limit=…, then the file at GET /track/{id}.
// Tested 2026-11-18: Monochrome's own server sends FLAC for some recordings (24 bit / 96 kHz for
// EsDeeKid's "5am") and AAC in MP4 at about 320 kbps for others ("Get Lucky"), whatever quality is
// asked for. So every song is asked about: FLAC wins over every source, AAC only over YouTube's.
// On 2026-11-23 it had 20 popular songs out of 20, Italian rap included, 18 of them in FLAC.
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
    private const val DOWN_MS = 60_000L

    // Measured 2026-11-23: a search takes 0.6-3.3 s. A file's first bytes come in 0.4-1.5 s when the
    // server has it at hand, but it fetches about a third of them first, and those take 3-17 s. So a
    // lookup gets all that time, while a song about to play waits for it only so long (see resolve):
    // the lookup goes on behind the song, and its answer is kept for the next time.
    private const val CONNECT_MS = 5_000
    private const val SEARCH_READ_MS = 10_000
    private const val FILE_READ_MS = 25_000

    // A recording the server lists but can't send (Fousheé's "Deep End": 403 or 502 after 30 s, on
    // 2026-11-18) is passed over for a while: only that song plays from its own source.
    private const val UNSENDABLE_MS = 30 * 60_000L
    // An answer that isn't audio at all, like an error page, is more likely a passing fault.
    private const val ODD_ANSWER_MS = 5 * 60_000L

    // Answers outlive the app, so a song played again skips the search. A file found is checked
    // again before playing once its answer is FRESH_MS old, since the server may have let it go;
    // "not there" holds for MISSING_MS. The newest KEEP answers are kept.
    private const val FRESH_MS = 20 * 60_000L
    private const val MISSING_MS = 3 * 24 * 3600_000L
    private const val KEEP = 300
    private const val SERVER = "@server"

    // seconds: the recording's length on the server, 0 when it doesn't say.
    class Found(val url: String, val flac: Boolean, val label: String, val seconds: Int = 0)

    // What the server said about a song, and when. A null found: it hasn't the song.
    private class Answer(val found: Found?, val at: Long)

    // How a lookup ended: the file, or what to tell the on-screen log.
    private class Lookup(val found: Found?, val note: String)

    private val answers = ConcurrentHashMap<String, Answer>()
    private val pending = ConcurrentHashMap<String, Future<Lookup>>()
    private val unsendable = ConcurrentHashMap<String, Long>()
    private val lookups = Executors.newCachedThreadPool()
    @Volatile private var downUntil = 0L
    @Volatile private var server = ""
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("lossless", Context.MODE_PRIVATE)
    }

    // A song's file from the server broke off while playing: that song isn't asked about for a
    // while, so playing it again goes straight to its own source, and after that it's checked anew.
    fun failed(title: String, artist: String) {
        val key = "$title|$artist"
        answers[key]?.found?.let { answers[key] = Answer(it, 0) }
        unsendable[key] = System.currentTimeMillis() + UNSENDABLE_MS
    }

    // The song's file on the server, or null when the server hasn't it or can't send it now. waitMs:
    // how long to wait for the server, 0 for as long as the lookup takes. Past it the lookup goes on,
    // and its answer is there the next time. Blocking: call off the main thread.
    fun resolve(title: String, artist: String, seconds: Int, waitMs: Long = 0, log: (String) -> Unit): Found? {
        val (known, task) = begin(title, artist, seconds, log)
        if (task == null) return known
        val result = try {
            if (waitMs > 0) task.get(waitMs, TimeUnit.MILLISECONDS) else task.get()
        } catch (_: TimeoutException) {
            log("Il server lossless è lento: questa volta suono la sorgente del brano")
            return null
        } catch (e: ExecutionException) {
            log("Errore del server lossless: ${e.cause?.message ?: e.cause?.javaClass?.simpleName}")
            return null
        }
        if (result.found == null) log(result.note)
        return result.found
    }

    // Starts looking a song up, without waiting: the song after the one playing, so it starts
    // without the wait. Blocking for a moment the first time (the answers are read from disk).
    fun prefetch(title: String, artist: String, seconds: Int) {
        begin(title, artist, seconds) {}
    }

    // What's known already (a file, or null with no lookup: not there, or not now), else the lookup
    // for it, the one already running if there is one.
    private fun begin(title: String, artist: String, seconds: Int, log: (String) -> Unit): Pair<Found?, Future<Lookup>?> {
        val base = current()
        val key = "$title|$artist"
        val now = System.currentTimeMillis()
        val known = answers[key]
        if (known != null && now - known.at < (if (known.found != null) FRESH_MS else MISSING_MS)) {
            if (known.found == null) log("Non c'è sul server lossless")
            return known.found to null
        }
        if (now < (unsendable[key] ?: 0) || now < downUntil) return null to null
        val before = known?.found
        log(if (before != null) "Controllo il file lossless" else "Cerco la versione lossless")
        return null to pending.computeIfAbsent(key) {
            lookups.submit(Callable { lookup(base, key, title, artist, seconds, before) })
        }
    }

    // before: the file found the last time, which needs no search, only a check that it still comes.
    private fun lookup(base: String, key: String, title: String, artist: String, seconds: Int, before: Found?): Lookup {
        try {
            val url: String
            val length: Int
            if (before != null) {
                url = before.url
                length = before.seconds
            } else {
                // Anything going wrong here (no answer, a web page instead of JSON) must only mean
                // "play the song's own source", never a failed song. The search failing means the
                // server is out, and it rests.
                val match = try {
                    search(base, title, artist, seconds)
                } catch (e: Exception) {
                    downUntil = System.currentTimeMillis() + DOWN_MS
                    return Lookup(null, "Server lossless non risponde: ${e.message ?: e.javaClass.simpleName}")
                }
                if (match == null) {
                    remember(base, key, Answer(null, System.currentTimeMillis()))
                    return Lookup(null, "Non c'è sul server lossless")
                }
                url = "$base/track/${match.first}"
                length = match.second
            }
            // The file failing is this recording's trouble: the next songs still ask. Except "too
            // many requests", which is about all of them. A file found before that fails is looked
            // for anew the next time.
            val found = try {
                open(url, length)
            } catch (e: Exception) {
                if (e.message == "HTTP 429") {
                    downUntil = System.currentTimeMillis() + DOWN_MS
                } else {
                    unsendable[key] = System.currentTimeMillis() + UNSENDABLE_MS
                    if (before != null) forget(base, key)
                }
                return Lookup(null, "Il server lossless ce l'ha ma non lo manda: ${e.message ?: e.javaClass.simpleName}")
            }
            if (found == null) {
                unsendable[key] = System.currentTimeMillis() + ODD_ANSWER_MS
                return Lookup(null, "Il server lossless non manda un file audio")
            }
            remember(base, key, Answer(found, System.currentTimeMillis()))
            return Lookup(found, "")
        } finally {
            pending.remove(key)
        }
    }

    // The server in use. Answers come from the disk the first time, and only for that server: a
    // different one starts from nothing.
    private fun current(): String {
        val base = Settings.losslessServer
        if (base == server) return base
        synchronized(this) {
            if (base != server) {
                answers.clear()
                unsendable.clear()
                downUntil = 0
                if (prefs.getString(SERVER, null) == base) load() else prefs.edit().clear().putString(SERVER, base).apply()
                server = base
            }
        }
        return base
    }

    private fun load() {
        for ((key, value) in prefs.all) {
            if (key == SERVER || value !is String) continue
            val json = try {
                JSONObject(value)
            } catch (_: Exception) {
                continue
            }
            val url = json.optString("u")
            val found = if (url.isEmpty()) null else Found(url, json.optBoolean("f"), json.optString("l"), json.optInt("s"))
            answers[key] = Answer(found, json.optLong("t"))
        }
    }

    // Kept only if the server is still the one asked.
    private fun remember(base: String, key: String, answer: Answer) {
        if (base != server) return
        answers[key] = answer
        val json = JSONObject().put("t", answer.at)
        answer.found?.let { json.put("u", it.url).put("f", it.flac).put("l", it.label).put("s", it.seconds) }
        val edit = prefs.edit().putString(key, json.toString())
        val extra = answers.size - KEEP
        if (extra > 0) {
            answers.entries.sortedBy { it.value.at }.take(extra).forEach {
                answers.remove(it.key)
                edit.remove(it.key)
            }
        }
        edit.apply()
    }

    private fun forget(base: String, key: String) {
        if (base != server) return
        answers.remove(key)
        prefs.edit().remove(key).apply()
    }

    // The id and length in seconds of the best matching recording. The uncensored one comes before
    // its clean edit, which has the same title and length.
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
            val rank = diff + (if (got == wantTitle) 0 else 100) + (if (t.optBoolean("explicit")) 0 else 10)
            if (titleOk && artistOk && diff <= MAX_DURATION_DIFF_S) Triple(id, length, rank) else null
        }.minByOrNull { it.third }?.let { it.first to it.second }
    }

    // The first bytes of the file say what it is; a FLAC's STREAMINFO says how fine. Null when it
    // isn't audio.
    private fun open(url: String, seconds: Int): Found? {
        val conn = connect(url, mapOf("Range" to "bytes=0-41"), FILE_READ_MS)
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
                else -> null
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
        val conn = connect(url, mapOf("Accept" to "application/json"), SEARCH_READ_MS)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun connect(url: String, headers: Map<String, String>, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = readMs
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

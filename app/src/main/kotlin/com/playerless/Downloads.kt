package com.playerless

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Kept free for the phone itself: a download that would leave less doesn't start.
private const val RESERVE_BYTES = 300L * 1024 * 1024

// Connections one file may take: a 100 MB FLAC at the lossless server's ~5 MB each needs 20.
private const val MAX_TRIES = 60
private const val MAX_IDLE_TRIES = 3

// The phone is too full for one more download.
class NoSpace : IOException("not enough space")

// Offline songs live in filesDir/music, listed newest first in index.json. Every file is named .m4a,
// whatever it holds (AAC in MP4, FLAC, Opus in WebM): the player tells them by their first bytes.
object Downloads {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val inProgress = HashSet<String>() // Main thread only.
    private val unsafeChars = Regex("[^A-Za-z0-9_-]")

    // The index, read from disk once and then kept in step with every write: every search reads it.
    @Volatile private var cached: List<Song>? = null

    // The song the player is on, set by the playback service: its file isn't swapped under it.
    @Volatile var playing: String? = null

    // Main thread. While upgradeToLossless runs: songs done, and in all.
    var upgrade: Pair<Int, Int>? = null
        private set

    // What was downloaded in this run: a stream may move to the file only if it's the same bytes.
    private val sources = ConcurrentHashMap<String, String>()

    private lateinit var appContext: Context
    private lateinit var dir: File
    private lateinit var index: AtomicFile

    var onChanged: (() -> Unit)? = null

    fun init(context: Context) {
        if (::dir.isInitialized) return
        appContext = context.applicationContext
        dir = File(appContext.filesDir, "music").apply { mkdirs() }
        index = AtomicFile(File(dir, "index.json"))
        // Whatever a killed process left behind. Queued first, so it can't hit a running download.
        executor.execute { dir.listFiles { file -> file.name.endsWith(".part") }?.forEach { it.delete() } }
    }

    fun sameAsStream(id: String, url: String) = sources[id] == url

    fun isDownloaded(song: Song) = audioFile(song.id).exists()

    fun isDownloading(song: Song) = song.id in inProgress

    // Main thread. A download or the lossless swap is running.
    val busy: Boolean get() = inProgress.isNotEmpty() || upgrade != null

    fun localFile(id: String): File? = audioFile(id).takeIf { it.exists() }

    fun imageFor(song: Song): String {
        val cover = coverFile(song.id)
        return if (cover.exists()) Uri.fromFile(cover).toString() else song.image
    }

    fun list(): List<Song> = cached ?: synchronized(this) { cached ?: read().also { cached = it } }

    // Every word of the query has to appear in the title or the artist, matched as the online search does.
    fun search(query: String): List<Song> {
        val words = Match.words(query)
        return list().filter { Match.hasAll(words, it) }
    }

    // A null bitrate keeps the URL as it is, for streams that don't name theirs.
    fun download(song: Song, kbps: Int?) {
        if (!inProgress.add(song.id)) return
        onChanged?.invoke()
        executor.execute {
            val result = runCatching { fetch(song, kbps) }
            main.post {
                inProgress.remove(song.id)
                // Says what was saved, so a "lossless" download that isn't FLAC doesn't pass for one.
                val message = result.fold(
                    { Lang.get(R.string.download_done, song.title, it) },
                    { Lang.get(if (it is NoSpace) R.string.download_no_space else R.string.download_failed, song.title) },
                )
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                onChanged?.invoke()
            }
        }
    }

    // Main thread. Swaps each downloaded song that isn't FLAC yet for the lossless server's FLAC,
    // where the server has one; the others stay as they are. One at a time, behind any download.
    // progress gets songs done and in all; done gets how many are FLAC now, how many stayed as they
    // were, and whether it stopped short with the phone nearly full. Run by UpgradeService, which
    // keeps the app alive meanwhile. False if one is running.
    fun upgradeToLossless(progress: (done: Int, total: Int) -> Unit, done: (flac: Int, kept: Int, full: Boolean) -> Unit): Boolean {
        if (upgrade != null) return false
        val songs = list()
        upgrade = 0 to songs.size
        onChanged?.invoke()
        executor.execute {
            var flac = 0
            var kept = 0
            var full = false
            fun step(song: Song) {
                if (full) return
                val result = runCatching { toFlac(song) }
                full = result.exceptionOrNull() is NoSpace
                if (result.getOrDefault(false)) flac++ else kept++
                val count = flac + kept
                main.post {
                    upgrade = count to songs.size
                    onChanged?.invoke()
                    progress(count, songs.size)
                }
            }
            // A song playing is left for last, in case the player has moved on by then.
            val later = songs.filter { it.id == playing }
            songs.filterNot { it in later }.forEach(::step)
            later.forEach { if (it.id == playing) kept++ else step(it) }
            main.post {
                upgrade = null
                onChanged?.invoke()
                done(flac, kept, full)
            }
        }
        return true
    }

    // True when the song's file is FLAC now: it already was, or the lossless server had it.
    private fun toFlac(song: Song): Boolean {
        val file = audioFile(song.id)
        if (!file.exists()) return false
        if (isFlac(file)) return true
        // The length of the file on the phone picks the same recording on the server.
        val seconds = song.seconds.takeIf { it > 0 } ?: lengthOf(file)
        val found = Lossless.resolve(song.title, song.artist, seconds) {} ?: return false
        if (!found.flac) return false
        save(found.url, file, headers = mapOf("User-Agent" to Lossless.UA))
        sources.remove(song.id)
        val length = found.seconds.takeIf { it > 0 } ?: seconds
        val kbps = if (length > 0) (file.length() * 8 / length / 1000).toInt() else null
        // In place: the list of downloads keeps its order.
        synchronized(this) { write(list().map { if (it.id == song.id) it.copy(kbps = kbps, seconds = length) else it }) }
        return true
    }

    private fun isFlac(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && String(head, Charsets.US_ASCII) == "fLaC"
        }
    } catch (_: IOException) {
        false
    }

    // A downloaded file's length in seconds, 0 when it can't be read.
    private fun lengthOf(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    fun delete(song: Song) {
        synchronized(this) { write(list().filterNot { it.id == song.id }) }
        audioFile(song.id).delete()
        coverFile(song.id).delete()
        onChanged?.invoke()
    }

    // kbps Lossless.KBPS: the lossless server's file. When it doesn't have the song or doesn't answer,
    // the best the song's own source has, rather than no download at all. Returns what was saved.
    private fun fetch(song: Song, kbps: Int?): String {
        if (song.image.isNotEmpty()) runCatching { save(Covers.big(song.image), coverFile(song.id)) }
        val lossless = kbps == Lossless.KBPS
        if (lossless) fetchLossless(song)?.let { return it }

        // A YouTube song has one rendition, the one it streams, so the stream may move to the file.
        val youtube = if (YouTube.isYouTube(song)) YtStream.resolve(song.url.removePrefix(YouTube.PREFIX)) {} else null
        val quality = kbps?.takeIf { it != Lossless.KBPS }
        val url = when {
            youtube != null -> youtube.url
            quality != null -> SaavnApi.withQuality(song.url, quality)
            else -> song.url
        }
        save(url, audioFile(song.id), ranged = youtube != null)
        sources[song.id] = if (youtube != null) song.url else url
        val saved = youtube?.kbps ?: quality ?: SaavnApi.kbpsOf(url)
        remember(song.copy(kbps = saved))
        val what = when {
            youtube != null -> "YouTube ${youtube.codec} ${youtube.kbps} kbps"
            saved != null -> "JioSaavn $saved kbps"
            else -> "JioSaavn"
        }
        return if (lossless) Lang.get(R.string.download_not_lossless, what) else what
    }

    // Null when the lossless server hasn't the song or doesn't answer. A file it has but can't get
    // across, even piece by piece, fails the download: lossless was asked for, and a lesser copy
    // saved in its place would then play instead of the stream, which does come in lossless.
    private fun fetchLossless(song: Song): String? {
        val found = Lossless.resolve(song.title, song.artist, song.seconds) {} ?: return null
        val file = audioFile(song.id)
        save(found.url, file, headers = mapOf("User-Agent" to Lossless.UA))
        // Never the bytes the song streams: a song playing doesn't move onto this file mid-way.
        sources.remove(song.id)
        // What the file really is, FLAC or not: its size over its length.
        val length = found.seconds.takeIf { it > 0 } ?: song.seconds
        remember(song.copy(kbps = if (length > 0) (file.length() * 8 / length / 1000).toInt() else null))
        return if (found.flac) found.label else Lang.get(R.string.not_flac, found.label)
    }

    // Keeps the online URL, so the song still plays anywhere it's saved if the file is deleted.
    private fun remember(saved: Song) {
        synchronized(this) { write(listOf(saved) + list().filterNot { it.id == saved.id }) }
    }

    // Through a .part file, so the target only ever exists complete. A file comes in as many pieces as
    // it takes: the lossless server sends a big FLAC a few MB per connection, pausing for seconds (on
    // 2026-11-24 a 40 MB one came in 7 pieces, and no download of it in one piece got through), and
    // any connection may drop. So each try asks, with a range, only for what's still missing, and
    // the download fails after MAX_IDLE_TRIES tries in a row that brought nothing. YouTube throttles
    // a request without a range to about twice the song's bitrate; with one, even open-ended, it
    // doesn't.
    private fun save(url: String, target: File, ranged: Boolean = false, headers: Map<String, String> = emptyMap()) {
        val part = File(dir, "${target.name}.part")
        part.delete()
        try {
            var total = -1L
            var whole = false
            var failure: IOException? = null
            var idle = 0
            for (attempt in 1..MAX_TRIES) {
                val have = part.length()
                failure = null
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 15_000
                    conn.useCaches = false
                    headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                    if (ranged || have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("HTTP $code")
                    // A server that ignores the range sends the whole file again: it starts over.
                    val append = code == 206 && have > 0
                    val length = conn.contentLengthLong
                    total = if (code == 206) {
                        conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: if (length >= 0) have + length else -1
                    } else {
                        length
                    }
                    val missing = total - (if (append) have else 0)
                    if (dir.usableSpace - missing.coerceAtLeast(0) < RESERVE_BYTES) throw NoSpace()
                    conn.inputStream.use { input -> FileOutputStream(part, append).use { input.copyTo(it) } }
                } catch (e: NoSpace) {
                    throw e
                } catch (e: IOException) {
                    failure = e
                } finally {
                    conn.disconnect()
                }
                val now = part.length()
                // Without a length, an answer that ends cleanly is the whole file.
                whole = if (total >= 0) now == total else failure == null
                if (whole || total in 0 until now) break
                idle = if (now > have) 0 else idle + 1
                if (idle >= MAX_IDLE_TRIES) break
                if (idle > 0) Thread.sleep(1_000L * idle)
            }
            if (!whole) throw failure ?: IOException("truncated download")
            if (!part.renameTo(target)) throw IOException("rename failed")
        } finally {
            part.delete()
        }
    }

    // Ids come from the API, so keep them from escaping the music folder.
    private fun safeName(id: String) = id.replace(unsafeChars, "_")

    private fun audioFile(id: String) = File(dir, "${safeName(id)}.m4a")

    private fun coverFile(id: String) = File(dir, "${safeName(id)}.jpg")

    private fun read(): List<Song> = index.readJson { text ->
        val songs = JSONArray(text)
        (0 until songs.length()).map { Song.fromJson(songs.getJSONObject(it)) }
    }.orEmpty()

    private fun write(songs: List<Song>) {
        cached = songs
        val json = JSONArray()
        songs.forEach { json.put(it.toJson()) }
        index.writeText(json.toString())
    }
}

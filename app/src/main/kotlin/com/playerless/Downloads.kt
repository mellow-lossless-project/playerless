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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
                    { appContext.getString(R.string.download_done, song.title, it) },
                    { appContext.getString(R.string.download_failed, song.title) },
                )
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                onChanged?.invoke()
            }
        }
    }

    // Main thread. Swaps each downloaded song that isn't FLAC yet for the lossless server's FLAC,
    // where the server has one; the others stay as they are. One at a time, behind any download.
    // progress gets songs done and in all; done gets how many are FLAC now and how many stayed as
    // they were. Run by UpgradeService, which keeps the app alive meanwhile. False if one is running.
    fun upgradeToLossless(progress: (done: Int, total: Int) -> Unit, done: (flac: Int, kept: Int) -> Unit): Boolean {
        if (upgrade != null) return false
        val songs = list()
        upgrade = 0 to songs.size
        onChanged?.invoke()
        executor.execute {
            var flac = 0
            var kept = 0
            fun step(song: Song) {
                if (runCatching { toFlac(song) }.getOrDefault(false)) flac++ else kept++
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
                done(flac, kept)
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
        return if (lossless) "$what: non è sul server lossless" else what
    }

    // Null when the lossless server hasn't the song or doesn't answer.
    private fun fetchLossless(song: Song): String? {
        val found = Lossless.resolve(song.title, song.artist, song.seconds) {} ?: return null
        val file = audioFile(song.id)
        save(found.url, file, headers = mapOf("User-Agent" to Lossless.UA))
        // Never the bytes the song streams: a song playing doesn't move onto this file mid-way.
        sources.remove(song.id)
        // What the file really is, FLAC or not: its size over its length.
        val length = found.seconds.takeIf { it > 0 } ?: song.seconds
        remember(song.copy(kbps = if (length > 0) (file.length() * 8 / length / 1000).toInt() else null))
        return if (found.flac) found.label else "${found.label}, non FLAC"
    }

    // Keeps the online URL, so the song still plays anywhere it's saved if the file is deleted.
    private fun remember(saved: Song) {
        synchronized(this) { write(listOf(saved) + list().filterNot { it.id == saved.id }) }
    }

    // Through a .part file, so the target only ever exists complete. YouTube throttles a request
    // without a range to about twice the song's bitrate; with one, even open-ended, it doesn't.
    private fun save(url: String, target: File, ranged: Boolean = false, headers: Map<String, String> = emptyMap()) {
        val part = File(dir, "${target.name}.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (ranged) conn.setRequestProperty("Range", "bytes=0-")
            val written = conn.inputStream.use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
            val expected = conn.contentLengthLong
            if (expected >= 0 && written != expected) throw IOException("truncated download")
            if (!part.renameTo(target)) throw IOException("rename failed")
        } finally {
            conn.disconnect()
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

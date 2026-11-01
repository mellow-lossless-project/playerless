package com.playerless

import android.content.Context
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

// Offline songs live in filesDir/music, listed newest first in index.json.
object Downloads {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val inProgress = HashSet<String>() // Main thread only.
    private val unsafeChars = Regex("[^A-Za-z0-9_-]")

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

    fun localFile(id: String): File? = audioFile(id).takeIf { it.exists() }

    fun imageFor(song: Song): String {
        val cover = coverFile(song.id)
        return if (cover.exists()) Uri.fromFile(cover).toString() else song.image
    }

    fun list(): List<Song> = synchronized(this) { read() }

    // Every word of the query has to appear in the title or the artist.
    fun search(query: String): List<Song> {
        val words = query.lowercase().split(Regex("\\s+"))
        return list().filter { song ->
            val text = "${song.title} ${song.artist}".lowercase()
            words.all { it in text }
        }
    }

    // A null bitrate keeps the URL as it is, for streams that don't name theirs.
    fun download(song: Song, kbps: Int?) {
        if (!inProgress.add(song.id)) return
        onChanged?.invoke()
        executor.execute {
            val failure = runCatching { fetch(song, kbps) }.exceptionOrNull()
            main.post {
                inProgress.remove(song.id)
                val message = if (failure == null) R.string.download_done else R.string.download_failed
                Toast.makeText(appContext, appContext.getString(message, song.title), Toast.LENGTH_SHORT).show()
                onChanged?.invoke()
            }
        }
    }

    fun delete(song: Song) {
        synchronized(this) { write(read().filterNot { it.id == song.id }) }
        audioFile(song.id).delete()
        coverFile(song.id).delete()
        onChanged?.invoke()
    }

    private fun fetch(song: Song, kbps: Int?) {
        if (song.image.isNotEmpty()) runCatching { save(Covers.big(song.image), coverFile(song.id)) }
        val url = if (kbps != null) SaavnApi.withQuality(song.url, kbps) else song.url
        save(url, audioFile(song.id))
        sources[song.id] = url

        // Keeps the online URL, so the song still plays anywhere it's saved if the file is deleted.
        val saved = song.copy(kbps = kbps)
        synchronized(this) { write(listOf(saved) + read().filterNot { it.id == song.id }) }
    }

    // Through a .part file, so the target only ever exists complete.
    private fun save(url: String, target: File) {
        val part = File(dir, "${target.name}.part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 15_000
            conn.useCaches = false
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
        val json = JSONArray()
        songs.forEach { json.put(it.toJson()) }
        index.writeText(json.toString())
    }
}

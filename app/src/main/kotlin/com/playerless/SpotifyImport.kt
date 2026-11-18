package com.playerless

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

// Imports the playlists that tools/spotify exported from Spotify. The PC tool pushes spotify.json
// over adb into the app's external files folder, the one place both can reach without permissions,
// and reads the progress and the report back from there. Every track is looked up on JioSaavn
// first and on YouTube when JioSaavn doesn't have it.
object SpotifyImport {
    private const val FILE = "spotify.json"
    private const val PROGRESS = "spotify-progress.txt"
    private const val REPORT = "spotify-report.txt"
    private const val THREADS = 4
    private const val UPDATE_MS = 300L
    private const val LENGTH_SLACK_S = 10

    private val main = Handler(Looper.getMainLooper())
    private val bracketed = Regex("\\s*[(\\[].*?[)\\]]")
    private val dashSuffix = Regex("\\s+-\\s+.*$")
    private val marks = Regex("\\p{M}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    private class Track(val title: String, val artists: List<String>, val seconds: Int) {
        val key = normalize(title) + "|" + artists.joinToString(",") { normalize(it) }
    }

    private class Import(val name: String, val tracks: List<Track>)

    // Main thread. Non-null while an import runs: what the home screen shows.
    var progress: String? = null
        private set
    var onChanged: (() -> Unit)? = null

    // Main thread. Also creates the folder the PC tool writes to.
    fun checkForFile(context: Context) {
        if (progress != null) return
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, FILE)
        if (!file.exists()) return
        val app = context.applicationContext
        progress = app.getString(R.string.import_starting)
        onChanged?.invoke()
        Thread { run(app, dir, file) }.start()
    }

    private fun run(context: Context, dir: File, file: File) {
        val imports = try {
            parse(file.readText())
        } catch (e: Exception) {
            file.delete()
            File(dir, REPORT).writeText("Il file di Spotify non si legge: ${e.message}\n")
            main.post { finish(context, context.getString(R.string.import_failed, e.message)) }
            return
        }
        // A crash halfway through mustn't import the same file at every start; the tool can run again.
        file.delete()

        val tracks = imports.flatMap { it.tracks }.distinctBy { it.key }
        // As a Map: on ConcurrentHashMap itself, "in" would look among the values.
        val found: MutableMap<String, Song> = ConcurrentHashMap()
        val done = AtomicInteger()
        val lastUpdate = AtomicLong()
        val pool = Executors.newFixedThreadPool(THREADS)
        for (track in tracks) {
            pool.execute {
                match(track)?.let { found[track.key] = it }
                val count = done.incrementAndGet()
                val now = SystemClock.uptimeMillis()
                val last = lastUpdate.get()
                if (count == tracks.size || (now - last > UPDATE_MS && lastUpdate.compareAndSet(last, now))) {
                    File(dir, PROGRESS).writeText("$count/${tracks.size}")
                    val text = context.getString(R.string.import_progress, count, tracks.size)
                    main.post {
                        progress = text
                        onChanged?.invoke()
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.HOURS)

        val missing = StringBuilder()
        val playlists = imports.mapNotNull { import ->
            import.tracks.filter { it.key !in found }.forEach { missing.append("  ${import.name}: ${it.title} · ${it.artists.joinToString()}\n") }
            val songs = import.tracks.mapNotNull { found[it.key] }.distinctBy { it.id }
            if (songs.isEmpty()) null else import.name to songs
        }
        val total = imports.sumOf { it.tracks.size }
        val matched = imports.sumOf { import -> import.tracks.count { it.key in found } }
        val fromYoutube = imports.sumOf { import -> import.tracks.count { found[it.key]?.let(YouTube::isYouTube) == true } }
        val report = buildString {
            append("Playlist importate: ${playlists.size} su ${imports.size}\n")
            append("Brani trovati: $matched su $total (JioSaavn ${matched - fromYoutube}, YouTube $fromYoutube)\n")
            if (missing.isNotEmpty()) append("\nNon trovati:\n").append(missing)
        }

        main.post {
            playlists.forEach { (name, songs) -> Playlists.replace(name, songs) }
            File(dir, PROGRESS).delete()
            File(dir, REPORT).writeText(report)
            finish(context, context.getString(R.string.import_done, playlists.size, matched, total))
        }
    }

    private fun finish(context: Context, message: String) {
        progress = null
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onChanged?.invoke()
    }

    private fun parse(text: String): List<Import> {
        val playlists = JSONObject(text).getJSONArray("playlists")
        return (0 until playlists.length()).map { i ->
            val playlist = playlists.getJSONObject(i)
            val tracks = playlist.getJSONArray("tracks")
            Import(
                playlist.getString("name"),
                (0 until tracks.length()).mapNotNull { j ->
                    val track = tracks.optJSONObject(j) ?: return@mapNotNull null
                    val artists = track.optJSONArray("artists")
                    Track(
                        track.optString("title"),
                        (0 until (artists?.length() ?: 0)).map { artists!!.optString(it) }.filter { it.isNotBlank() },
                        track.optInt("seconds"),
                    ).takeIf { it.title.isNotBlank() }
                },
            )
        }
    }

    // JioSaavn's version wins when it has the very title: it starts at once and in better quality.
    // Otherwise the closest title among both, still with JioSaavn first on a tie.
    private fun match(track: Track): Song? {
        val query = "${clean(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
        val saavn = runCatching { SaavnApi.search(query) }.getOrDefault(emptyList())
        best(saavn, track)?.let { if (titleScore(it, track) == EXACT) return it }
        val youtube = runCatching { YouTube.search(query) }.getOrDefault(emptyList())
        return best(saavn + youtube, track)
    }

    private fun best(songs: List<Song>, track: Track): Song? = songs
        .filter { sameArtist(it, track) && sameLength(it, track) && titleScore(it, track) > 0 }
        .maxByOrNull { titleScore(it, track) }

    private const val EXACT = 2

    // "Grinch" is in "Grinch 2" too, so an exact title outranks one that only contains it.
    private fun titleScore(song: Song, track: Track): Int {
        val found = normalize(clean(song.title))
        val wanted = normalize(clean(track.title))
        return when {
            found.isEmpty() || wanted.isEmpty() -> 0
            found == wanted -> EXACT
            found.contains(wanted) || wanted.contains(found) -> 1
            else -> 0
        }
    }

    private fun sameArtist(song: Song, track: Track): Boolean {
        if (track.artists.isEmpty()) return true
        val found = song.artist.split(',').map(::normalize).filter { it.isNotEmpty() }
        return track.artists.map(::normalize).filter { it.isNotEmpty() }.any { wanted ->
            found.any { it == wanted || it.contains(wanted) || wanted.contains(it) }
        }
    }

    // Keeps live takes and sped-up edits out when both sides know the length.
    private fun sameLength(song: Song, track: Track) =
        song.seconds == 0 || track.seconds == 0 || abs(song.seconds - track.seconds) <= LENGTH_SLACK_S

    // "Song - Remastered 2011" and "Song (feat. X)" are still "Song".
    private fun clean(title: String) = title.replace(bracketed, "").replace(dashSuffix, "").trim()

    // Lowercase, no accents, words split by single spaces.
    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(nonWord, " ").trim()
}

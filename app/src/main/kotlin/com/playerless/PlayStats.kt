package com.playerless

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

// How many times each song was listened to, newest first. Main thread only.
object PlayStats {
    private const val MAX_SONGS = 400

    private class Entry(val song: Song, val plays: Int)

    private lateinit var file: AtomicFile
    private var entries: List<Entry> = emptyList()

    // The activity and the playback service share this object, whichever starts first.
    fun init(context: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(context.filesDir, "plays.json"))
        entries = read()
    }

    fun top(count: Int): List<Song> =
        entries.sortedByDescending { it.plays }.take(count).map { it.song }

    fun record(song: Song) {
        val plays = entries.find { it.song.id == song.id }?.plays ?: 0
        entries = listOf(Entry(song, plays + 1)) + entries.filterNot { it.song.id == song.id }
        // Read at every start on the main thread, so it can't grow forever. Ties keep the newest.
        if (entries.size > MAX_SONGS) entries = entries.sortedByDescending { it.plays }.take(MAX_SONGS / 2)
        save()
    }

    private fun save() {
        val json = JSONArray()
        entries.forEach { json.put(it.song.toJson().put("plays", it.plays)) }
        file.writeText(json.toString())
    }

    private fun read(): List<Entry> = file.readJson { text ->
        val json = JSONArray(text)
        (0 until json.length()).map { i ->
            val item = json.getJSONObject(i)
            Entry(Song.fromJson(item), item.getInt("plays"))
        }
    }.orEmpty()
}

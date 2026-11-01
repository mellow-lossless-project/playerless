package com.playerless

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song>,
)

// Kept in memory and saved to playlists.json on every change. Main thread only.
object Playlists {
    private lateinit var file: AtomicFile
    var all: List<Playlist> = emptyList()
        private set

    fun init(context: Context) {
        file = AtomicFile(File(context.filesDir, "playlists.json"))
        all = read()
    }

    fun get(id: String?): Playlist? = all.find { it.id == id }

    fun create(name: String): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name, emptyList())
        save(all + playlist)
        return playlist
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun delete(id: String) = save(all.filterNot { it.id == id })

    // False when the song was already in the playlist.
    fun add(id: String, song: Song): Boolean {
        val playlist = get(id) ?: return false
        if (playlist.songs.any { it.id == song.id }) return false
        update(id) { it.copy(songs = it.songs + song) }
        return true
    }

    fun remove(id: String, song: Song) =
        update(id) { playlist -> playlist.copy(songs = playlist.songs.filterNot { it.id == song.id }) }

    private fun update(id: String, change: (Playlist) -> Playlist) =
        save(all.map { if (it.id == id) change(it) else it })

    private fun save(playlists: List<Playlist>) {
        all = playlists
        val json = JSONArray()
        playlists.forEach { playlist ->
            val songs = JSONArray()
            playlist.songs.forEach { songs.put(it.toJson()) }
            json.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("songs", songs),
            )
        }
        file.writeText(json.toString())
    }

    private fun read(): List<Playlist> = file.readJson { text ->
        val playlists = JSONArray(text)
        (0 until playlists.length()).map { i ->
            val json = playlists.getJSONObject(i)
            val songs = json.getJSONArray("songs")
            Playlist(
                id = json.getString("id"),
                name = json.getString("name"),
                songs = (0 until songs.length()).map { Song.fromJson(songs.getJSONObject(it)) },
            )
        }
    }.orEmpty()
}

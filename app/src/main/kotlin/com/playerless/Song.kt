package com.playerless

import org.json.JSONObject

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val image: String,
    val url: String,
    // The bitrate it was downloaded at. Older downloads don't have one.
    val kbps: Int? = null,
    // Length in seconds, 0 when unknown. Only search results have it, to match Spotify imports.
    val seconds: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("image", image)
        .put("url", url)
        .put("kbps", kbps)

    companion object {
        fun fromJson(json: JSONObject) = Song(
            id = json.getString("id"),
            title = json.getString("title"),
            artist = json.getString("artist"),
            image = json.getString("image"),
            url = json.getString("url"),
            kbps = json.optInt("kbps").takeIf { it > 0 },
        )
    }
}

// How a query meets a song's title and artist. Case, apostrophes and spacing don't count: "shottas"
// finds "Shotta's", "season25" finds "Season 25", as people type them.
object Match {
    private val apostrophes = Regex("['’‘`´]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    // Lowercase, apostrophes dropped, anything else that isn't a letter or digit one space.
    fun normalize(text: String) = text.lowercase().replace(apostrophes, "").replace(nonWord, " ").trim()

    fun words(query: String) = normalize(query).split(' ').filter { it.isNotEmpty() }

    // The letters of the query's words the song has: a song with the artist and the title beats one
    // with only the title, and a long word found counts more than a short one.
    fun score(words: List<String>, song: Song): Int {
        val text = normalize("${song.title} ${song.artist}")
        val joined = text.replace(" ", "")
        return words.sumOf { if (it in text || it in joined) it.length else 0 }
    }

    fun hasAll(words: List<String>, song: Song): Boolean {
        val text = normalize("${song.title} ${song.artist}")
        val joined = text.replace(" ", "")
        return words.all { it in text || it in joined }
    }
}

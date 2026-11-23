package com.playerless

import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val image: String,
    val url: String,
    // The bitrate it was downloaded at. Older downloads don't have one.
    val kbps: Int? = null,
    // Length in seconds, 0 when unknown: search results have it, and it's kept with the song since
    // 1.2, so the lossless server can tell a live take from the studio one. Older entries lack it.
    val seconds: Int = 0,
    // The uncensored recording, as its catalog marks it. Clean edits share the title.
    val explicit: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("image", image)
        .put("url", url)
        .put("kbps", kbps)
        .apply { if (seconds > 0) put("seconds", seconds) }
        .apply { if (explicit) put("explicit", true) }

    companion object {
        fun fromJson(json: JSONObject) = Song(
            id = json.getString("id"),
            title = json.getString("title"),
            artist = json.getString("artist"),
            image = json.getString("image"),
            url = json.getString("url"),
            kbps = json.optInt("kbps").takeIf { it > 0 },
            seconds = json.optInt("seconds"),
            explicit = json.optBoolean("explicit"),
        )
    }
}

// How a query meets a song's title and artist. Case, accents, apostrophes and spacing don't count:
// "shottas" finds "Shotta's", "season25" finds "Season 25", and JioSaavn's "Foushee" is the lossless
// server's "Fousheé", as people and catalogs write them.
object Match {
    private val marks = Regex("\\p{M}+")
    private val apostrophes = Regex("['’‘`´]")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    // Lowercase, accents and apostrophes dropped, anything else that isn't a letter or digit one space.
    fun normalize(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(marks, "")
        .replace(apostrophes, "")
        .replace(nonWord, " ")
        .trim()

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

    // A song as the catalogs name it: its title and first artist. Guests don't count, since only
    // some catalogs put them in the title: YouTube's "Moulaga (con JUL)" is JioSaavn's "Moulaga".
    fun key(song: Song) =
        normalize(song.title.replace(featuring, "")) + "|" + normalize(song.artist.substringBefore(','))

    private val featuring = Regex("\\s*[(\\[](?:con|feat\\.?|ft\\.?|with|featuring)\\s[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)

    // One entry per recording. A catalog lists a song again on every compilation, and next to it
    // its clean edit under the same title, often first: same title, first artist and length (within
    // 2 s) count as one, in the place of the first. The uncensored one wins, then the higher bitrate.
    fun oneEach(songs: List<Song>, kbps: (Song) -> Int = { 0 }): List<Song> {
        val kept = ArrayList<Song>(songs.size)
        val keys = ArrayList<String>(songs.size)
        for (song in songs) {
            val key = key(song)
            val twin = kept.indices.firstOrNull { keys[it] == key && sameLength(kept[it], song) }
            when {
                twin == null -> {
                    kept += song
                    keys += key
                }
                rank(song, kbps) > rank(kept[twin], kbps) -> kept[twin] = song
            }
        }
        return kept
    }

    private fun sameLength(a: Song, b: Song) =
        if (a.seconds > 0 && b.seconds > 0) abs(a.seconds - b.seconds) <= 2 else a.seconds == b.seconds

    private fun rank(song: Song, kbps: (Song) -> Int) = (if (song.explicit) 10_000 else 0) + kbps(song)
}

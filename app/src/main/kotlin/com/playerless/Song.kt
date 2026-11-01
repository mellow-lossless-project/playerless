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

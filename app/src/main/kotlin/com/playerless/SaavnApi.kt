package com.playerless

import android.net.Uri
import android.text.Html
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// JioSaavn's private API, the same one the official app uses. Ported from Convx's SaavnService.
object SaavnApi {
    private const val BASE_URL = "https://www.jiosaavn.com/api.php"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    private val QUALITY_SUFFIX = Regex("_(48|96|160|320)\\.(mp4|aac|mp3)$")

    // The bitrate a stream URL points at, when its name says so.
    fun kbpsOf(url: String): Int? = QUALITY_SUFFIX.find(url)?.groupValues?.get(1)?.toInt()

    // Only ever lowers the bitrate: a track without a 320 rendition stays as it is.
    fun withQuality(url: String, kbps: Int): String {
        val suffix = QUALITY_SUFFIX.find(url) ?: return url
        val current = suffix.groupValues[1].toInt()
        return if (kbps < current) url.replaceRange(suffix.range, "_$kbps.${suffix.groupValues[2]}") else url
    }

    // Blocking: call off the main thread.
    fun search(query: String): List<Song> {
        val url = Uri.parse(BASE_URL).buildUpon()
            .appendQueryParameter("__call", "search.getResults")
            .appendQueryParameter("_format", "json")
            .appendQueryParameter("_marker", "0")
            .appendQueryParameter("api_version", "4")
            .appendQueryParameter("ctx", "android")
            .appendQueryParameter("q", query)
            .appendQueryParameter("p", "1")
            .appendQueryParameter("n", "30")
            .build().toString()

        val results = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { parseSong(results.getJSONObject(it)) }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            conn.setRequestProperty("Cookie", "explicit_content=1")
            conn.setRequestProperty("X-Forwarded-For", "49.36.0.1")
            conn.setRequestProperty("X-Real-IP", "49.36.0.1")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSong(json: JSONObject): Song? {
        val info = json.optJSONObject("more_info") ?: return null
        val url = streamUrl(
            info.optString("encrypted_media_url"),
            info.optString("320kbps").equals("true", ignoreCase = true),
        ) ?: return null

        val artists = info.optJSONObject("artistMap")?.optJSONArray("primary_artists")
        val artist = (0 until (artists?.length() ?: 0))
            .joinToString(", ") { artists!!.getJSONObject(it).optString("name") }

        return Song(
            id = json.optString("id"),
            title = unescape(json.optString("title")),
            artist = unescape(artist),
            image = json.optString("image").replace("http://", "https://"),
            url = url,
        )
    }

    // The catalogue says per track whether a 320 rendition exists; asking the CDN anyway fails.
    private fun streamUrl(encrypted: String, supports320: Boolean): String? {
        if (encrypted.isBlank()) return null
        val url = decrypt(encrypted) ?: return null
        val suffix = QUALITY_SUFFIX.find(url)
        return if (supports320 && suffix != null) {
            url.replaceRange(suffix.range, "_320.${suffix.groupValues[2]}")
        } else {
            url
        }
    }

    private fun decrypt(encrypted: String): String? = try {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
        String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))).trim()
    } catch (_: Exception) {
        null
    }

    // Titles arrive HTML-escaped, e.g. &quot;.
    private fun unescape(text: String): String =
        if ('&' in text) Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString() else text
}

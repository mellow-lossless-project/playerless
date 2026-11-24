package com.playerless

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// The token YouTube wants before it serves more than the first megabyte of a stream, one per video.
// Minting needs BotGuard, Google's attestation script: its page stays open while its integrity
// token lasts, hours, and then mints each song's token in milliseconds. Ported from Convx's
// PoTokenGenerator.
object PoToken {
    private const val API = "https://www.youtube.com/api/jnn/v1/"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val MARGIN_MS = 10 * 60_000L

    private val headers = mapOf(
        "User-Agent" to CHROME_UA,
        "Accept" to "application/json",
        "Content-Type" to "application/json+protobuf",
        "x-goog-api-key" to API_KEY,
        "x-user-agent" to "grpc-web-javascript/0.1",
    )

    private var minter: JsPage? = null
    private var expires = 0L

    // Blocking.
    fun forVideo(context: Context, videoId: String, log: (String) -> Unit): String =
        ready(context, log).call("mint(${JSONObject.quote(videoId)})")

    // Blocking. Readies BotGuard ahead of the first song.
    @Synchronized
    fun ready(context: Context, log: (String) -> Unit): JsPage {
        minter?.takeIf { !it.dead && System.currentTimeMillis() < expires }?.let { return it }
        forget()

        log(Lang.get(R.string.botguard_start))
        val html = context.assets.open("yt/botguard.html").bufferedReader().use { it.readText() }
        val page = JsPage.open(context, "https://www.youtube.com/", html)
        try {
            val challenge = Http.post("${API}Create", JSONArray().put(REQUEST_KEY).toString(), headers)
            val answer = page.call("start(${JSONObject.quote(challenge)})")
            val integrity = JSONArray(Http.post("${API}GenerateIT", JSONArray().put(REQUEST_KEY).put(answer).toString(), headers))
            page.call("setToken(${JSONObject.quote(integrity.getString(0))})")
            expires = System.currentTimeMillis() + integrity.getLong(1) * 1000 - MARGIN_MS
        } catch (e: Exception) {
            page.close()
            throw e
        }
        minter = page
        log(Lang.get(R.string.botguard_ready))
        return page
    }

    // When YouTube turns down a link, BotGuard starts over.
    @Synchronized
    fun forget() {
        minter?.close()
        minter = null
    }
}

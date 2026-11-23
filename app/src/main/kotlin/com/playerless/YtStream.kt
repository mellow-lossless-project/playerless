package com.playerless

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

// Turns a YouTube video id into a stream URL for the player and for downloads. YouTube hands
// clients it doesn't trust links that stop after the first megabyte, so a link counts only once
// its last byte downloads. Two ways in: the VR app's client, instant when YouTube lets it through;
// otherwise the web client, whose links need unscrambling and a BotGuard token.
object YtStream {
    // The best audio YouTube gives without Premium: Opus in WebM at about 130-160 kbps, the full
    // band to 20 kHz. AAC in MP4 (128 kbps, cut at 16 kHz) only when a video lacks it.
    private const val OPUS = 251
    private const val AAC = 140
    private const val DEFAULT_EXPIRY_S = 6 * 3600L
    private const val EXPIRY_MARGIN_MS = 30 * 60_000L
    private const val VR_RETRY_MS = 24 * 3600_000L

    // codec and kbps say what the stream is, for the player's label and the downloads list.
    class Link(val url: String, val expires: Long, val codec: String, val kbps: Int)

    private val links = ConcurrentHashMap<String, Link>()
    private val background = Executors.newCachedThreadPool()
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    }

    // Asking a client YouTube turned down costs half a second, so a refusal holds for a day.
    private var vrRefused: Boolean
        get() = System.currentTimeMillis() < prefs.getLong("vr_refused_until", 0)
        set(value) = prefs.edit().putLong("vr_refused_until", if (value) System.currentTimeMillis() + VR_RETRY_MS else 0).apply()

    // Blocking: call off the main thread. log gets each step, for the on-screen log.
    fun resolve(videoId: String, log: (String) -> Unit): Link {
        links[videoId]?.takeIf { it.expires > System.currentTimeMillis() }?.let { return it }
        log("Chiedo il brano a YouTube")
        val visitor = YouTube.visitorData()
        val link = (if (vrRefused) null else fromVr(videoId, visitor, log)) ?: fromWeb(videoId, visitor, log, firstTry = true)
        links[videoId] = link
        return link
    }

    // A link that failed while playing shouldn't be handed out again.
    fun forget(videoId: String) {
        links.remove(videoId)
    }

    // Gets the web client ready while the user is still choosing, so the first song starts sooner.
    fun warmUp() {
        background.execute {
            runCatching {
                YouTube.visitorData()
                PoToken.ready(appContext) {}
                YtSolver.get(appContext, {})
            }
        }
    }

    private fun fromVr(videoId: String, visitor: String, log: (String) -> Unit): Link? {
        val response = try {
            YouTube.player(YouTube.Client.ANDROID_VR, videoId, visitor, null)
        } catch (_: IOException) {
            null
        }
        val format = response?.takeIf { status(it) == "OK" }?.let(::audioFormat)
        val url = format?.optString("url").orEmpty()
        if (url.isNotEmpty()) {
            log("Verifico il link")
            if (works(url, format!!)) return link(url, format, response)
        }
        vrRefused = true
        log("Client VR bloccato, uso il web")
        return null
    }

    private fun fromWeb(videoId: String, visitor: String, log: (String) -> Unit, firstTry: Boolean): Link {
        // The token comes from Google, the player from YouTube: fetched side by side.
        val token = background.submit(Callable { PoToken.forVideo(appContext, videoId, log) })
        val player = YtSolver.get(appContext, log, refresh = !firstTry)
        val response = YouTube.player(YouTube.Client.WEB_REMIX, videoId, visitor, player.signatureTimestamp)
        if (status(response) != "OK") {
            val reason = response.optJSONObject("playabilityStatus")?.optString("reason").orEmpty()
            throw YtException(reason.ifEmpty { "YouTube non dà questo brano" })
        }
        val format = audioFormat(response) ?: throw YtException("Nessun audio per questo brano")

        log("Decifro il link")
        val cipher = query(format.optString("signatureCipher"))
        var url = format.optString("url").ifEmpty { cipher["url"].orEmpty() }
        if (url.isEmpty()) throw YtException("Link mancante")
        val n = Uri.parse(url).getQueryParameter("n")
        val signature = cipher["s"]
        val solved = JSONObject(player.page.call("solve(${JSONObject.quote(n.orEmpty())},${JSONObject.quote(signature.orEmpty())})"))
        if (n != null) {
            val newN = solved.optString("n")
            if (newN.isEmpty() || newN == n || newN.startsWith("enhanced_except")) throw YtException("Decifratura fallita")
            url = url.replace(Regex("([?&])n=[^&]*"), "$1n=" + Regex.escapeReplacement(Uri.encode(newN)))
        }
        if (signature != null) url += "&${cipher["sp"] ?: "signature"}=${Uri.encode(solved.getString("sig"))}"
        url += "&pot=" + try {
            token.get()
        } catch (e: ExecutionException) {
            throw e.cause as? IOException ?: YtException("Gettone non generato")
        }

        log("Verifico il link")
        if (works(url, format)) return link(url, format, response)
        if (!firstTry) throw YtException("YouTube rifiuta il link")
        log("Link rifiutato, riprovo")
        PoToken.forget()
        return fromWeb(videoId, visitor, log, firstTry = false)
    }

    private fun status(response: JSONObject) = response.optJSONObject("playabilityStatus")?.optString("status")

    // Opus, else AAC, else the richest audio there is. Never a "DRC" copy, whose loudness is squeezed.
    private fun audioFormat(response: JSONObject): JSONObject? {
        val formats = response.optJSONObject("streamingData")?.optJSONArray("adaptiveFormats") ?: return null
        val audio = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
            .filter { it.optString("mimeType").let { type -> type.startsWith("audio/webm") || type.startsWith("audio/mp4") } }
            .filterNot { it.optBoolean("isDrc") }
        return audio.find { it.optInt("itag") == OPUS } ?: audio.find { it.optInt("itag") == AAC }
            ?: audio.maxByOrNull { it.optInt("bitrate") }
    }

    // averageBitrate is the file's; bitrate, its peak, only stands in when that's missing.
    private fun link(url: String, format: JSONObject, response: JSONObject): Link {
        val codec = if ("opus" in format.optString("mimeType")) "Opus" else "AAC"
        val bps = format.optInt("averageBitrate").takeIf { it > 0 } ?: format.optInt("bitrate")
        return Link(url, expiry(response), codec, (bps + 500) / 1000)
    }

    private fun works(url: String, format: JSONObject): Boolean {
        val length = format.optString("contentLength").toLongOrNull() ?: 1
        val code = try {
            Http.probe(url, length - 1, YouTube.WEB_UA)
        } catch (_: IOException) {
            return false
        }
        return code == 200 || code == 206
    }

    private fun expiry(response: JSONObject): Long {
        val seconds = response.optJSONObject("streamingData")?.optString("expiresInSeconds")?.toLongOrNull()
        return System.currentTimeMillis() + (seconds ?: DEFAULT_EXPIRY_S) * 1000 - EXPIRY_MARGIN_MS
    }

    // signatureCipher is a query string: s, sp and url.
    private fun query(text: String): Map<String, String> = text.split('&').mapNotNull { pair ->
        val at = pair.indexOf('=')
        if (at > 0) Uri.decode(pair.substring(0, at)) to Uri.decode(pair.substring(at + 1)) else null
    }.toMap()
}

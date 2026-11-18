package com.playerless

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

// What happens between pressing play and hearing the song, for the on-screen log. The playback
// service and its loader threads write it, the activity shows it. Main thread only, except add().
object LoadLog {
    private const val FAILURE_MS = 4_000L

    private val main = Handler(Looper.getMainLooper())
    private var id: String? = null
    private var startedAt = 0L
    private var entries = ArrayList<String>()
    private var failure: String? = null
    private var failedUntil = 0L

    var title = ""
        private set
    var loading = false
        private set
    var onChanged: (() -> Unit)? = null

    // A failure stays up a few seconds, also over the next song the player skips to.
    val visible get() = loading || SystemClock.uptimeMillis() < failedUntil
    val elapsedMs get() = SystemClock.uptimeMillis() - startedAt
    val lines: List<String> get() = entries

    // Any thread. Lines of a song that isn't loading on screen, like the next one preparing, stay hidden.
    fun add(id: String, text: String) {
        val at = SystemClock.uptimeMillis()
        main.post {
            switchTo(id, at)
            entries.add(line(at - startedAt, text))
            onChanged?.invoke()
        }
    }

    // A song is loading from the moment it should play until it first does.
    fun update(id: String?, title: String, loading: Boolean) {
        val switched = id != null && switchTo(id, SystemClock.uptimeMillis())
        if (!switched && loading == this.loading && title == this.title) return
        this.loading = loading
        this.title = title
        onChanged?.invoke()
    }

    fun fail(text: String) {
        val line = line(elapsedMs, "Non riproducibile: $title ($text)")
        failure = line
        entries.add(line)
        loading = false
        failedUntil = SystemClock.uptimeMillis() + FAILURE_MS
        main.postDelayed({ onChanged?.invoke() }, FAILURE_MS)
        onChanged?.invoke()
    }

    // The failure line is drawn in the accent color, so it reads as one at a glance.
    fun isFailure(line: String) = line === failure

    private fun switchTo(id: String, at: Long): Boolean {
        if (id == this.id) return false
        this.id = id
        startedAt = at
        entries = ArrayList(listOfNotNull(failure.takeIf { SystemClock.uptimeMillis() < failedUntil }))
        return true
    }

    private fun line(ms: Long, text: String) = String.format(Locale.ITALY, "%4.1f s  %s", ms / 1000.0, text)
}

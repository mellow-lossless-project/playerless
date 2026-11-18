package com.playerless

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

// What each song is really playing from and in what quality, e.g. "JioSaavn · AAC 320 kbps", so the
// full-screen player can say whether it's lossless. The playback service writes it when it opens a
// song, on the loading thread; the activity reads it on the main thread.
object NowPlaying {
    private val main = Handler(Looper.getMainLooper())
    private val labels = ConcurrentHashMap<String, String>()

    // Main thread.
    var onChanged: (() -> Unit)? = null

    fun set(id: String, label: String) {
        if (labels.put(id, label) == label) return
        main.post { onChanged?.invoke() }
    }

    fun of(id: String?): String? = id?.let { labels[it] }
}

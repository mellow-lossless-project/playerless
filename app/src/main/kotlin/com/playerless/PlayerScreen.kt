package com.playerless

import android.annotation.SuppressLint
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.util.Locale

private const val TICK_MS = 1_000L

// 3:07, or 1:02:03 past an hour.
private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}

// Also restarts after an error (idle) and at the end of the queue (ended).
@OptIn(UnstableApi::class)
fun Player.togglePlayPause() {
    Util.handlePlayPauseButtonAction(this)
}

@OptIn(UnstableApi::class)
val Player.playPauseIcon get() = if (Util.shouldShowPlayButton(this)) R.drawable.ic_play else R.drawable.ic_pause

// The full-screen player. Built the first time it's opened, so it costs nothing at startup.
// The cover's touch listener does call performClick, when the finger lifts.
@SuppressLint("ClickableViewAccessibility")
class PlayerScreen(
    private val view: View,
    private val window: Window,
    private val skin: Skin,
    private val player: () -> Player?,
) {
    private val title: TextView = view.findViewById(R.id.fullTitle)
    private val artist: TextView = view.findViewById(R.id.fullArtist)
    private val cover: ImageView = view.findViewById(R.id.fullCover)
    private val progress: SeekBar = view.findViewById(R.id.fullProgress)
    private val elapsed: TextView = view.findViewById(R.id.fullElapsed)
    private val length: TextView = view.findViewById(R.id.fullLength)
    private val quality: TextView = view.findViewById(R.id.fullQuality)
    private val playPause: ImageButton = view.findViewById(R.id.fullPlayPause)
    private val volume: SeekBar = view.findViewById(R.id.fullVolume)

    private val audio = view.context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false
    private var coverUrl: String? = null

    // The volume follows along too: keys and headsets change it without telling the app.
    private val tick = object : Runnable {
        override fun run() {
            showProgress()
            if (!volume.isPressed) syncVolume()
            handler.postDelayed(this, TICK_MS)
        }
    }

    val isOpen get() = view.visibility == View.VISIBLE

    init {
        view.background = skin.page()
        // A printed cover shows as it really is for as long as a finger rests on it.
        if (skin.inks != null) {
            cover.setOnTouchListener { touched, event ->
                val url = coverUrl
                if (url != null) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> Covers.load(url, cover, printed = false)
                        MotionEvent.ACTION_UP -> {
                            touched.performClick()
                            Covers.load(url, cover, keepOriginal = true)
                        }
                        MotionEvent.ACTION_CANCEL -> Covers.load(url, cover, keepOriginal = true)
                    }
                }
                true
            }
        }
        view.findViewById<View>(R.id.fullPrev).setOnClickListener { player()?.seekToPrevious() }
        view.findViewById<View>(R.id.fullNext).setOnClickListener { player()?.seekToNext() }
        playPause.setOnClickListener { player()?.togglePlayPause() }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            // While dragging, the time shows where the song will resume.
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) elapsed.text = time(value.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                player()?.seekTo(bar.progress.toLong())
            }
        })

        volume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    fun open() {
        view.visibility = View.VISIBLE
        setBarColors(skin.playerSystemBarColor)
        syncVolume()
        render()
        resume()
    }

    fun close() {
        view.visibility = View.GONE
        setBarColors(skin.systemBarColor)
        pause()
    }

    fun resume() {
        handler.removeCallbacks(tick)
        if (isOpen) handler.post(tick)
    }

    fun pause() = handler.removeCallbacks(tick)

    fun render() {
        val player = player() ?: return
        val metadata = player.currentMediaItem?.mediaMetadata
        if (metadata == null) {
            close()
            return
        }
        title.text = metadata.title
        artist.text = metadata.artist
        quality.text = NowPlaying.of(player.currentMediaItem?.mediaId)
        playPause.setImageResource(player.playPauseIcon)
        val image = metadata.artworkUri?.toString()?.let(Covers::big).orEmpty()
        if (image != coverUrl) {
            coverUrl = image
            Covers.load(image, cover, keepOriginal = true)
        }
        showProgress()
    }

    private fun showProgress() {
        val player = player() ?: return
        if (seeking) return
        // Unknown until the song has loaded: then the bar stays empty and the length blank.
        val duration = player.duration.coerceAtLeast(0)
        val position = player.currentPosition
        progress.max = duration.toInt()
        progress.progress = position.toInt()
        elapsed.text = time(position)
        length.text = if (duration > 0) time(duration) else ""
    }

    private fun syncVolume() {
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    // Deprecated only from Android 15, which this app doesn't target.
    @Suppress("DEPRECATION")
    private fun setBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
    }
}

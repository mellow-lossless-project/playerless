package com.playerless

import android.graphics.Color
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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

private const val TICK_MS = 1_000L
private val APP_BAR_COLOR = Color.parseColor("#3A3A3A")

// Also restarts after an error (idle) and at the end of the queue (ended).
@OptIn(UnstableApi::class)
fun Player.togglePlayPause() {
    Util.handlePlayPauseButtonAction(this)
}

@OptIn(UnstableApi::class)
val Player.playPauseIcon get() = if (Util.shouldShowPlayButton(this)) R.drawable.ic_play else R.drawable.ic_pause

// The full-screen player. Built the first time it's opened, so it costs nothing at startup.
class PlayerScreen(
    private val view: View,
    private val window: Window,
    private val player: () -> Player?,
) {
    private val title: TextView = view.findViewById(R.id.fullTitle)
    private val cover: ImageView = view.findViewById(R.id.fullCover)
    private val progress: SeekBar = view.findViewById(R.id.fullProgress)
    private val playPause: ImageButton = view.findViewById(R.id.fullPlayPause)
    private val volume: SeekBar = view.findViewById(R.id.fullVolume)

    private val audio = view.context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

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
        view.findViewById<View>(R.id.fullPrev).setOnClickListener { player()?.seekToPrevious() }
        view.findViewById<View>(R.id.fullNext).setOnClickListener { player()?.seekToNext() }
        playPause.setOnClickListener { player()?.togglePlayPause() }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit

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
        setBarColors(Color.BLACK)
        syncVolume()
        render()
        resume()
    }

    fun close() {
        view.visibility = View.GONE
        setBarColors(APP_BAR_COLOR)
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
        playPause.setImageResource(player.playPauseIcon)
        val image = metadata.artworkUri?.toString()?.let(Covers::big).orEmpty()
        if (cover.tag != image) Covers.load(image, cover)
        showProgress()
    }

    private fun showProgress() {
        val player = player() ?: return
        if (seeking) return
        progress.max = player.duration.coerceAtLeast(0).toInt()
        progress.progress = player.currentPosition.toInt()
    }

    private fun syncVolume() {
        volume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun setBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
    }
}

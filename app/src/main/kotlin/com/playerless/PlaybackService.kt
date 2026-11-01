package com.playerless

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ConcurrentHashMap

private const val LISTENED_MS = 30_000L
const val EXTRA_URL = "url"
const val EXTRA_IMAGE = "image"

class PlaybackService : MediaSessionService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Downloads.init(this)
        PlayStats.init(this)

        // Start fast and keep little in memory: 15-30 s of buffer instead of the default 50 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 500, 1_500)
            .build()

        // no-store keeps audio out of the cover cache, which would otherwise take it too.
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Cache-Control" to "no-store"))

        // The extractor keeps byte offsets of the file it parsed, and the renditions differ, so a
        // song stays on the source it started from. Only the first open of a song chooses.
        val fromDisk = ConcurrentHashMap<String, Boolean>()
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val id = spec.key ?: return@Factory spec
            val local = Downloads.localFile(id)
            if (spec.position == 0L) fromDisk[id] = local != null
            val safe = fromDisk[id] == true || Downloads.sameAsStream(id, spec.uri.toString())
            if (local != null && safe) spec.withUri(Uri.fromFile(local)) else spec
        }

        // Audio only: the default factories would also pull in every video, text and metadata
        // renderer and container, 200 KB of the APK. Saavn serves MP4; its URLs also allow AAC and MP3.
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            arrayOf(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audio))
        }
        val extractors = ExtractorsFactory {
            arrayOf(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), AdtsExtractor(), Mp3Extractor())
        }

        val player = ExoPlayer.Builder(this, renderers, ProgressiveMediaSource.Factory(dataSource, extractors))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // A song that can't load (offline and not downloaded) shouldn't end the whole queue.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })
        player.addListener(PlayCounter(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // The first song would otherwise wait for the list of codecs on the playback thread.
        Thread { MediaCodecUtil.warmDecoderInfoCache(MimeTypes.AUDIO_AAC, false, false) }.start()

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // A song counts once 30 s of it were heard, so skipping through a queue doesn't inflate the stats.
    private inner class PlayCounter(private val player: Player) : Player.Listener, Runnable {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            handler.removeCallbacks(this)
            if (mediaItem != null) handler.postDelayed(this, LISTENED_MS)
        }

        override fun run() {
            val item = player.currentMediaItem ?: return
            val left = LISTENED_MS - player.currentPosition
            if (left > 0) {
                handler.postDelayed(this, left.coerceAtLeast(1_000))
            } else {
                PlayStats.record(toSong(item))
            }
        }

        private fun toSong(item: MediaItem) = Song(
            id = item.mediaId,
            title = item.mediaMetadata.title.toString(),
            artist = item.mediaMetadata.artist.toString(),
            image = item.requestMetadata.extras?.getString(EXTRA_IMAGE) ?: item.mediaMetadata.artworkUri?.toString().orEmpty(),
            url = item.requestMetadata.extras?.getString(EXTRA_URL) ?: item.requestMetadata.mediaUri.toString(),
        )
    }

    // Controllers don't reliably pass the playable URI, so it travels in requestMetadata. The cache
    // key carries the song id down to the data source above.
    private object SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val playable = mediaItems.map { item ->
                item.buildUpon()
                    .setUri(item.requestMetadata.mediaUri)
                    .setCustomCacheKey(item.mediaId)
                    .build()
            }
            return Futures.immediateFuture(playable.toMutableList())
        }
    }
}

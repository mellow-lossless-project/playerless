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
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val LISTENED_MS = 30_000L

// How long a song waits for the lossless server before it starts from its own source. Most answers
// come in 1-4 s; a slower one goes on behind the song, so the song is lossless the next time.
private const val LOSSLESS_WAIT_MS = 6_000L
const val EXTRA_URL = "url"
const val EXTRA_IMAGE = "image"
const val EXTRA_SECONDS = "seconds"

// Where a song started playing from: its download, its own stream, or else a lossless file's URL.
private const val FROM_DISK = "disk"
private const val FROM_STREAM = ""

// What the lossless lookup needs of a queued song, which the data source only knows by id.
private class SongInfo(val title: String, val artist: String, val seconds: Int)

private val queued = ConcurrentHashMap<String, SongInfo>()

class PlaybackService : MediaSessionService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private val origins = ConcurrentHashMap<String, String>()
    private val ahead = Executors.newSingleThreadExecutor()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Lossless.init(this)
        Downloads.init(this)
        PlayStats.init(this)
        YouTube.init(this)
        YtStream.init(this)
        Equalizer.init(this)

        // Start fast and keep little in memory: 15-30 s of buffer instead of the default 50 s.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 500, 1_500)
            .build()

        // no-store keeps audio out of the cover cache, which would otherwise take it too.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(YouTube.WEB_UA)
            .setDefaultRequestProperties(mapOf("Cache-Control" to "no-store"))

        // The extractor keeps byte offsets of the file it parsed, and the renditions differ, so a
        // song stays on the source it started from: only the first open of a song chooses. The one
        // move allowed is from a stream to its download, when that finished meanwhile and holds the
        // very same bytes. YouTube songs get their stream URL here, on the loading thread, the
        // moment they're needed.
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            val id = spec.key ?: return@Factory spec
            val first = spec.position == 0L
            val local = Downloads.localFile(id)
            if (first) origins[id] = if (local != null) FROM_DISK else losslessUrl(id, spec.uri.scheme == "yt") ?: FROM_STREAM
            val origin = origins[id] ?: FROM_STREAM
            when {
                local != null && (origin == FROM_DISK || (origin == FROM_STREAM && Downloads.sameAsStream(id, spec.uri.toString()))) -> {
                    if (first) LoadLog.add(id, "Apro il brano scaricato")
                    val kbps = Downloads.list().find { it.id == id }?.kbps
                    NowPlaying.set(id, if (kbps != null) "Scaricato · $kbps kbps" else "Scaricato")
                    spec.withUri(Uri.fromFile(local))
                }
                // Its label was set when the lossless server was chosen.
                origin != FROM_DISK && origin != FROM_STREAM -> spec.withUri(Uri.parse(origin))
                spec.uri.scheme == "yt" -> {
                    val log: (String) -> Unit = if (first) ({ LoadLog.add(id, it) }) else ({})
                    val link = try {
                        YtStream.resolve(spec.uri.schemeSpecificPart, log)
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Exception) {
                        // YouTube answering something unexpected: not worth a retry either.
                        throw YtException(e.message ?: e.javaClass.simpleName)
                    }
                    NowPlaying.set(id, "YouTube · ${link.codec} ${link.kbps} kbps")
                    log("Link pronto, carico l'audio")
                    // From the start of a file the player asks for no range, and YouTube serves a
                    // request without one at about twice the song's bitrate: enough to play, slow
                    // to buffer. A range of any kind gets full speed.
                    spec.withUri(Uri.parse(link.url)).withAdditionalHeaders(mapOf("Range" to "bytes=${spec.position}-"))
                }
                else -> {
                    if (first) LoadLog.add(id, "Carico l'audio da JioSaavn")
                    val kbps = SaavnApi.kbpsOf(spec.uri.toString())
                    NowPlaying.set(id, if (kbps != null) "JioSaavn · AAC $kbps kbps" else "JioSaavn")
                    spec
                }
            }
        }

        // Audio only: the default factories would also pull in every video, text and metadata
        // renderer and container, 200 KB of the APK. Saavn serves MP4; its URLs also allow AAC and
        // MP3. YouTube serves Opus in WebM, or AAC in fragmented MP4.
        // The equalizer sits in the sink's chain, after decoding, before the speaker.
        val renderers = RenderersFactory { handler, _, audio, _, _ ->
            val sink = DefaultAudioSink.Builder(this).setAudioProcessors(arrayOf(EqProcessor())).build()
            arrayOf(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audio, sink))
        }
        // FLAC for the lossless quality and Opus for YouTube, both decoded by the platform's own
        // decoders. Every file is told by its first bytes, not its name (downloads are all .m4a):
        // the formats that open with a signature go first, the ones that need a scan last.
        val extractors = ExtractorsFactory {
            arrayOf(
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
                FlacExtractor(),
                AdtsExtractor(),
                Mp3Extractor(),
            )
        }

        // A YouTube song that can't be resolved won't be on a second try, and each try takes seconds.
        val retries = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (info.exception is YtException) C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }
        val mediaSource = ProgressiveMediaSource.Factory(dataSource, extractors).setLoadErrorHandlingPolicy(retries)

        val player = ExoPlayer.Builder(this, renderers, mediaSource)
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
                val id = player.currentMediaItem?.mediaId
                if (id != null && id.startsWith(YouTube.PREFIX)) YtStream.forget(id.removePrefix(YouTube.PREFIX))
                // A lossless file that broke off: the same song again, from its own source. Its first
                // open chooses again, and the lossless server is out of the running for it.
                val origin = id?.let { origins[it] }
                val info = id?.let { queued[it] }
                if (origin != null && origin != FROM_DISK && origin != FROM_STREAM && info != null) {
                    Lossless.failed(info.title, info.artist)
                    LoadLog.add(id, "Il file lossless si è interrotto: riparto dalla sorgente del brano")
                    player.prepare()
                    return
                }
                LoadLog.fail(error.cause?.message ?: error.errorCodeName)
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                }
            }
        })
        player.addListener(PlayCounter(player))
        player.addListener(LoadWatcher())
        player.addListener(LosslessAhead(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        // The first song would otherwise wait for the list of codecs on the playback thread.
        Thread {
            MediaCodecUtil.warmDecoderInfoCache(MimeTypes.AUDIO_AAC, false, false)
            MediaCodecUtil.warmDecoderInfoCache(MimeTypes.AUDIO_OPUS, false, false)
            if (Settings.lossless) MediaCodecUtil.warmDecoderInfoCache(MimeTypes.AUDIO_FLAC, false, false)
        }.start()

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback)
            .setSessionActivity(openApp)
            .build()
    }

    // With the lossless quality on: FLAC from the lossless server beats every source. What the server
    // sends instead for some songs (Monochrome's own: AAC ~320 kbps) still beats YouTube's Opus, not
    // JioSaavn's 320. Every song is asked about, since the server has FLAC for some and not others.
    // Null: the song's own source.
    private fun losslessUrl(id: String, youtube: Boolean): String? {
        if (!Settings.lossless) return null
        val info = queued[id] ?: return null
        val log: (String) -> Unit = { LoadLog.add(id, it) }
        val found = Lossless.resolve(info.title, info.artist, info.seconds, LOSSLESS_WAIT_MS, log) ?: return null
        if (!found.flac && !youtube) {
            log("${found.label}, non FLAC: resto su JioSaavn 320 kbps")
            return null
        }
        log("Carico l'audio dal server lossless (${found.label})")
        NowPlaying.set(id, if (found.flac) "Lossless · ${found.label}" else "Server lossless · ${found.label}, non FLAC")
        return found.url
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        Downloads.playing = null
        ahead.shutdownNow()
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
            // Its download isn't swapped for a lossless one while it plays.
            Downloads.playing = mediaItem?.mediaId
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

    // With the lossless quality on, the next song is looked up on the lossless server while this one
    // plays, with all the time the server needs: skipping to it then starts without the wait, and in
    // FLAC even when the server is slow. Downloads need none.
    private inner class LosslessAhead(private val player: Player) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!Settings.lossless) return
            val index = player.nextMediaItemIndex
            if (index == C.INDEX_UNSET) return
            val next = player.getMediaItemAt(index).mediaId
            val info = queued[next] ?: return
            if (Downloads.localFile(next) != null) return
            ahead.execute { runCatching { Lossless.prefetch(info.title, info.artist, info.seconds) } }
        }
    }

    // Tells the on-screen log when a song is loading: from when it should play until it first does.
    // Rebuffering mid-song doesn't count, and neither does the next song preparing in the background.
    private class LoadWatcher : Player.Listener {
        private var started: String? = null

        override fun onEvents(player: Player, events: Player.Events) {
            val item = player.currentMediaItem
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && !player.isPlaying) started = null
            if (player.isPlaying) started = item?.mediaId
            val loading = item != null && item.mediaId != started && player.playWhenReady &&
                player.playerError == null && player.playbackState != Player.STATE_ENDED
            LoadLog.update(item?.mediaId, item?.mediaMetadata?.title?.toString().orEmpty(), loading)
        }
    }

    // Controllers don't reliably pass the playable URI, so it travels in requestMetadata. The cache
    // key carries the song id down to the data source above.
    private object SessionCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val playable = mediaItems.map { item ->
                queued[item.mediaId] = SongInfo(
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    seconds = item.requestMetadata.extras?.getInt(EXTRA_SECONDS) ?: 0,
                )
                item.buildUpon()
                    .setUri(item.requestMetadata.mediaUri)
                    .setCustomCacheKey(item.mediaId)
                    .build()
            }
            return Futures.immediateFuture(playable.toMutableList())
        }
    }
}

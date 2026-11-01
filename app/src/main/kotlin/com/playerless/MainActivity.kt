package com.playerless

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

private const val MOST_PLAYED_COUNT = 2
private const val KEY_SCREEN = "screen"
private const val KEY_PLAYLIST = "playlist"

class MainActivity : Activity() {
    private enum class Screen { HOME, SEARCH, DOWNLOADS, PLAYLIST, SETTINGS }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var search: EditText
    private lateinit var titleRow: View
    private lateinit var listTitle: TextView
    private lateinit var playAll: View
    private lateinit var shuffleAll: View
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private lateinit var playerBar: View
    private lateinit var playerTitle: TextView
    private lateinit var playerArtist: TextView
    private lateinit var playPause: ImageButton

    private val songAdapter: SongAdapter =
        SongAdapter(::playFromList, ::actionIcon, ::onSongAction, ::showSongMenu, ::songSubtitle)
    private val homeAdapter = HomeAdapter()
    private var screen = Screen.HOME
    private var playlistId: String? = null
    private var searchResults: List<Song> = emptyList()
    private var searchStatus: String? = null
    @Volatile private var searchId = 0
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerScreen: PlayerScreen? = null
    private var pendingQueue: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.init(this)
        setTheme(Settings.accent.theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Downloads.init(this)
        Downloads.onChanged = ::refreshList
        Playlists.init(this)
        PlayStats.init(this)
        Covers.init(this)

        search = findViewById(R.id.search)
        titleRow = findViewById(R.id.titleRow)
        listTitle = findViewById(R.id.listTitle)
        playAll = findViewById(R.id.playAll)
        shuffleAll = findViewById(R.id.shuffleAll)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        playerBar = findViewById(R.id.player)
        playerTitle = findViewById(R.id.playerTitle)
        playerArtist = findViewById(R.id.playerArtist)
        playPause = findViewById(R.id.playPause)

        // Tiles take one of the two columns, everything else the whole width.
        results.layoutManager = GridLayoutManager(this, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    val isTile = results.adapter === homeAdapter &&
                        homeAdapter.items.getOrNull(position) is HomeItem.Tile
                    return if (isTile) 1 else 2
                }
            }
        }
        results.itemAnimator = null
        // Swapping adapters keeps the pool, so going back to a screen doesn't inflate it again.
        results.recycledViewPool.setMaxRecycledViews(0, 16)

        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(search.text.toString().trim())
                true
            } else {
                false
            }
        }

        findViewById<View>(R.id.searchIcon).setOnClickListener {
            val query = search.text.toString().trim()
            if (query.isEmpty()) {
                search.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
            } else {
                runSearch(query)
            }
        }
        findViewById<View>(R.id.navHome).setOnClickListener { showScreen(Screen.HOME) }
        findViewById<View>(R.id.navDownloads).setOnClickListener { showScreen(Screen.DOWNLOADS) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showScreen(Screen.SETTINGS) }
        playAll.setOnClickListener { playAll(songAdapter.songs) }
        shuffleAll.setOnClickListener { playAll(songAdapter.songs.shuffled()) }
        findViewById<View>(R.id.prev).setOnClickListener { controller?.seekToPrevious() }
        findViewById<View>(R.id.next).setOnClickListener { controller?.seekToNext() }
        playPause.setOnClickListener { controller?.togglePlayPause() }
        playerBar.setOnClickListener { openPlayerScreen() }
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Changing the accent restarts the activity; the settings screen should still be there.
        playlistId = savedInstanceState?.getString(KEY_PLAYLIST)
        showScreen(savedInstanceState?.getString(KEY_SCREEN)?.let(Screen::valueOf) ?: Screen.HOME)
        connect()
    }

    // Bound for the whole life of the activity: a paused player stays alive after Media3 leaves the
    // foreground, and coming back to the app rebuilds nothing.
    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled) return@addListener
            controller = runCatching { future.get() }.getOrNull()?.also { it.addListener(listener) }
            render()
            playerScreen?.resume()
            pendingQueue?.invoke()
            pendingQueue = null
        }, MoreExecutors.directExecutor())
    }

    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state)
        // The EditText has its text back only now; the results didn't survive.
        if (screen == Screen.SEARCH) runSearch(search.text.toString().trim())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
        outState.putString(KEY_PLAYLIST, playlistId)
    }

    override fun onStart() {
        super.onStart()
        // Most played changes while the app is away. Skips the first start: onCreate just built it.
        if (screen == Screen.HOME && controller != null) refreshList()
        playerScreen?.resume()
    }

    override fun onStop() {
        playerScreen?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        Downloads.onChanged = null
        executor.shutdown()
        super.onDestroy()
    }

    // Keeps the app alive; finishing would drop the search results while music keeps playing.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            playerScreen?.isOpen == true -> playerScreen?.close()
            screen == Screen.HOME -> moveTaskToBack(true)
            else -> showScreen(Screen.HOME)
        }
    }

    private fun openPlayerScreen() {
        val opened = playerScreen ?: run {
            val view = layoutInflater.inflate(R.layout.view_player, null)
            val size = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addContentView(view, size)
            PlayerScreen(view, window) { controller }.also { playerScreen = it }
        }
        opened.open()
    }

    private fun showScreen(target: Screen) {
        screen = target
        refreshList()
        results.scrollToPosition(0)
    }

    private fun refreshList() {
        when (screen) {
            Screen.HOME -> showItems(null, homeItems())
            Screen.SETTINGS -> showItems(getString(R.string.settings), settingsItems())
            Screen.SEARCH -> showSongs(null, searchResults, searchStatus)
            Screen.DOWNLOADS -> {
                val songs = Downloads.list()
                val empty = getString(R.string.no_downloads).takeIf { songs.isEmpty() }
                showSongs(getString(R.string.downloads), songs, empty)
            }
            Screen.PLAYLIST -> {
                val playlist = Playlists.get(playlistId)
                if (playlist == null) {
                    showScreen(Screen.HOME)
                } else {
                    val empty = getString(R.string.empty_playlist).takeIf { playlist.songs.isEmpty() }
                    showSongs(playlist.name, playlist.songs, empty)
                }
            }
        }
    }

    private fun showItems(title: String?, items: List<HomeItem>) {
        if (results.adapter !== homeAdapter) results.swapAdapter(homeAdapter, true)
        homeAdapter.items = items
        // Half of the 20 dp gutter; each tile brings the other half as its margin.
        val padding = (10 * resources.displayMetrics.density).toInt()
        results.setPadding(padding, padding, padding, padding)
        showHeader(title, null)
    }

    private fun showSongs(title: String?, songs: List<Song>, message: String?) {
        if (results.adapter !== songAdapter) results.swapAdapter(songAdapter, true)
        songAdapter.songs = songs
        results.setPadding(0, 0, 0, 0)
        showHeader(title, message)
    }

    private fun showHeader(title: String?, message: String?) {
        listTitle.text = title
        titleRow.visibility = if (title == null) View.GONE else View.VISIBLE
        val canPlayAll = screen == Screen.PLAYLIST && message == null
        playAll.visibility = if (canPlayAll) View.VISIBLE else View.GONE
        shuffleAll.visibility = playAll.visibility
        status.text = message
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun homeItems(): List<HomeItem> {
        val playlists = Playlists.all.map { playlist ->
            HomeItem.Tile(playlist.name, coverOf(playlist.songs.firstOrNull()), { showPlaylistMenu(playlist) }) {
                playlistId = playlist.id
                showScreen(Screen.PLAYLIST)
            }
        }
        val create = HomeItem.Tile(getString(R.string.new_playlist), "") {
            askPlaylistName("") { name ->
                Playlists.create(name)
                refreshList()
            }
        }

        val top = PlayStats.top(MOST_PLAYED_COUNT)
        val mostPlayed = top.mapIndexed { index, song ->
            HomeItem.Tile(song.title, coverOf(song), { showSongMenu(song) }) { play(top, index) }
        }.ifEmpty { listOf(HomeItem.Row(getString(R.string.most_played_empty), null)) }

        return playlists + create + HomeItem.Header(getString(R.string.most_played)) + mostPlayed
    }

    private fun coverOf(song: Song?): String = song?.let { Covers.big(Downloads.imageFor(it)) }.orEmpty()

    private fun settingsItems(): List<HomeItem> {
        val quality = getString(R.string.quality_kbps, Settings.quality)
        return listOf(
            HomeItem.Row(getString(R.string.audio_quality), quality, ::chooseQuality),
            HomeItem.Row(getString(R.string.accent), getString(Settings.accent.label), ::chooseAccent),
        )
    }

    private fun chooseAccent() {
        val accents = Accent.entries
        val labels = accents.map { accent ->
            SpannableString(getString(accent.label)).apply {
                setSpan(ForegroundColorSpan(getColor(accent.color)), 0, length, 0)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.accent)
            .setSingleChoiceItems(labels.toTypedArray<CharSequence>(), accents.indexOf(Settings.accent)) { dialog, which ->
                Settings.accent = accents[which]
                dialog.dismiss()
                recreate()
            }
            .show()
    }

    private fun chooseQuality() {
        val qualities = Settings.QUALITIES
        val labels = qualities.map { getString(R.string.quality_kbps, it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_quality)
            .setSingleChoiceItems(labels.toTypedArray(), qualities.indexOf(Settings.quality)) { dialog, which ->
                Settings.quality = qualities[which]
                dialog.dismiss()
                refreshList()
            }
            .show()
    }

    private fun actionIcon(song: Song) = when {
        screen == Screen.DOWNLOADS -> R.drawable.ic_delete
        Downloads.isDownloading(song) -> R.drawable.ic_pending
        Downloads.isDownloaded(song) -> R.drawable.ic_done
        else -> R.drawable.ic_download
    }

    private fun onSongAction(song: Song) {
        if (screen == Screen.DOWNLOADS) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, song.title))
                .setPositiveButton(R.string.delete) { _, _ -> Downloads.delete(song) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else if (!Downloads.isDownloaded(song) && !Downloads.isDownloading(song)) {
            chooseDownloadQuality(song)
        }
    }

    private fun chooseDownloadQuality(song: Song) {
        val best = SaavnApi.kbpsOf(song.url)
        val qualities = Settings.QUALITIES.filter { best != null && it <= best }
        // Nothing to choose: one rendition, or a stream that doesn't name its bitrate.
        if (qualities.size < 2) {
            Downloads.download(song, qualities.firstOrNull())
            return
        }
        val labels = qualities.map { getString(R.string.quality_kbps, it) }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_quality)
            .setItems(labels.toTypedArray()) { _, which -> Downloads.download(song, qualities[which]) }
            .show()
    }

    private fun songSubtitle(song: Song): String {
        if (screen != Screen.DOWNLOADS) return song.artist
        val kbps = song.kbps ?: SaavnApi.kbpsOf(song.url)
        return if (kbps != null) getString(R.string.song_quality, song.artist, kbps) else song.artist
    }

    private fun showSongMenu(song: Song) {
        val openPlaylist = playlistId.takeIf { screen == Screen.PLAYLIST }
        val items = listOfNotNull(
            getString(R.string.add_to_playlist),
            getString(R.string.remove_from_playlist).takeIf { openPlaylist != null },
        )
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    choosePlaylist(song)
                } else if (openPlaylist != null) {
                    Playlists.remove(openPlaylist, song)
                    refreshList()
                }
            }
            .show()
    }

    private fun choosePlaylist(song: Song) {
        val playlists = Playlists.all
        val names = playlists.map { it.name } + getString(R.string.new_playlist)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(names.toTypedArray()) { _, which ->
                val chosen = playlists.getOrNull(which)
                if (chosen != null) {
                    addToPlaylist(chosen, song)
                } else {
                    askPlaylistName("") { name -> addToPlaylist(Playlists.create(name), song) }
                }
            }
            .show()
    }

    private fun addToPlaylist(playlist: Playlist, song: Song) {
        val added = Playlists.add(playlist.id, song)
        val message = if (added) R.string.added_to_playlist else R.string.already_in_playlist
        Toast.makeText(this, getString(message, playlist.name), Toast.LENGTH_SHORT).show()
        refreshList()
    }

    private fun showPlaylistMenu(playlist: Playlist) {
        val items = arrayOf(getString(R.string.rename), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(items) { _, which ->
                if (which == 0) {
                    askPlaylistName(playlist.name) { name ->
                        Playlists.rename(playlist.id, name)
                        refreshList()
                    }
                } else {
                    confirmDelete(playlist)
                }
            }
            .show()
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, playlist.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Playlists.delete(playlist.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPlaylistName(initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            hint = getString(R.string.playlist_name)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onDone(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) }
            .show()
    }

    private fun runSearch(query: String) {
        if (query.isEmpty()) return
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()

        // Downloaded songs show up at once and keep working without a network.
        val local = Downloads.search(query)
        searchResults = local
        searchStatus = getString(R.string.searching).takeIf { local.isEmpty() }
        showScreen(Screen.SEARCH)

        val id = ++searchId
        executor.execute {
            if (id != searchId) return@execute
            val songs = runCatching { SaavnApi.search(query) }
            main.post {
                if (id != searchId || isDestroyed) return@post
                val found = songs.getOrNull()
                val localIds = local.map { it.id }.toSet()
                searchResults = local + found.orEmpty().filterNot { it.id in localIds }
                searchStatus = when {
                    searchResults.isNotEmpty() -> null
                    found == null -> getString(R.string.network_error)
                    else -> getString(R.string.no_results)
                }
                refreshList()
            }
        }
    }

    private fun isOnline() = getSystemService(ConnectivityManager::class.java).activeNetwork != null

    private fun playFromList(position: Int): Unit = play(songAdapter.songs, position)

    // Offline the queue is what's on disk, so skipping never waits on network timeouts.
    private fun playable(songs: List<Song>) =
        if (isOnline()) songs else songs.filter(Downloads::isDownloaded)

    private fun play(songs: List<Song>, position: Int) {
        val queue = playable(songs)
        startQueue(queue, queue.indexOf(songs[position]))
    }

    private fun playAll(songs: List<Song>) = startQueue(playable(songs), 0)

    private fun startQueue(queue: List<Song>, start: Int) {
        val player = controller
        if (player == null) {
            pendingQueue = { startQueue(queue, start) }
            return
        }
        if (start !in queue.indices) {
            Toast.makeText(this, R.string.offline_not_downloaded, Toast.LENGTH_SHORT).show()
            return
        }

        player.setMediaItems(queue.map(::toMediaItem), start, 0L)
        player.prepare()
        player.play()
    }

    private fun toMediaItem(song: Song): MediaItem {
        val uri = Uri.parse(SaavnApi.withQuality(song.url, Settings.quality))
        // The stats keep the song as it came, not this bitrate or this cover file.
        val original = Bundle().apply {
            putString(EXTRA_URL, song.url)
            putString(EXTRA_IMAGE, song.image)
        }
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(original).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(Downloads.imageFor(song).takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    private fun render() {
        playerScreen?.takeIf { it.isOpen }?.render()
        val player = controller
        val metadata = player?.currentMediaItem?.mediaMetadata
        if (metadata == null) {
            playerBar.visibility = View.GONE
            return
        }
        playerBar.visibility = View.VISIBLE
        playerTitle.text = metadata.title
        if (player.playerError != null) {
            playerArtist.setText(R.string.playback_error)
        } else {
            playerArtist.text = metadata.artist
        }
        playPause.setImageResource(player.playPauseIcon)
    }
}

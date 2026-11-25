package com.playerless

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.SpannableString
import android.text.SpannableStringBuilder
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
private const val KEY_ARTIST_NAME = "artist_name"
private const val KEY_ARTIST_ID = "artist_id"
private const val KEY_ALBUM = "album"
private const val LOG_DELAY_MS = 300L
// A lost session is connected again after this, so a service that keeps failing isn't rebound in a loop.
private const val RECONNECT_MS = 1_000L
private const val LOG_TICK_MS = 100L
private const val LOG_LINES = 8
// How long the first catalog to answer a search waits for the other before showing alone.
private const val SEARCH_GRACE_MS = 1_500L

// One search, both catalogs at once. Main thread only, except id, query and local.
private class SearchRun(val id: Int, val query: String, val local: List<Song>) {
    var saavn: Result<List<Song>>? = null
    var youtube: Result<YouTube.Found>? = null
    var waiting = false
    var warmed = false
    val complete get() = saavn != null && youtube != null
}

// An artist's page from YouTube Music, as the screen shows it: named at once, whole once loaded.
// ref is null while the artist is only known by the name a song gives. Main thread only.
private class ArtistView(val name: String, var ref: YouTube.ArtistRef?) {
    var page: YouTube.ArtistPage? = null
    var message: String? = null
    val title get() = page?.name?.ifEmpty { null } ?: ref?.name ?: name
}

// An album's songs, from YouTube Music. Main thread only.
private class AlbumView(val ref: YouTube.AlbumRef) {
    var songs: List<Song> = emptyList()
    var message: String? = null
}

class MainActivity : Activity() {
    private enum class Screen { HOME, SEARCH, DOWNLOADS, PLAYLIST, SETTINGS, EQUALIZER, ARTIST, ALBUM }

    // What back returns to from an artist or an album: the screen it was opened from, scrolled as it was.
    private class Place(val screen: Screen, val scroll: Parcelable?, val artist: ArtistView?, val album: AlbumView?)

    // JioSaavn's searches and YouTube's, one thread each, so a slow catalog never holds up the other.
    private val executor = Executors.newSingleThreadExecutor()
    private val youtubeSearch = Executors.newSingleThreadExecutor()
    // Artist pages and albums, in the order they're asked for: a prefetch answers the tap after it.
    private val pages = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val logTick = Runnable { renderLog() }

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
    private lateinit var loadLog: TextView

    private val songAdapter: SongAdapter =
        SongAdapter(::playFromList, ::actionIcon, ::onSongAction, ::showSongMenu, ::songSubtitle)
    private val homeAdapter = HomeAdapter()
    private var screen = Screen.HOME
    private var playlistId: String? = null
    private var searchResults: List<Song> = emptyList()
    private var searchStatus: String? = null
    // The artist the last search named, shown as the first row of its results.
    private var searchArtist: YouTube.ArtistRef? = null
    private var artistShown: ArtistView? = null
    private var albumShown: AlbumView? = null
    private val backStack = ArrayList<Place>()
    @Volatile private var searchId = 0
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var playerScreen: PlayerScreen? = null
    private var eqView: View? = null
    private var pendingQueue: (() -> Unit)? = null
    private lateinit var skin: Skin

    // Off screen nothing is drawn; whatever changed meanwhile is drawn in onStart.
    private var started = false
    private var stale = false
    // In front and taking touches: the only time an update may ask to be installed.
    private var resumed = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = render()
    }

    // The language picked in the settings, if any, before anything is inflated.
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        Settings.init(this)
        setTheme(Settings.theme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        skin = Skin(this)
        Covers.inks = skin.inks
        // The poster page's grain and grid, under everything that has no background of its own.
        if (Settings.look != Look.CLASSIC) window.setBackgroundDrawable(skin.page())
        Lossless.init(this)
        Downloads.init(this)
        Downloads.onChanged = ::refreshList
        Playlists.init(this)
        PlayStats.init(this)
        Covers.init(this)
        Equalizer.init(this)
        YouTube.init(this)
        YtStream.init(this)
        Updater.init(this)
        Updater.onChanged = {
            if (screen == Screen.SETTINGS) refreshList()
            offerUpdate()
        }

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
        loadLog = findViewById(R.id.loadLog)
        LoadLog.onChanged = ::renderLog
        NowPlaying.onChanged = ::render
        SpotifyImport.onChanged = { if (screen == Screen.HOME) refreshList() }

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
        results.setHasFixedSize(true)
        if (skin.ruleColor != 0) {
            val density = resources.displayMetrics.density
            results.addItemDecoration(RuleDecoration(skin.ruleColor, 20 * density, density))
        }
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
        findViewById<View>(R.id.navHome).setOnClickListener { navigate(Screen.HOME) }
        findViewById<View>(R.id.navDownloads).setOnClickListener { navigate(Screen.DOWNLOADS) }
        findViewById<View>(R.id.navSettings).setOnClickListener { navigate(Screen.SETTINGS) }
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
        savedInstanceState?.let(::reopen)
        connect()
        // Started by that answer. Not again when the activity is only rebuilt, e.g. for a new theme.
        if (savedInstanceState == null && Updater.isStatus(intent)) Updater.onStatus(this, intent)
    }

    // The artist or album that was open when the activity was rebuilt (a new font size, a fold, or
    // Android ending the app in the background), opened again with home behind it.
    private fun reopen(state: Bundle) {
        val name = state.getString(KEY_ARTIST_NAME)
        val album = state.getStringArray(KEY_ALBUM)
        if (screen == Screen.ARTIST && name != null) {
            screen = Screen.HOME
            openArtist(name, state.getString(KEY_ARTIST_ID)?.let { YouTube.ArtistRef(it, name) })
        } else if (screen == Screen.ALBUM && album != null && album.size == 4) {
            screen = Screen.HOME
            openAlbum(YouTube.AlbumRef(album[0], album[1], album[2], album[3]))
        }
    }

    // Android's answer about an update being installed; see Updater.install.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Updater.isStatus(intent)) Updater.onStatus(this, intent)
    }

    // Bound for the whole life of the activity: a paused player stays alive after Media3 leaves the
    // foreground, and coming back to the app rebuilds nothing.
    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        // The session can go away under a living activity (its service stopped, or ended by Android):
        // a new connection then, instead of every tap going to a controller that's gone.
        val future = MediaController.Builder(this, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@MainActivity.controller !== controller) return
                    this@MainActivity.controller = null
                    main.postDelayed({ reconnect() }, RECONNECT_MS)
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled) return@addListener
            controller = runCatching { future.get() }.getOrNull()?.also { it.addListener(listener) }
            render()
            playerScreen?.resume()
            pendingQueue?.invoke()
            pendingQueue = null
            offerUpdate()
        }, MoreExecutors.directExecutor())
    }

    override fun onRestoreInstanceState(state: Bundle) {
        super.onRestoreInstanceState(state)
        // The EditText has its text back only now; the results didn't survive. Without a query
        // there's nothing to search again: home.
        if (screen == Screen.SEARCH) {
            val query = search.text.toString().trim()
            if (query.isEmpty()) showScreen(Screen.HOME) else runSearch(query)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
        outState.putString(KEY_PLAYLIST, playlistId)
        // An artist or an album open now opens again; YouTube's answer is still at hand if the
        // process lives on.
        artistShown?.takeIf { screen == Screen.ARTIST }?.let {
            outState.putString(KEY_ARTIST_NAME, it.name)
            outState.putString(KEY_ARTIST_ID, it.ref?.id)
        }
        albumShown?.takeIf { screen == Screen.ALBUM }?.ref?.let {
            outState.putStringArray(KEY_ALBUM, arrayOf(it.id, it.title, it.year, it.image))
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Most played changes while the app is away, and the player kept going.
        if (stale || screen == Screen.HOME) refreshList()
        render()
        playerScreen?.resume()
        renderLog()
    }

    // The Spotify tool on the PC pushes its file, then reopens the app: that lands here.
    override fun onResume() {
        super.onResume()
        resumed = true
        SpotifyImport.checkForFile(this)
        Updater.checkIfDue()
        offerUpdate()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    // A new version downloaded: Android asks the user to install it. Not while music plays or a
    // download runs, since installing ends the app; it waits for the next time the app is opened.
    private fun offerUpdate() {
        val player = controller ?: return
        val musicOn = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        if (!resumed || musicOn || Downloads.busy || SpotifyImport.progress != null) return
        if (Updater.shouldOffer()) Updater.install()
    }

    override fun onStop() {
        started = false
        // A focused field keeps blinking its cursor, redrawing twice a second an app no one sees.
        search.clearFocus()
        playerScreen?.pause()
        main.removeCallbacks(logTick)
        super.onStop()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        Downloads.onChanged = null
        LoadLog.onChanged = null
        NowPlaying.onChanged = null
        SpotifyImport.onChanged = null
        Updater.onChanged = null
        main.removeCallbacks(logTick)
        executor.shutdown()
        youtubeSearch.shutdown()
        pages.shutdown()
        super.onDestroy()
    }

    // Keeps the app alive; finishing would drop the search results while music keeps playing.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            playerScreen?.isOpen == true -> playerScreen?.close()
            goBack() -> Unit
            screen == Screen.HOME -> moveTaskToBack(true)
            screen == Screen.EQUALIZER -> showScreen(Screen.SETTINGS)
            else -> showScreen(Screen.HOME)
        }
    }

    // A tab of the bottom bar: a fresh start, with nothing to go back through.
    private fun navigate(target: Screen) {
        backStack.clear()
        showScreen(target)
    }

    // Before opening an artist or an album: back returns here.
    private fun leave() {
        backStack += Place(screen, results.layoutManager?.onSaveInstanceState(), artistShown, albumShown)
    }

    private fun goBack(): Boolean {
        val place = backStack.removeLastOrNull() ?: return false
        screen = place.screen
        artistShown = place.artist
        albumShown = place.album
        hideKeyboard()
        refreshList()
        place.scroll?.let { results.layoutManager?.onRestoreInstanceState(it) }
        return true
    }

    private fun openPlayerScreen() {
        val opened = playerScreen ?: run {
            val root = findViewById<ViewGroup>(android.R.id.content)
            val view = layoutInflater.inflate(R.layout.view_player, root, false)
            root.addView(view)
            PlayerScreen(view, window, skin) { controller }.also { playerScreen = it }
        }
        hideKeyboard()
        opened.open()
    }

    private fun showScreen(target: Screen) {
        screen = target
        hideKeyboard()
        refreshList()
        results.scrollToPosition(0)
    }

    // Leaving the search field for another screen shouldn't leave the keyboard over it.
    private fun hideKeyboard() {
        if (!search.hasFocus()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    private fun refreshList() {
        if (!started) {
            stale = true
            return
        }
        stale = false
        val equalizer = screen == Screen.EQUALIZER
        results.visibility = if (equalizer) View.GONE else View.VISIBLE
        eqView?.visibility = if (equalizer) View.VISIBLE else View.GONE
        when (screen) {
            Screen.HOME -> showItems(null, homeItems())
            Screen.SETTINGS -> showItems(getString(R.string.settings), settingsItems())
            Screen.EQUALIZER -> showEqualizer()
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
            // Rebuilt without them, e.g. after Android ended the app in the background: home.
            Screen.ARTIST -> artistShown?.let { showItems(it.title, artistItems(it), it.message) } ?: showScreen(Screen.HOME)
            Screen.ALBUM -> albumShown?.let { showSongs(it.ref.title, it.songs, it.message) } ?: showScreen(Screen.HOME)
        }
    }

    // Each row of albums under its own title, e.g. "Album", then "Singoli ed EP".
    private fun artistItems(shown: ArtistView): List<HomeItem> {
        val page = shown.page ?: return emptyList()
        return page.shelves.flatMap { shelf ->
            listOf(HomeItem.Header(shelf.title)) + shelf.albums.map { album ->
                val label = if (album.year.isEmpty()) album.title else getString(R.string.album_year, album.title, album.year)
                HomeItem.Tile(label, Covers.big(album.image)) { openAlbum(album) }
            }
        }
    }

    // From the search's first row, or from a song's menu by the artist's name alone: YouTube Music
    // is then asked who that is.
    private fun openArtist(name: String, ref: YouTube.ArtistRef?) {
        leave()
        val shown = ArtistView(name, ref)
        artistShown = shown
        val cached = ref?.let { YouTube.cachedArtist(it.id) }
        if (cached != null) {
            shown.page = cached
            shown.message = getString(R.string.no_albums).takeIf { cached.shelves.isEmpty() }
            showScreen(Screen.ARTIST)
            return
        }
        shown.message = getString(R.string.loading)
        showScreen(Screen.ARTIST)
        pages.execute {
            val result = runCatching {
                val found = shown.ref ?: YouTube.find(name).artist
                found to found?.let { YouTube.artist(it.id) }
            }
            main.post {
                val (found, page) = result.getOrNull() ?: (null to null)
                if (found != null) shown.ref = found
                shown.page = page
                shown.message = when {
                    result.isFailure -> getString(R.string.network_error)
                    page == null -> getString(R.string.artist_not_found, name)
                    page.shelves.isEmpty() -> getString(R.string.no_albums)
                    else -> null
                }
                if (artistShown === shown && screen == Screen.ARTIST) refreshList()
            }
        }
    }

    private fun openAlbum(ref: YouTube.AlbumRef) {
        leave()
        val shown = AlbumView(ref)
        albumShown = shown
        // Its songs come from YouTube: its player gets ready while one is picked.
        YtStream.warmUp()
        val cached = YouTube.cachedAlbum(ref.id)
        if (cached != null) {
            shown.songs = cached
            showScreen(Screen.ALBUM)
            return
        }
        shown.message = getString(R.string.loading)
        showScreen(Screen.ALBUM)
        pages.execute {
            val result = runCatching { YouTube.album(ref.id) }
            main.post {
                shown.songs = result.getOrNull().orEmpty()
                shown.message = when {
                    result.isFailure -> getString(R.string.network_error)
                    shown.songs.isEmpty() -> getString(R.string.no_results)
                    else -> null
                }
                if (albumShown === shown && screen == Screen.ALBUM) refreshList()
            }
        }
    }

    // Built the first time, under the loading log, and kept: the list stays as it was behind it.
    private fun showEqualizer() {
        if (eqView == null) {
            val content = findViewById<ViewGroup>(R.id.content)
            val view = layoutInflater.inflate(R.layout.view_eq, content, false)
            content.addView(view, content.indexOfChild(loadLog))
            EqScreen(view)
            eqView = view
        }
        showHeader(getString(R.string.equalizer), null)
    }

    private fun showItems(title: String?, items: List<HomeItem>, message: String? = null) {
        if (results.adapter !== homeAdapter) results.swapAdapter(homeAdapter, true)
        homeAdapter.items = items
        // Half of the 20 dp gutter; each tile brings the other half as its margin.
        val padding = (10 * resources.displayMetrics.density).toInt()
        results.setPadding(padding, padding, padding, padding)
        showHeader(title, message)
    }

    private fun showSongs(title: String?, songs: List<Song>, message: String?) {
        if (results.adapter !== songAdapter) results.swapAdapter(songAdapter, true)
        songAdapter.show(songs, searchLead().takeIf { screen == Screen.SEARCH })
        results.setPadding(0, 0, 0, 0)
        showHeader(title, message)
    }

    // The artist the search named, above its songs; their picture once their page has come.
    private fun searchLead(): Lead? {
        val artist = searchArtist ?: return null
        val image = YouTube.cachedArtist(artist.id)?.image.orEmpty()
        return Lead(artist.name, getString(R.string.artist), image) { openArtist(artist.name, artist) }
    }

    private fun showHeader(title: String?, message: String?) {
        listTitle.text = title
        titleRow.visibility = if (title == null) View.GONE else View.VISIBLE
        // Downloads too: offline they are the whole library.
        val canPlayAll = (screen == Screen.PLAYLIST || screen == Screen.DOWNLOADS || screen == Screen.ALBUM) && message == null
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

        val importing = listOfNotNull(SpotifyImport.progress?.let { HomeItem.Row(it, null) })
        return importing + playlists + create + HomeItem.Header(getString(R.string.most_played)) + mostPlayed
    }

    private fun coverOf(song: Song?): String = song?.let { Covers.big(Downloads.imageFor(it)) }.orEmpty()

    private fun settingsItems(): List<HomeItem> {
        val eq = Equalizer.state
        val eqSummary = if (eq.enabled) resources.getQuantityString(R.plurals.eq_on, eq.points.size, eq.points.size) else getString(R.string.eq_off)
        return listOfNotNull(
            HomeItem.Row(getString(R.string.audio_quality), qualityLabel(Settings.quality), ::chooseQuality),
            HomeItem.Row(getString(R.string.lossless_server), Settings.losslessServer, ::askLosslessServer)
                .takeIf { Settings.lossless },
            HomeItem.Row(getString(R.string.lossless_mobile), getString(if (Settings.losslessOnMobile) R.string.lossless_mobile_on else R.string.lossless_mobile_off)) {
                Settings.losslessOnMobile = !Settings.losslessOnMobile
                refreshList()
            }.takeIf { Settings.lossless },
            HomeItem.Row(getString(R.string.lossless_upgrade), upgradeSummary(), ::confirmUpgrade)
                .takeIf { Settings.lossless && Downloads.list().isNotEmpty() },
            HomeItem.Row(getString(R.string.equalizer), eqSummary) { showScreen(Screen.EQUALIZER) },
            HomeItem.Row(getString(R.string.look), getString(Settings.look.label), ::chooseLook),
            // The posters bring their own inks: the accent is the classic theme's.
            HomeItem.Row(getString(R.string.accent), getString(Settings.accent.label), ::chooseAccent)
                .takeIf { Settings.look == Look.CLASSIC },
            HomeItem.Row(getString(R.string.language), languageLabel(Settings.language), ::chooseLanguage),
            HomeItem.Row(getString(R.string.update), updateSummary()) {
                if (Updater.state == Updater.State.READY) Updater.install() else Updater.check()
            },
        )
    }

    private fun updateSummary(): String = when (Updater.state) {
        Updater.State.IDLE -> getString(R.string.update_idle, Updater.installed)
        Updater.State.CHECKING -> getString(R.string.update_checking)
        Updater.State.DOWNLOADING -> getString(R.string.update_downloading, Updater.available)
        Updater.State.READY -> getString(R.string.update_ready, Updater.available)
        Updater.State.CURRENT -> getString(R.string.update_current, Updater.installed)
        Updater.State.FAILED -> getString(R.string.update_check_failed, Updater.installed)
    }

    private fun upgradeSummary(): String {
        val (done, total) = Downloads.upgrade ?: return getString(R.string.lossless_upgrade_hint)
        return getString(R.string.lossless_upgrade_running, done, total)
    }

    // Downloads already on the phone play from the phone, so lossless reaches them only by swapping
    // their files: FLAC where the server has it. Big files, hence the warning first. The swap runs in
    // UpgradeService, which keeps going with the app closed and counts the songs in a notification.
    private fun confirmUpgrade() {
        if (Downloads.upgrade != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.lossless_upgrade)
            .setMessage(getString(R.string.lossless_upgrade_confirm, Downloads.list().size))
            .setPositiveButton(R.string.lossless_upgrade_start) { _, _ ->
                startForegroundService(Intent(this, UpgradeService::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseLook() {
        val looks = Look.entries
        val labels = looks.map { getString(it.label) }
        AlertDialog.Builder(this)
            .setTitle(R.string.look)
            .setSingleChoiceItems(labels.toTypedArray(), looks.indexOf(Settings.look)) { dialog, which ->
                dialog.dismiss()
                if (looks[which] == Settings.look) return@setSingleChoiceItems
                Settings.look = looks[which]
                recreate()
            }
            .show()
    }

    private fun languageLabel(tag: String) = if (tag.isEmpty()) getString(R.string.language_system) else Lang.name(tag)

    // Like the theme, the whole screen is rebuilt in the new language.
    private fun chooseLanguage() {
        val tags = Lang.CHOICES
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(tags.map(::languageLabel).toTypedArray(), tags.indexOf(Settings.language)) { dialog, which ->
                dialog.dismiss()
                if (tags[which] == Settings.language) return@setSingleChoiceItems
                Settings.language = tags[which]
                recreate()
            }
            .show()
    }

    private fun qualityLabel(kbps: Int) =
        getString(if (kbps == Lossless.KBPS) R.string.quality_lossless else R.string.quality_saavn, kbps)

    private fun askLosslessServer() {
        val current = Settings.losslessServer
        val input = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            setSingleLine()
            hint = Lossless.DEFAULT_SERVER
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.lossless_server)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.losslessServer = input.text.toString()
                refreshList()
            }
            .setNeutralButton(R.string.lossless_server_default) { _, _ ->
                Settings.losslessServer = ""
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        val labels = qualities.map(::qualityLabel)
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

    // Lossless first, from the lossless server, then the renditions of the song's own source. A null
    // bitrate downloads the song as it streams.
    private fun chooseDownloadQuality(song: Song) {
        val best = SaavnApi.kbpsOf(song.url)
        val choices = ArrayList<Pair<Int?, String>>()
        if (Lossless.SUPPORTED) choices += Lossless.KBPS to qualityLabel(Lossless.KBPS)
        when {
            YouTube.isYouTube(song) -> choices += null to getString(R.string.quality_youtube)
            best == null -> choices += null to getString(R.string.quality_saavn_stream)
            else -> Settings.QUALITIES.filter { it != Lossless.KBPS && it <= best }.forEach { choices += it to qualityLabel(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.download_quality)
            .setItems(choices.map { it.second }.toTypedArray()) { _, which -> Downloads.download(song, choices[which].first) }
            .show()
    }

    private fun songSubtitle(song: Song): String {
        // An album is all YouTube: saying so on every row tells nothing.
        if (screen == Screen.ALBUM) return song.artist
        if (screen != Screen.DOWNLOADS) {
            return if (YouTube.isYouTube(song)) getString(R.string.from_youtube, song.artist) else song.artist
        }
        val kbps = song.kbps ?: SaavnApi.kbpsOf(song.url)
        return if (kbps != null) getString(R.string.song_quality, song.artist, kbps) else song.artist
    }

    private fun showSongMenu(song: Song) {
        val openPlaylist = playlistId.takeIf { screen == Screen.PLAYLIST }
        // The first artist a song credits: "Lazza, Sfera Ebbasta" is Lazza's.
        val artist = song.artist.substringBefore(',').trim()
        val items = listOfNotNull(
            getString(R.string.add_to_playlist) to { choosePlaylist(song) },
            openPlaylist?.let {
                getString(R.string.remove_from_playlist) to {
                    Playlists.remove(it, song)
                    refreshList()
                }
            },
            artist.takeIf { it.isNotEmpty() }?.let { getString(R.string.go_to_artist, it) to { openArtist(it, null) } },
        )
        AlertDialog.Builder(this)
            .setTitle(song.title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
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
        hideKeyboard()

        // Downloaded songs show up at once and keep working without a network.
        val local = Downloads.search(query)
        searchResults = local
        searchArtist = null
        searchStatus = getString(R.string.searching).takeIf { local.isEmpty() }
        backStack.clear()
        showScreen(Screen.SEARCH)

        // Both catalogs at once, each on its own thread. A search typed meanwhile skips the queue.
        val run = SearchRun(++searchId, query, local)
        executor.execute {
            if (run.id != searchId) return@execute
            val found = runCatching { SaavnApi.search(query) }
            main.post {
                run.saavn = found
                arrived(run)
            }
        }
        youtubeSearch.execute {
            if (run.id != searchId) return@execute
            val found = runCatching { YouTube.find(query) }
            main.post {
                run.youtube = found
                arrived(run)
            }
        }
    }

    // The first catalog to answer waits a moment for the other, so the list usually shows once and
    // whole. A catalog slower than that doesn't hold the other back: it joins the list when it comes.
    private fun arrived(run: SearchRun) {
        if (run.id != searchId || isDestroyed) return
        if (run.complete) {
            showResults(run)
        } else if (!run.waiting) {
            run.waiting = true
            main.postDelayed({ if (!run.complete) showResults(run) }, SEARCH_GRACE_MS)
        }
    }

    private fun showResults(run: SearchRun) {
        if (run.id != searchId || isDestroyed) return
        val saavn = run.saavn
        val youtube = run.youtube
        val found = merge(run.query, saavn?.getOrNull().orEmpty(), youtube?.getOrNull()?.songs.orEmpty())
        val localIds = run.local.mapTo(HashSet()) { it.id }
        searchResults = run.local + found.filterNot { it.id in localIds }
        val artist = youtube?.getOrNull()?.artist
        if (artist != null && artist.id != searchArtist?.id) {
            searchArtist = artist
            prefetchArtist(artist)
        }
        val failed = saavn?.isFailure == true && youtube?.isFailure == true
        searchStatus = when {
            searchResults.isNotEmpty() -> null
            !run.complete -> getString(R.string.searching)
            failed -> getString(R.string.network_error)
            else -> getString(R.string.no_results)
        }
        if (screen == Screen.SEARCH) refreshList()
        if (!run.warmed && found.any(YouTube::isYouTube)) {
            run.warmed = true
            YtStream.warmUp()
        }
    }

    // Whoever searches an artist likely opens them: their page comes meanwhile, and with it the
    // picture for the search's first row.
    private fun prefetchArtist(artist: YouTube.ArtistRef) {
        if (YouTube.cachedArtist(artist.id) != null) return
        pages.execute {
            if (runCatching { YouTube.artist(artist.id) }.isFailure) return@execute
            main.post { if (screen == Screen.SEARCH && searchArtist === artist && !isDestroyed) refreshList() }
        }
    }

    // Best match first: the more of the query a song has in its title and artist, the higher. For
    // artists JioSaavn lacks it fills its answer with other songs of the same name; the song itself
    // then comes from YouTube and must still come first. On a tie JioSaavn wins: it starts at once
    // and goes up to 320 kbps. A YouTube song JioSaavn also has is left out, unless JioSaavn has only
    // its clean edit: then the uncensored one from YouTube takes that place.
    private fun merge(query: String, saavn: List<Song>, youtube: List<Song>): List<Song> {
        val words = Match.words(query)
        val uncensored = saavn.filter { it.explicit }.mapTo(HashSet(), Match::key)
        youtube.filter { it.explicit }.mapTo(uncensored, Match::key)
        val kept = saavn.filter { it.explicit || Match.key(it) !in uncensored }
        val onSaavn = kept.mapTo(HashSet(), Match::key)
        return (kept + youtube.filterNot { Match.key(it) in onSaavn })
            .map { it to Match.score(words, it) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // Shown only past a short wait, so songs that start at once never flash it.
    private fun renderLog() {
        main.removeCallbacks(logTick)
        if (!started) return
        // The clock keeps moving while it loads: proof the app isn't stuck.
        if (LoadLog.loading) main.postDelayed(logTick, LOG_TICK_MS)
        if (!LoadLog.visible || (LoadLog.loading && LoadLog.elapsedMs < LOG_DELAY_MS)) {
            loadLog.visibility = View.GONE
            return
        }
        val text = SpannableStringBuilder()
        if (LoadLog.loading) {
            val seconds = String.format(Lang.locale, "%.1f", LoadLog.elapsedMs / 1000.0)
            text.append(getString(R.string.loading_song, LoadLog.title, seconds), ForegroundColorSpan(skin.panelTextTitle), 0)
        }
        val lines = LoadLog.lines.takeLast(LOG_LINES)
        lines.forEachIndexed { index, line ->
            if (text.isNotEmpty()) text.append('\n')
            // A failure in the accent color; otherwise the newest step stands out from the ones done.
            val color = when {
                LoadLog.isFailure(line) -> skin.accent
                index == lines.lastIndex -> skin.panelTextLatest
                else -> null
            }
            if (color != null) text.append(line, ForegroundColorSpan(color), 0) else text.append(line)
        }
        loadLog.text = text
        loadLog.visibility = View.VISIBLE
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

    // Unless one is on its way already, or the activity is gone.
    private fun reconnect() {
        if (isDestroyed || controller?.isConnected == true) return
        controllerFuture?.let {
            if (!it.isDone) return
            MediaController.releaseFuture(it)
        }
        connect()
    }

    private fun startQueue(queue: List<Song>, start: Int) {
        val player = controller?.takeIf { it.isConnected }
        if (player == null) {
            pendingQueue = { startQueue(queue, start) }
            reconnect()
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
            putInt(EXTRA_SECONDS, song.seconds)
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
        if (!started) return
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

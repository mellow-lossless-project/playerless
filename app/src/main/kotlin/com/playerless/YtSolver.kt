package com.playerless

import android.content.Context
import java.io.File

// YouTube's player script holds the functions that unscramble stream links. The script is kept on
// disk and checked once a day; yt-dlp's solver runs it in a hidden WebView, left open for the next
// songs. Ported from Convx's EjsNTransformSolver and PlayerJsFetcher.
object YtSolver {
    private const val CHECK_EVERY_MS = 24 * 3600_000L
    private const val RETRY_BROKEN_MS = 10 * 60_000L
    // The TV variant of the player: since 2026-11-23 the main one ("player_ias") builds a class at
    // load time that the solver strips ("a.addOnDisposeCallback is not a function"); yt-dlp moved to
    // this variant with the same solver, 0.8.0. Its files are cached under "tv", so a main one cached
    // for the same player hash isn't reused.
    private const val PLAYER_URL = "https://www.youtube.com/s/player/%s/tv-player-ias.vflset/tv-player-ias.js"
    private val HASH = Regex("""player\\?/([A-Za-z0-9_-]{6,})\\?/""")
    private val STS = Regex("""(?:signatureTimestamp|sts)\s*[:=]\s*(\d{5})""")
    private val SCRIPTS = listOf("yt.solver.lib.min.js", "yt.solver.core.js")

    // The player's version goes in every stream request, so the links match these functions.
    class Player(val signatureTimestamp: Int, val page: JsPage)

    private var page: JsPage? = null
    private var pageHash: String? = null

    // A player the solver can't read fails the same way for every song: say so at once.
    private var broken: String? = null
    private var brokenHash: String? = null
    private var brokenUntil = 0L

    // Blocking. refresh asks YouTube for its current player even if the day isn't over.
    @Synchronized
    fun get(context: Context, log: (String) -> Unit, refresh: Boolean = false): Player {
        val dir = File(context.cacheDir, "yt").apply { mkdirs() }
        val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
        var hash = prefs.getString("player", null)
        var sts = prefs.getInt("player_sts", 0)
        val stale = System.currentTimeMillis() - prefs.getLong("player_checked", 0) > CHECK_EVERY_MS
        if (hash == null || refresh || stale || !File(dir, playerFile(hash)).exists()) {
            val iframe = Http.get("https://www.youtube.com/iframe_api", mapOf("User-Agent" to YouTube.WEB_UA))
            val current = HASH.find(iframe)?.groupValues?.get(1) ?: throw YtException(Lang.get(R.string.yt_player_unknown))
            if (current != hash || !File(dir, playerFile(current)).exists()) {
                log(Lang.get(R.string.yt_player_download))
                val code = Http.get(PLAYER_URL.format(current), mapOf("User-Agent" to YouTube.WEB_UA))
                sts = STS.find(code)?.groupValues?.get(1)?.toInt() ?: throw YtException(Lang.get(R.string.yt_player_unreadable))
                dir.listFiles()?.forEach { if (it.name.startsWith("player-") || it.name.startsWith("prep-")) it.delete() }
                File(dir, playerFile(current)).writeText(code)
                hash = current
            }
            prefs.edit()
                .putString("player", hash)
                .putInt("player_sts", sts)
                .putLong("player_checked", System.currentTimeMillis())
                .apply()
        }

        val open = page
        if (open != null && !open.dead && pageHash == hash) return Player(sts, open)
        if (hash == brokenHash && System.currentTimeMillis() < brokenUntil) throw YtException(broken.orEmpty())
        open?.close()
        page = null

        for (name in SCRIPTS) {
            context.assets.open("yt/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        val prepared = File(dir, prepFile(hash))
        // Preparing a new player takes seconds; the line says why the wait is longer.
        log(Lang.get(if (prepared.exists()) R.string.yt_solver_start else R.string.yt_solver_prepare))
        val html = context.assets.open("yt/solver.html").bufferedReader().use { it.readText() }
        // Written aside and renamed, so a half-written file can't break the next start.
        val created = JsPage.open(context, "file://${dir.path}/", html) { text ->
            val part = File(dir, "prep.part")
            part.writeText(text)
            part.renameTo(prepared)
        }
        try {
            created.call("load('${prepFile(hash)}','${playerFile(hash)}')", 120_000)
        } catch (e: YtException) {
            created.close()
            prepared.delete()
            broken = Lang.get(R.string.yt_solver_failed, e.message.orEmpty())
            brokenHash = hash
            brokenUntil = System.currentTimeMillis() + RETRY_BROKEN_MS
            throw YtException(broken!!)
        }
        page = created
        pageHash = hash
        return Player(sts, created)
    }

    private fun playerFile(hash: String?) = "player-tv-$hash.js"

    private fun prepFile(hash: String?) = "prep-tv-$hash.js"
}

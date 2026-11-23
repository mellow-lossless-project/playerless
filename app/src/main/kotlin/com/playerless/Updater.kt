package com.playerless

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Keeps the app current with its GitHub releases. At most every CHECK_MS, one request asks GitHub
// for the latest release: it answers with a redirect to its tag, and nothing else. A newer version's
// APK (under 1 MB) comes down behind the scenes, and Android is asked to install it, which takes one
// tap from the user: Android 10 asks for every app that doesn't come from a store. Only while the
// app is in front and nothing would be cut short: installing ends the app, its music and downloads.
object Updater {
    const val ACTION_STATUS = "com.playerless.UPDATE_STATUS"
    private const val REPO = "https://github.com/mellow-lossless-project/playerless"
    private const val APK = "Playerless.apk"
    private const val CHECK_MS = 6 * 3600_000L
    // Without a network the check fails at once; it's tried again after this, not at every start.
    private const val RETRY_MS = 30 * 60_000L
    // A version is put in front of the user by itself once a day at most: turned down, it waits.
    // The settings row installs it any time.
    private const val ASK_AGAIN_MS = 24 * 3600_000L
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    enum class State { IDLE, CHECKING, DOWNLOADING, READY, CURRENT, FAILED }

    // Main thread.
    var state = State.IDLE
        private set
    // The newer version on GitHub, e.g. "1.6", once found.
    var available: String? = null
        private set
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var ready: File? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("updater", Context.MODE_PRIVATE)
    }

    // "1.5", as built into this APK.
    val installed: String by lazy { info().versionName.orEmpty() }

    // Main thread. On every start of the app; asks GitHub only when it's time.
    fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong("checked_at", 0) >= CHECK_MS) check()
    }

    // Main thread. Asks GitHub now, and downloads what's newer.
    fun check() {
        if (state == State.CHECKING || state == State.DOWNLOADING) return
        set(State.CHECKING)
        executor.execute {
            val result = runCatching { fetchLatest() }
            main.post {
                val now = System.currentTimeMillis()
                result.onSuccess { file ->
                    prefs.edit().putLong("checked_at", now).apply()
                    ready = file
                    set(if (file != null) State.READY else State.CURRENT)
                }
                result.onFailure {
                    prefs.edit().putLong("checked_at", now - CHECK_MS + RETRY_MS).apply()
                    set(State.FAILED)
                }
            }
        }
    }

    // Main thread. Whether to put the install in front of the user without being asked: a version is
    // ready that wasn't offered in the last day.
    fun shouldOffer(): Boolean {
        val version = available ?: return false
        if (state != State.READY) return false
        return prefs.getString("asked", null) != version ||
            System.currentTimeMillis() - prefs.getLong("asked_at", 0) >= ASK_AGAIN_MS
    }

    // Main thread. Hands the downloaded APK to Android, which shows its install dialog: the answer
    // comes back to MainActivity as ACTION_STATUS, see onStatus.
    fun install() {
        val file = ready ?: return
        prefs.edit().putString("asked", available).putLong("asked_at", System.currentTimeMillis()).apply()
        executor.execute {
            val result = runCatching {
                val installer = appContext.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setSize(file.length())
                val session = installer.openSession(installer.createSession(params))
                session.use {
                    file.inputStream().use { input ->
                        it.openWrite(APK, 0, file.length()).use { output ->
                            input.copyTo(output)
                            it.fsync(output)
                        }
                    }
                    // Not immutable: Android writes the outcome into it.
                    val status = Intent(appContext, MainActivity::class.java).setAction(ACTION_STATUS)
                    it.commit(PendingIntent.getActivity(appContext, 1, status, PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
                }
            }
            result.onFailure { e ->
                main.post { toast(appContext.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    // Main thread, from MainActivity: what Android did with the APK.
    fun onStatus(activity: Activity, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) activity.startActivity(confirm)
            }
            // The new version replaces this one, which ends here. Turned down: see ASK_AGAIN_MS.
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                toast(appContext.getString(R.string.update_failed, message))
            }
        }
    }

    // On the executor. The APK of a newer release, downloaded; null when this one is the latest.
    private fun fetchLatest(): File? {
        val tag = latestTag() ?: throw IOException("no release")
        val dir = appContext.cacheDir
        val name = "update-${tag.replace(UNSAFE, "_")}.apk"
        // What a previous version downloaded for itself.
        dir.listFiles { file -> file.name.startsWith("update-") && file.name != name }?.forEach { it.delete() }
        val version = tag.trimStart('v', 'V')
        if (!newer(version, installed)) {
            File(dir, name).delete()
            main.post { available = null }
            return null
        }
        main.post {
            available = version
            set(State.DOWNLOADING)
        }
        val file = File(dir, name)
        if (!isUpdate(file)) {
            download("$REPO/releases/download/$tag/$APK", file)
            if (!isUpdate(file)) {
                file.delete()
                throw IOException("not an update of this app")
            }
        }
        return file
    }

    // GitHub sends /releases/latest on to /releases/tag/<tag>. HEAD: the page itself isn't needed.
    private fun latestTag(): String? {
        val conn = URL("$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            if (conn.responseCode !in 300..399) return null
            return conn.getHeaderField("Location")?.substringAfter("/releases/tag/", "")?.takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }

    // Through a .part file, so the APK only ever exists whole.
    private fun download(url: String, target: File) {
        val part = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            val written = conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            val expected = conn.contentLengthLong
            if (expected >= 0 && written != expected) throw IOException("truncated download")
            if (!part.renameTo(target)) throw IOException("rename failed")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    // This app, in a higher version. Android checks the signature when it installs.
    private fun isUpdate(file: File): Boolean {
        if (!file.exists()) return false
        val archive = appContext.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        return archive.packageName == appContext.packageName && code(archive) > code(info())
    }

    // "1.10" is newer than "1.9".
    private fun newer(version: String, than: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: 0 }
        val b = than.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun info(): PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun set(value: State) {
        state = value
        onChanged?.invoke()
    }

    private fun toast(text: String) = Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
}

package com.playerless

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

// Everything the desktop app imports, read over USB:
//   adb exec-out content read --uri content://com.playerless.export/data
//   adb exec-out content read --uri content://com.playerless.export/music/<file>
// The manifest asks for DUMP, which adb's shell has and no installed app can get, so nothing on the
// phone can read through here. Nothing is copied on the phone either: files go straight to the PC.
class ExportProvider : ContentProvider() {
    private val fileName = Regex("[A-Za-z0-9_-]+\\.(m4a|jpg)")

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Sola lettura")
        val context = context ?: throw FileNotFoundException()
        val path = uri.pathSegments
        val file = when {
            path == listOf("data") -> return dataFile(context)
            path.size == 2 && path[0] == "music" && fileName.matches(path[1]) -> File(context.filesDir, "music/${path[1]}")
            else -> throw FileNotFoundException(uri.toString())
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Written to a file that's deleted once open: the descriptor keeps it readable until adb is done.
    private fun dataFile(context: Context): ParcelFileDescriptor {
        val file = File.createTempFile("export", ".json", context.cacheDir)
        try {
            file.writeText(data(context).toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            file.delete()
        }
    }

    // Read from disk, not from the objects the app keeps in memory: those are main-thread only, and
    // every change is saved at once anyway.
    private fun data(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val music = File(context.filesDir, "music")
        // Sizes let the PC tell a whole file from an error message: adb mixes both into one stream.
        val downloads = readArray(File(music, "index.json"))
        for (i in 0 until downloads.length()) {
            val song = downloads.getJSONObject(i)
            val name = song.optString("id").replace(Regex("[^A-Za-z0-9_-]"), "_")
            song.put("file", name)
            song.put("audioBytes", File(music, "$name.m4a").length())
            song.put("coverBytes", File(music, "$name.jpg").length())
        }
        return JSONObject()
            .put("format", "playerless")
            .put("version", 1)
            .put(
                "settings",
                JSONObject()
                    .put("quality", prefs.getInt("quality", Settings.DEFAULT_QUALITY))
                    .put("accent", prefs.getString("accent", null)),
            )
            .put(
                "equalizer",
                JSONObject()
                    .put("enabled", prefs.getBoolean("eq_on", false))
                    .put("points", prefs.getString("eq_points", null)?.let { runCatching { JSONArray(it) }.getOrNull() }),
            )
            .put("playlists", readArray(File(context.filesDir, "playlists.json")))
            .put("plays", readArray(File(context.filesDir, "plays.json")))
            .put("downloads", downloads)
    }

    // Not readJson: a file the app can't parse is the app's to set aside, not an export's.
    private fun readArray(file: File): JSONArray = try {
        JSONArray(String(AtomicFile(file).readFully()))
    } catch (_: Exception) {
        JSONArray()
    }

    override fun getType(uri: Uri): String? = null

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}

package com.playerless

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// Minimal cover loader: replaces Coil/Glide, which would outweigh the rest of the app.
object Covers {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    // A fresh one per decode: the two threads decode at once, and a decode writes into its options.
    // Downloaded covers are 500x500; a 48 dp row (126 px here) only needs a third of that.
    private fun options(row: Boolean) = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        if (row) inSampleSize = 3
    }

    // The CDN says max-age of a year, so this alone keeps covers on disk: no network, works offline.
    fun init(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "http")
        executor.execute { HttpResponseCache.install(dir, 32L * 1024 * 1024) }
    }

    // Search returns 150x150 covers, too small for a home tile. JioSaavn names the size in the
    // file, YouTube after an "=".
    private val youtubeSize = Regex("=w\\d+-h\\d+")

    fun big(url: String) = url.replace("150x150", "500x500").replace(youtubeSize, "=w500-h500")

    // The two inks a poster theme prints covers in.
    class Inks(val dark: Int, val light: Int) {
        val key = "#print${Integer.toHexString(dark)}-${Integer.toHexString(light)}"
    }

    // Set by the activity from its theme; null shows covers as they are.
    @Volatile var inks: Inks? = null

    // printed: in the theme's inks, if it has any. keepOriginal keeps the cover as it is at hand too,
    // for the full-screen player, which shows it while a finger is on the cover.
    fun load(url: String, view: ImageView, row: Boolean = false, printed: Boolean = true, keepOriginal: Boolean = false) {
        val inks = if (printed) inks else null
        val plain = if (row) "$url#row" else url
        val key = if (inks != null) plain + inks.key else plain
        view.tag = key
        val cached = cache.get(key)
        show(view, cached, inks != null)
        if (cached != null || url.isEmpty()) return

        executor.execute {
            // The row scrolled away while this waited in line.
            if (view.tag != key) return@execute
            val original = cache.get(plain) ?: fetch(url, row) ?: return@execute
            val bitmap = if (inks == null) original else Print.apply(original, inks.dark, inks.light)
            if (inks != null && keepOriginal) cache.put(plain, original)
            cache.put(key, bitmap)
            main.post { if (view.tag == key) show(view, bitmap, inks != null) }
        }
    }

    // A printed cover keeps its grain sharp: scaled dot for dot, not smoothed into grey.
    private fun show(view: ImageView, bitmap: Bitmap?, printed: Boolean) {
        if (bitmap == null || !printed) {
            view.setImageBitmap(bitmap)
        } else {
            view.setImageDrawable(BitmapDrawable(view.resources, bitmap).apply { isFilterBitmap = false })
        }
    }

    private fun fetch(url: String, row: Boolean): Bitmap? = if (url.startsWith("file:")) {
        BitmapFactory.decodeFile(Uri.parse(url).path, options(row))
    } else {
        download(url)
    }

    private fun download(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.inputStream.use { BitmapFactory.decodeStream(it, null, options(row = false)) }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

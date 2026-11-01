package com.playerless

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }

    // Downloaded covers are 500x500; a 48 dp row (126 px here) only needs a third of that.
    private val rowOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inSampleSize = 3
    }

    // The CDN says max-age of a year, so this alone keeps covers on disk: no network, works offline.
    fun init(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "http")
        executor.execute { HttpResponseCache.install(dir, 32L * 1024 * 1024) }
    }

    // Search returns 150x150 covers, too small for a home tile.
    fun big(url: String) = url.replace("150x150", "500x500")

    fun load(url: String, view: ImageView, row: Boolean = false) {
        val key = if (row) "$url#row" else url
        view.tag = key
        val cached = cache.get(key)
        view.setImageBitmap(cached)
        if (cached != null || url.isEmpty()) return

        executor.execute {
            // The row scrolled away while this waited in line.
            if (view.tag != key) return@execute
            val bitmap = fetch(url, row) ?: return@execute
            cache.put(key, bitmap)
            main.post { if (view.tag == key) view.setImageBitmap(bitmap) }
        }
    }

    private fun fetch(url: String, row: Boolean): Bitmap? = if (url.startsWith("file:")) {
        BitmapFactory.decodeFile(Uri.parse(url).path, if (row) rowOptions else options)
    } else {
        download(url)
    }

    private fun download(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            conn.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

package com.playerless

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Blocking HTTP for the YouTube code: call off the main thread.
object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url, headers, null)

    fun post(url: String, body: String, headers: Map<String, String>): String =
        request(url, headers, body)

    // The status of a request for one byte: whether a server would really serve that part of a file.
    // No disconnect: the connection goes back to the shared pool, where the player's request for
    // the same file can pick it up instead of opening its own.
    fun probe(url: String, byte: Long, userAgent: String): Int {
        val conn = open(url, mapOf("User-Agent" to userAgent, "Range" to "bytes=$byte-$byte"))
        return try {
            conn.inputStream.use { it.readBytes() }
            conn.responseCode
        } catch (_: IOException) {
            conn.responseCode
        }
    }

    private fun request(url: String, headers: Map<String, String>, body: String?): String {
        val conn = open(url, headers)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // useCaches off: the cover cache is installed for every connection and would keep these too.
    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 15_000
            useCaches = false
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
}

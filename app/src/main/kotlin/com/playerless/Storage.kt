package com.playerless

import android.util.AtomicFile
import org.json.JSONException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

// Null when the file doesn't exist. One that can't be parsed is set aside, so it neither crashes
// every start nor gets overwritten by the next save.
fun <T : Any> AtomicFile.readJson(parse: (String) -> T): T? {
    val text = try {
        String(readFully())
    } catch (_: FileNotFoundException) {
        return null
    }
    return try {
        parse(text)
    } catch (_: JSONException) {
        baseFile.renameTo(File("${baseFile.path}.bad"))
        null
    }
}

// A full disk keeps the previous file; losing one save is better than crashing mid-song.
fun AtomicFile.writeText(text: String) {
    var stream: FileOutputStream? = null
    try {
        stream = startWrite()
        stream.write(text.toByteArray())
        finishWrite(stream)
    } catch (_: IOException) {
        stream?.let(::failWrite)
    }
}
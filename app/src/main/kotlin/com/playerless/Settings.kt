package com.playerless

import android.content.Context
import android.content.SharedPreferences

enum class Accent(val label: Int, val theme: Int, val color: Int) {
    RED(R.string.accent_red, R.style.AppTheme, R.color.accent_red),
    ORANGE(R.string.accent_orange, R.style.AppTheme_Orange, R.color.accent_orange),
    YELLOW(R.string.accent_yellow, R.style.AppTheme_Yellow, R.color.accent_yellow),
    GREEN(R.string.accent_green, R.style.AppTheme_Green, R.color.accent_green),
    CYAN(R.string.accent_cyan, R.style.AppTheme_Cyan, R.color.accent_cyan),
    BLUE(R.string.accent_blue, R.style.AppTheme_Blue, R.color.accent_blue),
    PURPLE(R.string.accent_purple, R.style.AppTheme_Purple, R.color.accent_purple),
    PINK(R.string.accent_pink, R.style.AppTheme_Pink, R.color.accent_pink),
    WHITE(R.string.accent_white, R.style.AppTheme_White, R.color.accent_white),
}

// The app's look. Classic wears the accent the user picks; the posters bring their own inks.
enum class Look(val label: Int, val theme: Int) {
    CLASSIC(R.string.look_classic, 0),
    POSTER_DARK(R.string.look_poster_dark, R.style.AppTheme_PosterDark),
    POSTER_PAPER(R.string.look_poster_paper, R.style.PosterPaper),
}

object Settings {
    // Best first: lossless, then JioSaavn's renditions. YouTube always streams its best, Opus at
    // about 130 kbps. Lossless unless chosen otherwise, where the phone decodes FLAC: a song never
    // waits on it more than a few seconds (see PlaybackService).
    val QUALITIES = if (Lossless.SUPPORTED) listOf(Lossless.KBPS, 320, 160, 96) else listOf(320, 160, 96)
    val DEFAULT_QUALITY = QUALITIES[0]

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var quality: Int
        get() = prefs.getInt("quality", DEFAULT_QUALITY).takeIf { it in QUALITIES } ?: DEFAULT_QUALITY
        set(value) = prefs.edit().putInt("quality", value).apply()

    val lossless: Boolean get() = quality == Lossless.KBPS

    // A song in FLAC is 25-40 MB: on a metered network, mobile data, only when allowed here.
    var losslessOnMobile: Boolean
        get() = prefs.getBoolean("lossless_mobile", false)
        set(value) = prefs.edit().putBoolean("lossless_mobile", value).apply()

    var losslessServer: String
        get() = prefs.getString("lossless_server", null) ?: Lossless.DEFAULT_SERVER
        set(value) {
            val link = value.trim().trimEnd('/').let { if (it.isEmpty() || "://" in it) it else "https://$it" }
            prefs.edit().putString("lossless_server", link.ifEmpty { null }).apply()
        }

    var accent: Accent
        get() = Accent.entries.find { it.name == prefs.getString("accent", null) } ?: Accent.RED
        set(value) = prefs.edit().putString("accent", value.name).apply()

    var look: Look
        get() = Look.entries.find { it.name == prefs.getString("look", null) } ?: Look.CLASSIC
        set(value) = prefs.edit().putString("look", value.name).apply()

    // The theme the activity wears: the look, and in the classic one the accent too.
    val theme: Int get() = look.let { if (it == Look.CLASSIC) accent.theme else it.theme }

    // A language tag from Lang.CHOICES; empty for the phone's language.
    var language: String
        get() = prefs.getString("language", null)?.takeIf { it in Lang.CHOICES } ?: ""
        set(value) {
            prefs.edit().putString("language", value).apply()
            Lang.reset()
        }
}

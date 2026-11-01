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

object Settings {
    val QUALITIES = listOf(320, 160, 96)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var quality: Int
        get() = prefs.getInt("quality", QUALITIES.first())
        set(value) = prefs.edit().putInt("quality", value).apply()

    var accent: Accent
        get() = Accent.entries.find { it.name == prefs.getString("accent", null) } ?: Accent.RED
        set(value) = prefs.edit().putString("accent", value.name).apply()
}

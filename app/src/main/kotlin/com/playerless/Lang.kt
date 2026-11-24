package com.playerless

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

// The app's language: the phone's, or the one picked in the settings. Activities and services wear
// it from attachBaseContext; code with no screen of its own (the loading log, the toasts of a
// download, the playback service's lines) reads its strings through get().
object Lang {
    // "" is the phone's language. Each one needs its res/values-xx and an entry in localeFilters.
    val CHOICES = listOf("", "en", "it", "es", "fr", "de", "pt")

    private lateinit var app: Context
    @Volatile private var localized: Context? = null

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    // For attachBaseContext, which every activity and service goes through first: the context as it
    // is, or in the chosen language.
    fun wrap(base: Context): Context {
        init(base)
        Settings.init(base)
        val tag = Settings.language.ifEmpty { return base }
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }

    // Any thread.
    fun get(id: Int, vararg args: Any): String = context().getString(id, *args)

    // The language strings are in, for numbers: "96,0 kHz" in Italian, "96.0 kHz" in English.
    val locale: Locale get() = context().resources.configuration.locales[0]

    // A language's own name, as pickers show it: "Deutsch", not "German".
    fun name(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    // After the setting changes.
    fun reset() {
        localized = null
    }

    private fun context(): Context = localized ?: wrap(app).also { localized = it }
}

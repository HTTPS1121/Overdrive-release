package com.overdrive.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

/**
 * Context whose strings follow the in-app language. Below Android 13 AppCompat applies that
 * language to Activities only, so the Application context still resolves the system locale.
 */
object AppLocaleContext {
    @Volatile private var cached: Pair<String, Context>? = null

    // Falls back to the plain context on any failure: callers report errors through these strings.
    fun of(context: Context): Context {
        val app = context.applicationContext ?: context
        return try {
            val locales = AppCompatDelegate.getApplicationLocales()
            val locale = (if (locales.isEmpty) null else locales[0]) ?: return app
            val tag = locale.toLanguageTag()
            cached?.let { (cachedTag, localized) -> if (cachedTag == tag) return localized }
            val cfg = Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)) }
            app.createConfigurationContext(cfg).also { cached = tag to it }
        } catch (e: RuntimeException) {
            app
        }
    }
}

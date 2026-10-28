package com.vougadev.shell.web

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * The light or dark the person chose on the site (its account menu), followed by the app's native screens: a native
 * settings screen in dark over a site set to light reads as two apps. The site marks it as Bootstrap's `data-bs-theme`
 * on `<html>`, already resolved ("light" or "dark"); a site without it leaves the native screens on the phone's theme.
 */
object PageTheme {

    const val SCRIPT = "document.documentElement.getAttribute('data-bs-theme')"

    /** The last page's choice, as an [AppCompatDelegate] night mode; the phone's own until a page has answered. */
    // shortcut: kept in memory and read at each page load and before Settings opens; a theme changed on a page and
    // then a native screen opened from an extension the same instant shows the previous one, until the next page load.
    @Volatile
    var night: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        private set

    /** `evaluateJavascript` hands back the attribute JSON-encoded: `"dark"` with its quotes, `null` when absent. */
    fun nightMode(result: String?): Int = when (result) {
        "\"dark\"" -> AppCompatDelegate.MODE_NIGHT_YES
        "\"light\"" -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun remember(result: String?) { night = nightMode(result) }

    /** For a native screen that is not an AppCompat one (Compose): its base context in the site's light or dark. */
    fun wrap(base: Context): Context {
        val bits = when (night) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or bits
        return base.createConfigurationContext(config)
    }
}

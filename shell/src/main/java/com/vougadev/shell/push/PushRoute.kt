package com.vougadev.shell.push

import com.vougadev.shell.web.AppOrigin
import java.net.URI
import java.net.URISyntaxException

/**
 * WHAT A TAPPED NOTIFICATION OPENS. One hub sends every VougaDev app's notifications, so each says which app it belongs
 * to and what it opens: `{app, url, screen?, topic, id, title, body}` (title and body already in the person's language).
 *
 * - this app's, on this app's origin: the native screen an extension owns for [screen], else the page at [url];
 * - another app's, or a URL elsewhere: the system opens it (that app if installed and it claims the link, else the
 *   browser);
 * - nothing usable: this app's home. Never a blank screen.
 *
 * An older payload with a same-origin path in `url` and no `app` is this app's. Pure, tested on the JVM.
 */
sealed interface PushRoute {
    /** A page of this app: [url] is absolute, on its origin. */
    data class Page(val url: String) : PushRoute

    /** A native screen of this app, if an extension owns [screen]; else the page at [url]. */
    data class Screen(val screen: String, val url: String) : PushRoute

    /** Another app's or another site's address, handed to the system. */
    data class Outside(val url: String) : PushRoute

    data object Home : PushRoute

    companion object {
        private val SCREEN = Regex("^[a-z][a-z0-9_.:-]{0,63}$")

        fun of(data: Map<String, String>, ownSlug: String, origin: AppOrigin): PushRoute {
            val app = data["app"]?.trim().orEmpty().ifEmpty { ownSlug }
            val raw = data["url"]?.trim().orEmpty()
            val own = when {
                raw.isEmpty() -> null
                raw.startsWith("/") -> origin.pageUrl(raw)
                origin.matches(raw) -> origin.pageUrl(pathOf(raw))
                else -> null
            }
            if (app != ownSlug || (raw.isNotEmpty() && own == null)) {
                return if (raw.isHttps()) Outside(raw) else Home
            }
            val screen = data["screen"]?.trim()?.takeIf { SCREEN.matches(it) }
            return when {
                screen != null -> Screen(screen, own ?: (origin.baseUrl + "/"))
                own != null -> Page(own)
                else -> Home
            }
        }

        /** The path and query of an absolute URL on the origin (what the shell activity opens). */
        fun pathOf(url: String): String? = try {
            val uri = URI(url)
            (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        } catch (_: URISyntaxException) {
            null
        }

        private fun String.isHttps(): Boolean = try {
            val uri = URI(this)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        } catch (_: URISyntaxException) {
            false
        }
    }
}

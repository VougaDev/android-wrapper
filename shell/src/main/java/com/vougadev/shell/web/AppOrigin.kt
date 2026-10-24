package com.vougadev.shell.web

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * One origin the shell trusts: the site's web app, or the account service where the person signs in.
 *
 * Everything that grants something - loading a page inside the WebView, the `window.VougaDevNative` bridge,
 * geolocation, a photo - asks this class first, and it answers with scheme, host and port only. A path or a query never
 * makes a foreign page trusted, and `app.example.org.evil.io` or `app.example.org@evil.io` are other hosts, which is
 * what a URI parser (not a string prefix) says they are.
 *
 * Pure Kotlin: the Android side passes the parts of an `android.net.Uri`, the tests pass strings.
 */
class AppOrigin(baseUrl: String) {

    /** The base URL without a trailing slash, e.g. `https://app.example.org`. */
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    private val scheme: String
    private val host: String
    private val port: Int

    init {
        val uri = URI(this.baseUrl)
        scheme = requireNotNull(uri.scheme) { "Base URL has no scheme: $baseUrl" }.lowercase(Locale.ROOT)
        host = requireNotNull(uri.host) { "Base URL has no host: $baseUrl" }.lowercase(Locale.ROOT)
        require(scheme == "https" || scheme == "http") { "Base URL must be http(s): $baseUrl" }
        port = effectivePort(scheme, uri.port)
    }

    /**
     * The origin as a web origin string, `scheme://host[:port]`, the port only when it is not the scheme's default: what a
     * WebView allowed-origin rule and a message's source origin look like.
     */
    val originString: String
        get() = if (port == effectivePort(scheme, -1)) "$scheme://$host" else "$scheme://$host:$port"

    /** True when [url] is on this origin. A malformed or relative URL is never trusted. */
    fun matches(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            return false
        }
        return matchesParts(uri.scheme, uri.host, uri.port)
    }

    /** Same test from the parts of a parsed URI; `port` is -1 when the URL names none. */
    fun matchesParts(scheme: String?, host: String?, port: Int): Boolean {
        if (scheme == null || host == null) return false
        val s = scheme.lowercase(Locale.ROOT)
        if (s != this.scheme) return false
        if (host.lowercase(Locale.ROOT) != this.host) return false
        return effectivePort(s, port) == this.port
    }

    /**
     * The absolute URL of a page on this origin, or null when [path] is not a same-origin PATH: `/`-rooted, no
     * `//host`, no backslash, no whitespace or control character, at most 2048 characters. A query is kept.
     */
    fun pageUrl(path: String?): String? {
        if (path == null || path.length > MAX_PAGE_PATH) return null
        if (!path.startsWith("/") || path.startsWith("//")) return null
        if (path.any { it <= ' ' || it == '\\' || it == '\u007f' }) return null
        val url = baseUrl + path
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        return if (matchesParts(uri.scheme, uri.host, uri.port)) url else null
    }

    private companion object {
        const val MAX_PAGE_PATH = 2048

        fun effectivePort(scheme: String, port: Int): Int = when {
            port != -1 -> port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
    }
}

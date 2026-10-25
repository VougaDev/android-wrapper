package com.vougadev.shell.offline

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * EACH PAGE OF THE SITE FETCHED ONCE: the app's own main-frame GETs are read here, as the WebView would, and handed to
 * the WebView; an HTML 200 is also held for [OfflineCapture], which keeps it once the page proved to be the site's own,
 * instead of asking the site for it a second time.
 *
 * Null - the WebView fetches the page itself - for what it must handle on its own: a redirect (it follows it, and the
 * shell catches the sign-in's), a page that is not HTML, one too big to hold, and a network failure (its error then
 * leads to the offline screens).
 * shortcut: those answers are read twice (this request, then the WebView's); upgrade if a site redirects often.
 */
class PageFetch(private val client: OkHttpClient) {

    /** What the WebView is given: status, reason, `type; charset`, the headers but `Set-Cookie`, and the body. */
    class Answer(
        val code: Int,
        val reason: String,
        val mimeType: String,
        val charset: String,
        val headers: Map<String, String>,
        val setCookies: List<String>,
        val body: ByteArray,
    )

    class Page(val url: String, val contentType: String, val body: ByteArray)

    /** The last page fetched, until the WebView finished it: one main frame loads at a time. */
    @Volatile private var held: Page? = null

    /**
     * [headers] are the WebView's request's; [cookies] its jar for [url]; [acceptLanguage] the phone's, when the WebView
     * gave none (the site answers in the screen's language). Called off the main thread.
     */
    fun fetch(url: String, headers: Map<String, String>, cookies: String?, userAgent: String, acceptLanguage: String): Answer? {
        val request = try {
            Request.Builder().url(url).get().apply {
                headers.forEach { (name, value) -> if (!name.equals("Cookie", ignoreCase = true)) header(name, value) }
                if (!cookies.isNullOrEmpty()) header("Cookie", cookies)
                header("User-Agent", userAgent)
                if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) header("Accept-Language", acceptLanguage)
            }.build()
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                val mime = type.substringBefore(';').trim().lowercase()
                val length = response.body.contentLength()
                if (response.code !in SERVABLE || mime != "text/html" || length > MAX_BYTES) return null
                val body = response.body.bytes()
                if (body.size > MAX_BYTES) return null
                if (response.code == 200) held = Page(url, type, body)
                val passed = response.headers.names().filterNot { it.equals("Set-Cookie", true) || it.equals("Content-Length", true) }
                Answer(
                    code = response.code,
                    reason = response.message.ifBlank { if (response.code < 400) "OK" else "Error" },
                    mimeType = mime,
                    charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim('"') ?: "utf-8",
                    headers = passed.associateWith { response.headers(it).joinToString(", ") },
                    setCookies = response.headers("Set-Cookie"),
                    body = body,
                )
            }
        } catch (_: IOException) {
            null
        }
    }

    /** The HTML the WebView was given for [url], once; null when it fetched the page itself. */
    fun take(url: String): Page? {
        val page = held ?: return null
        if (PageCache.keyOf(page.url) != PageCache.keyOf(url)) return null
        held = null
        return page
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024

        /** What a WebResourceResponse may carry: never a redirect (the WebView would not follow it). */
        private val SERVABLE = (200..299) + (400..599)
    }
}

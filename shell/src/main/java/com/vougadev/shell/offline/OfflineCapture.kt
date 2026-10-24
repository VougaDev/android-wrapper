package com.vougadev.shell.offline

import com.vougadev.shell.auth.AppSession
import com.vougadev.shell.net.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/**
 * After a page of the site has loaded with the network: its HTML and the same-origin files it used are copied into the
 * [PageCache], as the WebView's session. The page itself is read once more (a GET, as the WebView did); a file already
 * kept is read again only after [FRESH_MS], and a versioned one (`?v=`, or a hashed static name) never.
 */
class OfflineCapture(private val session: AppSession, private val cache: PageCache, private val appName: String) {

    suspend fun capture(pageUrl: String, files: Collection<String>) = withContext(Dispatchers.IO) {
        val jar = session.cookies.jar() ?: ""
        fetch(pageUrl, jar, "text/html")?.let { (type, body) ->
            if (type.startsWith("text/html")) cache.put(pageUrl, type, body, isPage = true, title = PageCache.nameOf(String(body), appName))
        }
        for (url in files) {
            val saved = cache.savedAt(url)
            if (saved != null && (immutable(url) || System.currentTimeMillis() - saved < FRESH_MS)) continue
            fetch(url, jar, "*/*")?.let { (type, body) -> if (PageCache.cacheable(url, type)) cache.put(url, type, body, isPage = false) }
        }
    }

    /** `(content type, body)` of a 200 answer, or null. Never follows a redirect: a sign-in page is never kept. */
    private fun fetch(url: String, jar: String, accept: String): Pair<String, ByteArray>? {
        if (!PageCache.cacheable(url, null)) return null
        val headers = AppHttp.headers(session.origin, jar, session.userAgent, session.acceptLanguage(), unsafe = false) ?: return null
        val request = Request.Builder().url(url).headers(headers).header("Accept", accept).get().build()
        return try {
            AppHttp.client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type") ?: return null
                if (response.code != 200 || !PageCache.cacheable(url, type)) return null
                val length = response.body.contentLength()
                if (length > MAX_BYTES) return null
                type to response.body.bytes()
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val FRESH_MS = 10L * 60 * 1000
        private const val MAX_BYTES = 2L * 1024 * 1024
        private val HASHED = Regex("\\.[0-9a-f]{8,}\\.[a-z0-9]+$")

        /** A file whose address changes with its content: kept once. Pure. */
        fun immutable(url: String): Boolean = "?v=" in url || "&v=" in url || HASHED.containsMatchIn(url.substringBefore('?'))
    }
}

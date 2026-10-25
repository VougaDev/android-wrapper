package com.vougadev.shell.offline

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** Each page of the site is asked of it once: what the WebView is given is what is kept for offline. */
class PageFetchTest {

    private val hits = AtomicInteger()
    private var seen: Map<String, String> = emptyMap()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            hits.incrementAndGet()
            seen = exchange.requestHeaders.mapValues { it.value.joinToString(",") }.mapKeys { it.key.lowercase() }
            val (code, type, body) = when (exchange.requestURI.path) {
                "/shipments/" -> Triple(200, "text/html; charset=utf-8", "<title>Mes envois · Ship</title>")
                "/gone/" -> Triple(404, "text/html; charset=utf-8", "<p>introuvable</p>")
                "/label.pdf" -> Triple(200, "application/pdf", "%PDF")
                else -> Triple(302, "text/html", "")
            }
            if (code == 302) exchange.responseHeaders.add("Location", "/auth/login/?next=/shipments/")
            exchange.responseHeaders.add("Content-Type", type)
            exchange.responseHeaders.add("Set-Cookie", "messages=; Path=/")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val fetch = PageFetch(OkHttpClient.Builder().followRedirects(false).build())

    @After
    fun stop() = server.stop(0)

    private fun get(path: String) = fetch.fetch(base + path, mapOf("Referer" to "$base/"), "sessionid=abc", "UA ShipApp/1.0 (Android)", "fr-FR")

    @Test
    fun aPageIsReadOnceForTheWebViewAndItsOfflineCopy() {
        val answer = get("/shipments/")!!
        assertEquals(200, answer.code)
        assertEquals("text/html", answer.mimeType)
        assertEquals("utf-8", answer.charset)
        assertEquals(listOf("messages=; Path=/"), answer.setCookies)
        val kept = fetch.take("$base/shipments/#top")!!
        assertArrayEquals(answer.body, kept.body)
        assertEquals(1, hits.get())
        // taken once: a second finish of the same page keeps nothing more
        assertNull(fetch.take("$base/shipments/"))
    }

    @Test
    fun itIsAskedAsTheWebViewsSessionInThePhonesLanguage() {
        get("/shipments/")
        assertEquals("sessionid=abc", seen["cookie"])
        assertEquals("UA ShipApp/1.0 (Android)", seen["user-agent"])
        assertEquals("fr-FR", seen["accept-language"])
        assertEquals("$base/", seen["referer"])
    }

    @Test
    fun theSitesOwnErrorPageIsShownButNeverKept() {
        assertEquals(404, get("/gone/")!!.code)
        assertNull(fetch.take("$base/gone/"))
    }

    @Test
    fun aRedirectOrAFileIsLeftToTheWebView() {
        // the WebView follows the redirect itself: the shell catches the sign-in's
        assertNull(get("/somewhere/"))
        assertNull(get("/label.pdf"))
        assertNull(fetch.take("$base/somewhere/"))
    }

    @Test
    fun noNetworkLeavesThePageToTheWebViewsErrorAndTheOfflineScreens() {
        server.stop(0)
        assertNull(get("/shipments/"))
    }
}

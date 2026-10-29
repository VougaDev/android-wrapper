package com.vougadev.shell.offline

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
                "/private/" -> Triple(200, "text/html; charset=utf-8", "<title>Dossier · App</title>")
                "/gone/" -> Triple(404, "text/html; charset=utf-8", "<p>introuvable</p>")
                "/label.pdf" -> Triple(200, "application/pdf", "%PDF")
                "/down/" -> Triple(502, "text/plain; charset=UTF-8", "error code: 502")
                else -> Triple(302, "text/html", "")
            }
            if (exchange.requestURI.path == "/private/") exchange.responseHeaders.add("Cache-Control", "max-age=0, No-Store, private")
            if (code == 302) exchange.responseHeaders.add("Location", "/auth/login/?next=/shipments/")
            exchange.responseHeaders.add("Content-Type", type)
            SET_COOKIES.forEach { exchange.responseHeaders.add("Set-Cookie", it) }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val fetch = PageFetch(OkHttpClient.Builder().followRedirects(false).build())

    private companion object {
        val SET_COOKIES = listOf(
            "csrftoken=t0k3n; expires=Mon, 25 Oct 2027 03:00:00 GMT; Max-Age=31449600; Path=/; SameSite=Lax; Secure",
            "sessionid=s3ss; HttpOnly; Path=/; SameSite=Lax; Secure",
            "lang=fr; Path=/",
        )
    }

    @After
    fun stop() = server.stop(0)

    private fun get(path: String) = fetch.fetch(base + path, mapOf("Referer" to "$base/"), "sessionid=abc", "UA ShipApp/1.0 (Android)", "fr-FR")

    @Test
    fun aPageIsReadOnceForTheWebViewAndItsOfflineCopy() {
        val answer = get("/shipments/")!!
        assertEquals(200, answer.code)
        assertEquals("text/html", answer.mimeType)
        assertEquals("utf-8", answer.charset)
        // every cookie of the answer reaches the WebView's jar, attributes and all: the session, the CSRF token, the language
        assertEquals(SET_COOKIES, answer.setCookies)
        assertTrue(answer.headers.keys.none { it.equals("Set-Cookie", ignoreCase = true) })
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
    fun theEdgesBareErrorComesBackSoItIsNeverShownNorKept() {
        // Cloudflare's "error code: 502" while the server is out of reach (Echo on the WP35, 2026-10-29)
        val answer = get("/down/")!!
        assertEquals(502, answer.code)
        assertTrue(Outage.isEdge(answer.code, Outage.hasMeta(answer.body)))
        assertNull(fetch.take("$base/down/"))
    }

    @Test
    fun noNetworkIsOfflineAtOnce() {
        server.stop(0)
        assertThrows(java.io.IOException::class.java) { get("/shipments/") }
    }

    @Test
    fun aSiteThatNeverAnswersIsOfflineAfterTheTimeLimit() {
        server.createContext("/silent/") { Thread.sleep(5_000) }
        val quick = PageFetch(OkHttpClient.Builder().callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build())
        assertThrows(java.io.IOException::class.java) { quick.fetch("$base/silent/", emptyMap(), null, "UA", "fr-FR") }
    }

    @Test
    fun aNoStorePageIsShownButNeverKept() {
        assertEquals(200, get("/private/")!!.code)
        assertNull(fetch.take("$base/private/"))
        assertTrue(PageFetch.keeps("max-age=0, must-revalidate"))
        assertTrue(PageFetch.keeps(null))
        assertEquals(false, PageFetch.keeps("private, no-store"))
    }
}

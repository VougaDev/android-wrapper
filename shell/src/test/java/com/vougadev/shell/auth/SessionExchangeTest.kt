package com.vougadev.shell.auth

import com.vougadev.shell.web.AppOrigin
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `POST /auth/app-session/` with the access token, answered with the site's session cookies. */
class SessionExchangeTest {

    private val origin = AppOrigin("https://app.example.org")
    private val session = "sessionid=s3ss; expires=Thu, 29 Oct 2026 10:00:00 GMT; HttpOnly; Max-Age=1209600; Path=/; SameSite=Lax; Secure"
    private val csrf = "csrftoken=Tok3n; expires=Thu, 14 Oct 2027 10:00:00 GMT; Max-Age=31449600; Path=/; SameSite=Lax; Secure"

    @Test
    fun theRequestIsABearerPostWithoutCookies() {
        val r = SessionExchange.request(origin, "at-1", "UA DemoApp/1.0.0 (Android)", "fr-FR,fr;q=0.9")
        assertEquals("POST", r.method)
        assertEquals("https://app.example.org/auth/app-session/", r.url.toString())
        assertEquals("Bearer at-1", r.header("Authorization"))
        assertEquals("https://app.example.org/", r.header("Referer"))
        assertEquals("fr-FR,fr;q=0.9", r.header("Accept-Language"))
        assertEquals("UA DemoApp/1.0.0 (Android)", r.header("User-Agent"))
        assertNull(r.header("Cookie"))
        assertNull(r.header("X-CSRFToken"))
        assertEquals(0L, Buffer().also { r.body!!.writeTo(it) }.size)
    }

    @Test
    fun a204WithTheSessionCookieOpensIt() {
        assertEquals(Exchange.Opened(listOf(session, csrf)), SessionExchange.read(204, listOf(session, csrf)))
    }

    @Test
    fun anAnswerWithoutTheSessionCookieOpensNothing() {
        assertEquals(Exchange.Failed(204), SessionExchange.read(204, listOf(csrf)))
        assertEquals(Exchange.Failed(204), SessionExchange.read(204, listOf("xsessionid=1; Path=/")))
    }

    @Test
    fun aRefusedTokenAndAnEdgeErrorAreTold() {
        assertEquals(Exchange.Refused, SessionExchange.read(401, emptyList()))
        assertEquals(Exchange.Failed(502), SessionExchange.read(502, emptyList()))
    }

    @Test
    fun theCallReadsEverySetCookieAndAMissingNetworkIsOffline() = runBlocking {
        val server = FakeServer { FakeServer.response(it, 204, headers = listOf("Set-Cookie" to session, "Set-Cookie" to csrf)) }
        val exchange = SessionExchange(origin, { "UA" }, { "en" }, server.client)
        assertEquals(Exchange.Opened(listOf(session, csrf)), exchange.open("at-1"))
        assertEquals("Bearer at-1", server.requests.single().header("Authorization"))
        assertEquals(Exchange.Offline, SessionExchange(origin, { "UA" }, { "en" }, FakeServer { null }.client).open("at-1"))
    }
}

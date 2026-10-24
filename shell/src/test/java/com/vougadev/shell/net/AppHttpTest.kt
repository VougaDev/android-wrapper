package com.vougadev.shell.net

import com.vougadev.shell.web.AppOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The header rules every native call obeys (PIVO's `net/PivoHttp.kt`). */
class AppHttpTest {

    private val origin = AppOrigin("https://app.example.org/")
    private val jar = "lang=fr; csrftoken=Tok3n; sessionid=abc"

    @Test
    fun aReadCarriesTheSessionAndTheLanguageButNoCsrf() {
        val h = AppHttp.headers(origin, jar, "UA DemoApp/1.0.0 (Android)", "fr-FR,fr;q=0.9", unsafe = false)!!
        assertEquals(jar, h["Cookie"])
        assertEquals("https://app.example.org/", h["Referer"])
        assertEquals("fr-FR,fr;q=0.9", h["Accept-Language"])
        assertEquals("application/json", h["Accept"])
        assertEquals("UA DemoApp/1.0.0 (Android)", h["User-Agent"])
        assertNull(h["X-CSRFToken"])
    }

    @Test
    fun aPostCarriesTheCsrfCookieAsItsToken() {
        val h = AppHttp.headers(origin, jar, "UA", "en", unsafe = true)!!
        assertEquals("Tok3n", h["X-CSRFToken"])
        assertEquals("https://app.example.org/", h["Referer"])
    }

    @Test
    fun aPostWithoutACsrfCookieIsNotMade() {
        assertNull(AppHttp.headers(origin, "sessionid=abc", "UA", "en", unsafe = true))
    }

    @Test
    fun theRefererIsTheOriginEvenWithAPortAndAPath() {
        val lab = AppOrigin("http://lab.test:8000")
        assertEquals("http://lab.test:8000/", AppHttp.headers(lab, jar, "UA", "en", unsafe = true)!!["Referer"])
    }

    @Test
    fun acceptLanguageListsThePhonesLanguagesInOrder() {
        assertEquals("fr-FR,en-GB;q=0.9,de;q=0.8", AppHttp.acceptLanguage(listOf("fr-FR", "en-GB", "de")))
        assertEquals("en", AppHttp.acceptLanguage(emptyList()))
        assertEquals("fr", AppHttp.acceptLanguage(listOf("", "fr")))
    }

    @Test
    fun acceptLanguageNeverGoesBelowATenth() {
        val tags = (1..15).map { "l$it" }
        assertTrue(AppHttp.acceptLanguage(tags).endsWith("l15;q=0.1"))
    }

    @Test
    fun cookiesAreReadByExactName() {
        assertEquals("Tok3n", SessionCookies.valueOf(jar, "csrftoken"))
        assertNull(SessionCookies.valueOf("xcsrftoken=1", "csrftoken"))
        assertNull(SessionCookies.valueOf("csrftoken=", "csrftoken"))
        assertTrue(SessionCookies.isSignedIn(jar))
        assertFalse(SessionCookies.isSignedIn("csrftoken=1"))
        assertFalse(SessionCookies.isSignedIn(null))
    }
}

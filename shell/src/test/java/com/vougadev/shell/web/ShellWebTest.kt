package com.vougadev.shell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellWebTest {

    private val site = AppOrigin("https://app.example.org")

    private val company = listOf("example.org")

    private fun decide(scheme: String?, host: String?, isSite: Boolean = false, native: Boolean = false) =
        LinkPolicy.decide(scheme, host, isSite, native, path = "/", companyDomains = company)

    @Test
    fun theLinkPolicyHandsNativeRoutesToTheApp() {
        assertEquals(LinkPolicy.Decision.OPEN_NATIVE, decide("https", "app.example.org", isSite = true, native = true))
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "app.example.org", isSite = true))
    }

    @Test
    fun oneAppOneWebViewForEveryCompanyDomain() {
        // the account service, the company's other domains and subdomains: inside, never the browser
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "auth.example.org"))
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "example.org"))
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "Help.Example.org"))
        // a native route on the account service's host is not the site's
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "auth.example.org", native = true))
        // look-alikes and plain http are not the company
        assertEquals(LinkPolicy.Decision.OPEN_THIRD_PARTY, decide("https", "example.org.evil.io"))
        assertEquals(LinkPolicy.Decision.OPEN_THIRD_PARTY, decide("https", "notexample.org"))
        assertEquals(LinkPolicy.Decision.OPEN_THIRD_PARTY, decide("http", "auth.example.org"))
    }

    @Test
    fun onlyThirdPartiesAndThePhonesAppsLeave() {
        assertEquals(LinkPolicy.Decision.OPEN_THIRD_PARTY, decide("https", "checkout.payments.example.net"))
        assertEquals(LinkPolicy.Decision.SIGN_IN_ELSEWHERE, decide("https", "accounts.google.com"))
        assertEquals(LinkPolicy.Decision.SIGN_IN_ELSEWHERE, decide("https", "appleid.apple.com"))
        assertEquals(LinkPolicy.Decision.OPEN_APP, decide("tel", null))
        assertEquals(LinkPolicy.Decision.OPEN_APP, decide("mailto", null))
        assertEquals(LinkPolicy.Decision.OPEN_APP, decide("geo", null))
        assertEquals(LinkPolicy.Decision.OPEN_APP, decide("whatsapp", null))
        assertEquals(LinkPolicy.Decision.OPEN_INTENT, decide("intent", null))
        assertEquals(LinkPolicy.Decision.BLOCK, decide("javascript", null))
        assertEquals(LinkPolicy.Decision.BLOCK, decide(null, null))
    }

    @Test
    fun aNotificationPathStaysOnTheSite() {
        assertEquals("https://app.example.org/orders/?a=1", site.pageUrl("/orders/?a=1"))
        assertNull(site.pageUrl("//evil.io/x"))
        assertNull(site.pageUrl("https://evil.io/"))
        assertNull(site.pageUrl("/a b"))
        assertNull(site.pageUrl("/a\\b"))
    }

    @Test
    fun theModalBackAnswerIsReadExactly() {
        assertTrue(ModalBack.closedAModal("\"closed\""))
        assertFalse(ModalBack.closedAModal("\"none\""))
        assertFalse(ModalBack.closedAModal(null))
        assertTrue(PullGuard.blocks("\"block\""))
        assertFalse(PullGuard.blocks("\"pull\""))
        assertTrue(PullGuard.script(10.5f, 20f).contains("elementFromPoint(10.5,20.0)"))
    }
}

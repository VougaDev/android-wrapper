package com.vougadev.shell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellWebTest {

    private val site = AppOrigin("https://app.example.org")

    private val company = listOf("example.org")

    private fun decide(scheme: String?, host: String?, isSite: Boolean = false, native: Boolean = false, tapped: Boolean = true) =
        LinkPolicy.decide(scheme, host, isSite, native, path = "/", companyDomains = company, outsideHosts = listOf("paypal.example"), userTapped = tapped)

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
    fun paymentStaysInsideAndOnlyAStepThatRefusesWebViewsGoesToASheet() {
        // the company's checkout is a company domain
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "pay.example.org"))
        // a bank's or an operator's step met on the way (a redirect, nobody tapped) stays in the WebView
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "acs.bank.example.net", tapped = false))
        // a provider that refuses WebViews becomes an outside step, tapped or not
        assertEquals(LinkPolicy.Decision.OUTSIDE_STEP, decide("https", "www.paypal.example", tapped = false))
        assertEquals(LinkPolicy.Decision.OUTSIDE_STEP, decide("https", "paypal.example"))
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

    @Test
    fun nativeScreensFollowTheSitesLightOrDark() {
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES, PageTheme.nightMode("\"dark\""))
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO, PageTheme.nightMode("\"light\""))
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, PageTheme.nightMode("null"))
        assertEquals(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, PageTheme.nightMode(null))
        // back closes a menu or a popover before it leaves the screen
        assertTrue(ModalBack.SCRIPT.contains(".dropdown-menu.show") && ModalBack.SCRIPT.contains(".popover.show"))
    }

    @Test
    fun telegramsBotLinksOpenTelegramNeverTheWebView() {
        // the account service's "Connect Telegram" answers with a redirect to the bot: tapped or not, the app
        assertEquals(LinkPolicy.Decision.OPEN_IN_ITS_APP, decide("https", "t.me", tapped = false))
        assertEquals(LinkPolicy.Decision.OPEN_IN_ITS_APP, decide("https", "t.me"))
        assertEquals(LinkPolicy.Decision.OPEN_IN_ITS_APP, decide("https", "Telegram.me", tapped = false))
        // Telegram's sign-in step, met during the account service's sign-in: inside, with it
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, decide("https", "oauth.telegram.org", tapped = false))
        assertEquals(LinkPolicy.Decision.OPEN_THIRD_PARTY, decide("https", "t.me.example.net"))
    }
}

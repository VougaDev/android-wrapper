package com.vougadev.shell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellWebTest {

    private val site = AppOrigin("https://app.example.org")

    @Test
    fun theLinkPolicyHandsNativeRoutesToTheApp() {
        assertEquals(LinkPolicy.Decision.OPEN_NATIVE, LinkPolicy.decide("https", isSite = true, isAuth = false, isNativeRoute = true))
        assertEquals(LinkPolicy.Decision.LOAD_INSIDE, LinkPolicy.decide("https", isSite = true, isAuth = false, isNativeRoute = false))
    }

    @Test
    fun onlyTheSiteLoadsInsideAndEverythingElseLeaves() {
        // the account service's pages other than its sign-in open in the browser (SignInRouteTest); never in the WebView
        assertEquals(LinkPolicy.Decision.OPEN_OUTSIDE, LinkPolicy.decide("https", isSite = false, isAuth = true, isNativeRoute = false))
        // a native route on the account service's host is not the site's
        assertEquals(LinkPolicy.Decision.OPEN_OUTSIDE, LinkPolicy.decide("https", isSite = false, isAuth = true, isNativeRoute = true))
        assertEquals(LinkPolicy.Decision.OPEN_OUTSIDE, LinkPolicy.decide("https", isSite = false, isAuth = false, isNativeRoute = false))
        assertEquals(LinkPolicy.Decision.OPEN_OUTSIDE, LinkPolicy.decide("tel", isSite = false, isAuth = false, isNativeRoute = false))
        assertEquals(LinkPolicy.Decision.OPEN_INTENT, LinkPolicy.decide("intent", isSite = false, isAuth = false, isNativeRoute = false))
        assertEquals(LinkPolicy.Decision.BLOCK, LinkPolicy.decide("javascript", isSite = false, isAuth = false, isNativeRoute = false))
        assertEquals(LinkPolicy.Decision.BLOCK, LinkPolicy.decide(null, isSite = false, isAuth = false, isNativeRoute = false))
    }

    @Test
    fun aNotificationPathStaysOnTheSite() {
        assertEquals("https://app.example.org/shipments/?a=1", site.pageUrl("/shipments/?a=1"))
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

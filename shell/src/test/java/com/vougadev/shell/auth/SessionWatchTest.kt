package com.vougadev.shell.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A signed-in phone whose page came back without the site's session renews it, once a minute at most. */
class SessionWatchTest {

    @Test
    fun `a page without the site's session, on a signed-in phone, renews`() {
        assertTrue(SessionWatch().shouldRenew(signedInOnPhone = true, siteSessionCookie = false, busy = false, nowMs = 1_000))
    }

    @Test
    fun `nothing to renew when the session is there, the phone is signed out, or a sign-in is under way`() {
        val watch = SessionWatch()
        assertFalse(watch.shouldRenew(signedInOnPhone = true, siteSessionCookie = true, busy = false, nowMs = 1_000))
        assertFalse(watch.shouldRenew(signedInOnPhone = false, siteSessionCookie = false, busy = false, nowMs = 1_000))
        assertFalse(watch.shouldRenew(signedInOnPhone = true, siteSessionCookie = false, busy = true, nowMs = 1_000))
        // none of those spent the minute
        assertTrue(watch.shouldRenew(signedInOnPhone = true, siteSessionCookie = false, busy = false, nowMs = 1_001))
    }

    @Test
    fun `a site that keeps dropping the session never loops the app`() {
        val watch = SessionWatch()
        assertTrue(watch.shouldRenew(true, false, false, 10_000))
        assertFalse(watch.shouldRenew(true, false, false, 10_000 + 59_999))
        assertTrue(watch.shouldRenew(true, false, false, 10_000 + 60_000))
    }
}

package com.vougadev.shell.auth

/**
 * THE SITE DROPPED THE APP'S SESSION. The session the app opens on the site (`/auth/app-session/`) lasts as long as the
 * access token it was opened with; once that expires the site signs it out and its cookie goes, while the app still
 * holds a refresh token. A page that needs no sign-in (the home) then loads anonymous and nothing heads for a sign-in
 * page, so the person looks signed out - typically on the first start after a while, or after an update.
 *
 * After each page of the site, the shell asks [shouldRenew]: signed in on this phone, no session cookie any more, no
 * sign-in screen up, and no automatic renewal in the last [MIN_INTERVAL_MS] (a site that keeps dropping the session
 * never loops the app). Then the session is renewed silently and the page opened again. Pure, tested on the JVM.
 */
class SessionWatch(private val minIntervalMs: Long = MIN_INTERVAL_MS) {

    private var lastRenewalAt = Long.MIN_VALUE / 2

    fun shouldRenew(signedInOnPhone: Boolean, siteSessionCookie: Boolean, busy: Boolean, nowMs: Long): Boolean {
        if (!signedInOnPhone || siteSessionCookie || busy) return false
        if (nowMs - lastRenewalAt < minIntervalMs) return false
        lastRenewalAt = nowMs
        return true
    }

    companion object {
        const val MIN_INTERVAL_MS = 60_000L
    }
}

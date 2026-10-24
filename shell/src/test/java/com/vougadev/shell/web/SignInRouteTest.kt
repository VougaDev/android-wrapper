package com.vougadev.shell.web

import com.vougadev.shell.web.LinkPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NEVER SIGN IN IN THE WEBVIEW: the site's and the account service's sign-in pages are taken over; the web's sign-out too. */
class SignInRouteTest {

    private val origin = AppOrigin("https://app.example.org")

    private fun site(path: String, native: Boolean = false) = LinkPolicy.decide("https", isSite = true, isAuth = false, isNativeRoute = native, path = path)

    private fun auth(path: String) = LinkPolicy.decide("https", isSite = false, isAuth = true, isNativeRoute = false, path = path)

    @Test
    fun theSitesSignInPageRunsTheNativeSignIn() {
        assertEquals(Decision.SIGN_IN, site("/auth/login/"))
        assertEquals(Decision.SIGN_IN, site("/auth/login"))
        assertEquals(Decision.LOAD_INSIDE, site("/auth/login-help/"))
        assertEquals(Decision.LOAD_INSIDE, site("/shipments/"))
        assertEquals(Decision.OPEN_NATIVE, site("/courier/missions/", native = true))
    }

    @Test
    fun authsSignInPagesRunTheNativeSignInAndItsOtherPagesOpenInTheBrowser() {
        assertEquals(Decision.SIGN_IN, auth("/identity/o/authorize"))
        assertEquals(Decision.SIGN_IN, auth("/accounts/login/"))
        assertEquals(Decision.SIGN_IN, auth("/accounts/signup/"))
        assertEquals(Decision.OPEN_OUTSIDE, auth("/account/"))
        assertEquals(Decision.OPEN_OUTSIDE, auth("/"))
    }

    @Test
    fun anotherHostsLoginPathIsJustAnotherHost() {
        assertEquals(Decision.OPEN_OUTSIDE, LinkPolicy.decide("https", isSite = false, isAuth = false, isNativeRoute = false, path = "/auth/login/"))
    }

    @Test
    fun afterSignInTheNextPageOpensWhenItIsTheSitesOwn() {
        assertEquals("https://app.example.org/courier/missions/", SignInRoute.after(origin, "/courier/missions/"))
        assertEquals("https://app.example.org/shipments/?tab=sent", SignInRoute.after(origin, "/shipments/?tab=sent"))
        assertEquals("https://app.example.org/", SignInRoute.after(origin, null))
        assertEquals("https://app.example.org/", SignInRoute.after(origin, "//evil.example/"))
        assertEquals("https://app.example.org/", SignInRoute.after(origin, "https://evil.example/"))
        // never the sign-in or sign-out again: a loop
        assertEquals("https://app.example.org/", SignInRoute.after(origin, "/auth/login/?next=/"))
        assertEquals("https://app.example.org/", SignInRoute.after(origin, "/auth/logout/"))
    }

    @Test
    fun theWebsSignOutIsATopLevelPostToTheSitesLogout() {
        assertTrue(SignInRoute.isSignOut("POST", isSite = true, path = "/auth/logout/"))
        assertTrue(SignInRoute.isSignOut("post", isSite = true, path = "/auth/logout"))
        assertFalse(SignInRoute.isSignOut("GET", isSite = true, path = "/auth/logout/"))
        assertFalse(SignInRoute.isSignOut("POST", isSite = false, path = "/auth/logout/"))
        assertFalse(SignInRoute.isSignOut("POST", isSite = true, path = "/shipments/"))
    }
}

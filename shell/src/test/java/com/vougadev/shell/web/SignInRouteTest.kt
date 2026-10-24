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

    /** The app's the account service sign-in paths (`ShellConfig.authSignInPaths`). */
    private val authSignIn = listOf("/oauth/authorize", "/login", "/signup")

    private fun auth(path: String) =
        LinkPolicy.decide("https", isSite = false, isAuth = true, isNativeRoute = false, path = path, authSignIn = authSignIn)

    @Test
    fun theSitesSignInPageRunsTheNativeSignIn() {
        assertEquals(Decision.SIGN_IN, site("/auth/login/"))
        assertEquals(Decision.SIGN_IN, site("/auth/login"))
        assertEquals(Decision.LOAD_INSIDE, site("/auth/login-help/"))
        assertEquals(Decision.LOAD_INSIDE, site("/orders/"))
        assertEquals(Decision.OPEN_NATIVE, site("/native/", native = true))
    }

    @Test
    fun authsSignInPagesRunTheNativeSignInAndItsOtherPagesOpenInTheBrowser() {
        assertEquals(Decision.SIGN_IN, auth("/oauth/authorize"))
        assertEquals(Decision.SIGN_IN, auth("/login/"))
        assertEquals(Decision.SIGN_IN, auth("/signup/"))
        // without the app's paths, nothing of the account service is a sign-in
        assertEquals(Decision.OPEN_OUTSIDE, LinkPolicy.decide("https", isSite = false, isAuth = true, isNativeRoute = false, path = "/login/"))
        assertEquals(Decision.OPEN_OUTSIDE, auth("/account/"))
        assertEquals(Decision.OPEN_OUTSIDE, auth("/"))
    }

    @Test
    fun anotherHostsLoginPathIsJustAnotherHost() {
        assertEquals(Decision.OPEN_OUTSIDE, LinkPolicy.decide("https", isSite = false, isAuth = false, isNativeRoute = false, path = "/auth/login/"))
    }

    @Test
    fun afterSignInTheNextPageOpensWhenItIsTheSitesOwn() {
        assertEquals("https://app.example.org/native/", SignInRoute.after(origin, "/native/"))
        assertEquals("https://app.example.org/orders/?tab=sent", SignInRoute.after(origin, "/orders/?tab=sent"))
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
        assertFalse(SignInRoute.isSignOut("POST", isSite = true, path = "/orders/"))
    }
}

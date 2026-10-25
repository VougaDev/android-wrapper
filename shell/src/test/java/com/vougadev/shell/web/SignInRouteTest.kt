package com.vougadev.shell.web

import com.vougadev.shell.web.LinkPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The site's own sign-in address renews the session; the web's sign-out is taken over. */
class SignInRouteTest {

    private val origin = AppOrigin("https://app.example.org")

    private fun site(path: String, native: Boolean = false) =
        LinkPolicy.decide("https", "app.example.org", isSite = true, isNativeRoute = native, path = path, companyDomains = listOf("example.org"))

    @Test
    fun theSitesSignInPageRunsTheNativeSignIn() {
        assertEquals(Decision.SIGN_IN, site("/auth/login/"))
        assertEquals(Decision.SIGN_IN, site("/auth/login"))
        assertEquals(Decision.LOAD_INSIDE, site("/auth/login-help/"))
        assertEquals(Decision.LOAD_INSIDE, site("/orders/"))
        assertEquals(Decision.OPEN_NATIVE, site("/native/", native = true))
    }

    @Test
    fun anotherHostsLoginPathIsJustAnotherHost() {
        assertEquals(Decision.OPEN_THIRD_PARTY, LinkPolicy.decide("https", "other.example.net", isSite = false, isNativeRoute = false, path = "/auth/login/"))
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

    @Test
    fun aSitesSignUpOrReAuthenticationIsAskedOfTheAccountService() {
        assertEquals(SignInRoute.Asked("create", null), SignInRoute.Asked.of("create", null))
        assertEquals(SignInRoute.Asked("login", 0), SignInRoute.Asked.of(" login ", "0"))
        assertEquals(SignInRoute.Asked(null, 300), SignInRoute.Asked.of(null, "300"))
        assertEquals(SignInRoute.Asked("select_account", null), SignInRoute.Asked.of("select_account", "soon"))
        // a plain sign-in link asks nothing: the session is renewed silently
        assertNull(SignInRoute.Asked.of(null, null))
        // only the prompts the account service knows, never whatever the link carries
        assertNull(SignInRoute.Asked.of("none", "-1"))
        assertNull(SignInRoute.Asked.of("create consent", ""))
    }
}

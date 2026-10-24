package com.vougadev.shell.push

import com.vougadev.shell.web.AppOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a tapped notification from the hub opens: this app's screen or page, another app's address, or home. */
class PushRouteTest {

    private val origin = AppOrigin("https://app.example.org")

    private fun route(vararg pairs: Pair<String, String>) = PushRoute.of(mapOf(*pairs), "demo", origin)

    @Test
    fun `this app's page opens in the app`() {
        assertEquals(PushRoute.Page("https://app.example.org/orders/7?tab=x"), route("app" to "demo", "url" to "https://app.example.org/orders/7?tab=x"))
        // an older payload: a same-origin path and no app
        assertEquals(PushRoute.Page("https://app.example.org/orders/7"), route("url" to "/orders/7"))
    }

    @Test
    fun `a screen key goes with the page it stands for`() {
        assertEquals(PushRoute.Screen("task", "https://app.example.org/tasks/1"), route("app" to "demo", "url" to "https://app.example.org/tasks/1", "screen" to "task"))
        assertEquals(PushRoute.Screen("task", "https://app.example.org/"), route("screen" to "task"))
        // a key that is not one is ignored
        assertEquals(PushRoute.Page("https://app.example.org/x"), route("url" to "/x", "screen" to "../evil"))
    }

    @Test
    fun `another app's notification, or another site, opens outside`() {
        assertEquals(PushRoute.Outside("https://other.example.org/a"), route("app" to "other", "url" to "https://other.example.org/a"))
        assertEquals(PushRoute.Outside("https://other.example.org/a"), route("app" to "demo", "url" to "https://other.example.org/a"))
        // this app's address but another app's notification: still the system's to route
        assertEquals(PushRoute.Outside("https://app.example.org/a"), route("app" to "other", "url" to "https://app.example.org/a"))
    }

    @Test
    fun `nothing usable opens home, never a blank screen`() {
        assertEquals(PushRoute.Home, route())
        assertEquals(PushRoute.Home, route("app" to "other"))
        assertEquals(PushRoute.Home, route("url" to "javascript:alert(1)"))
        assertEquals(PushRoute.Home, route("url" to "//evil.example/x"))
        assertEquals(PushRoute.Home, route("app" to "other", "url" to "http://plain.example/"))
    }

    @Test
    fun `the page's path keeps its query`() {
        assertEquals("/a/b?c=1", PushRoute.pathOf("https://app.example.org/a/b?c=1"))
        assertEquals("/", PushRoute.pathOf("https://app.example.org"))
    }
}

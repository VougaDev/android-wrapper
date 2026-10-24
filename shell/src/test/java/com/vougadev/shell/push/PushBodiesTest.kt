package com.vougadev.shell.push

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the phone tells the site about its push token, which the site hands to the hub. */
class PushBodiesTest {

    @Test
    fun `the report says which app, platform and language`() {
        val body = Push.Bodies.report("tok", "  Pixel 8  ", "demo", "fr-GA")
        assertEquals("fcm", body.getString("kind"))
        assertEquals("tok", body.getString("token"))
        assertEquals("Pixel 8", body.getString("label"))
        assertEquals("demo", body.getString("app"))
        assertEquals("android", body.getString("platform"))
        assertEquals("fr-GA", body.getString("locale"))
        assertEquals(80, Push.Bodies.report("t", "x".repeat(200), "demo", "en").getString("label").length)
    }

    @Test
    fun `forgetting names the token only`() {
        assertEquals("""{"token":"tok"}""", Push.Bodies.forget("tok").toString())
    }
}

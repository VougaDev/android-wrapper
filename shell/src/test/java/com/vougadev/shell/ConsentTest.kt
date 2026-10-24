package com.vougadev.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/** The consent choices as the site reads them in its cookie, and as the phone keeps them. */
class ConsentTest {

    @Test
    fun `the cookie lists each purpose, on or off, in order`() {
        assertEquals("analytics:1|ads:0", Consent.cookieValue(linkedMapOf("analytics" to true, "ads" to false)))
        assertEquals("", Consent.cookieValue(emptyMap()))
    }

    @Test
    fun `what was saved reads back, and nothing readable is no choice`() {
        assertEquals(mapOf("analytics" to true, "ads" to false), Consent.parse("""{"analytics": true, "ads": false}"""))
        assertEquals(emptyMap<String, Boolean>(), Consent.parse(null))
        assertEquals(emptyMap<String, Boolean>(), Consent.parse("not json"))
    }
}

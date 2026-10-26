package com.vougadev.shell.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline only without a validated network: an edge that answered is a service restarting, never "offline". */
class OutageTest {

    @Test
    fun anEdgeErrorOnAWorkingNetworkIsARestartNeverOffline() {
        listOf(502, 503, 504, 500).forEach { assertEquals(Outage.RESTARTING, Outage.of(validated = true, status = it)) }
        // a 200 page without the company-page meta: the proxy's maintenance page
        assertEquals(Outage.RESTARTING, Outage.of(validated = true, status = null))
    }

    @Test
    fun withoutAValidatedNetworkItIsOffline() {
        listOf(null, 404, 502, 503, 504).forEach { assertEquals(Outage.OFFLINE, Outage.of(validated = false, status = it)) }
    }

    @Test
    fun aForeignPageUnder500IsUnavailable() {
        assertEquals(Outage.UNAVAILABLE, Outage.of(validated = true, status = 404))
        assertEquals(Outage.UNAVAILABLE, Outage.of(validated = true, status = 403))
    }

    @Test
    fun theSiteIsBackOnlyWithItsOwnPage() {
        val site = """<head><meta name="vougadev-page" content="motor"><title>Motor</title>"""
        assertTrue(Outage.back(200, site, "vougadev-page"))
        assertTrue(Outage.back(404, site, "vougadev-page"))
        assertFalse(Outage.back(502, site, "vougadev-page"))
        assertFalse(Outage.back(503, "<h1>503 Service Unavailable</h1>", "vougadev-page"))
        assertFalse(Outage.back(200, "<h1>Maintenance</h1>", "vougadev-page"))
    }
}

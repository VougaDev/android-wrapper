package com.vougadev.shell.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An error page without the company-page meta is never the site's: the site's own answers are never replaced. */
class OutageTest {

    @Test
    fun theSitesOwnAnswersAreNeverReplacedMetaOrNot() {
        // a CV, an invoice, a ticket, a PDF: generated documents without the meta (Jobs' CV, Echo on the WP35, 2026-10-26)
        listOf(null, 201, 204, 301, 302).forEach {
            assertFalse("$it", Outage.isEdge(it, hasMeta = false))
            assertFalse("$it", Outage.isEdge(it, hasMeta = true))
        }
        // the site's own error pages carry the meta; a 4xx file is the site's
        listOf(400, 403, 404, 410, 429, 500, 503).forEach { assertFalse("$it", Outage.isEdge(it, hasMeta = true)) }
        listOf(403, 404).forEach { assertFalse("$it", Outage.isEdge(it, hasMeta = false, mimeType = "application/pdf")) }
    }

    @Test
    fun anErrorPageWithoutTheMetaIsNeverShown() {
        listOf(500, 502, 503, 504, 522).forEach { assertTrue("$it", Outage.isEdge(it, hasMeta = false)) }
        // Cloudflare's 403 / 429, the proxy's 404 / 413, Django's debug or CSRF page (Echo, 2026-10-30)
        listOf(400, 403, 404, 413, 429).forEach {
            assertTrue("$it", Outage.isEdge(it, hasMeta = false))
            assertTrue("$it", Outage.isEdge(it, hasMeta = false, mimeType = "text/html"))
            assertTrue("$it", Outage.isEdge(it, hasMeta = false, mimeType = "text/plain"))
        }
    }

    @Test
    fun onlyAServerErrorMeansRestarting() {
        listOf(null, 500, 502, 503, 504).forEach { assertTrue("$it", Outage.restarting(it)) }
        listOf(400, 403, 404, 429).forEach { assertFalse("$it", Outage.restarting(it)) }
    }

    @Test
    fun theCompanyPageMetaIsReadFromTheAnswer() {
        listOf(
            "<head><meta name=\"vougadev-page\" content=\"proxi\"></head>",
            "<HEAD><META charset=utf-8><Meta content='jobs' name='vougadev-page'/></HEAD>",
            "<meta name=vougadev-page content=ship>",
        ).forEach { assertTrue(it, Outage.hasMeta(it.toByteArray())) }
        listOf(
            "error code: 502",
            "<title>Bad gateway | proxi.vougadev.com | Cloudflare</title><meta name=\"robots\" content=\"noindex\">",
            "<meta name=\"vougadev-pages\" content=\"x\">",
            "<p>name=\"vougadev-page\"</p>",
        ).forEach { assertFalse(it, Outage.hasMeta(it.toByteArray())) }
    }

    @Test
    fun theSiteIsBackWithAnyAnswerUnder500() {
        listOf(200, 302, 404).forEach { assertTrue("$it", Outage.back(it)) }
        listOf(500, 502, 503, 504).forEach { assertFalse("$it", Outage.back(it)) }
    }
}

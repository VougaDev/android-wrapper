package com.vougadev.shell.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only a 5xx without the company-page meta is the edge's: the site's own answers are never replaced. */
class OutageTest {

    @Test
    fun theSitesOwnAnswersAreNeverReplacedMetaOrNot() {
        // a CV, an invoice, a ticket, a PDF: generated documents without the meta (Jobs' CV, Echo on the WP35, 2026-10-26)
        listOf(null, 201, 204, 301, 302, 400, 403, 404, 410, 429).forEach {
            assertFalse("$it", Outage.isEdge(it, hasMeta = false))
            assertFalse("$it", Outage.isEdge(it, hasMeta = true))
        }
    }

    @Test
    fun aServerErrorWithoutTheMetaIsTheEdges() {
        listOf(500, 502, 503, 504, 522).forEach { assertTrue("$it", Outage.isEdge(it, hasMeta = false)) }
        // the site's own error page carries the meta: shown
        listOf(500, 503).forEach { assertFalse("$it", Outage.isEdge(it, hasMeta = true)) }
    }

    @Test
    fun theSiteIsBackWithAnyAnswerUnder500() {
        listOf(200, 302, 404).forEach { assertTrue("$it", Outage.back(it)) }
        listOf(500, 502, 503, 504).forEach { assertFalse("$it", Outage.back(it)) }
    }
}

package com.vougadev.shell.tracking

import com.vougadev.shell.net.ApiOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** The position answers as a site gives them, read by [ApiOutcome]; the ending codes are the app's ([TEST_CONFIG]). */
class PingClassifierTest {

    private fun problem(status: Int, code: String) =
        """{"type": "about:blank", "title": "t", "status": $status, "detail": "d", "code": "$code"}"""

    private fun classify(status: Int, body: String?) = PingClassifier.classify(ApiOutcome.classify(status, body), TEST_CONFIG.endedCodes)

    private val csrfPage = """<!DOCTYPE html><html><head><title>403 Forbidden</title></head>
        <body><h1>Forbidden <span>(403)</span></h1><p>CSRF verification failed. Request aborted.</p></body></html>"""

    @Test
    fun `200 is sent`() {
        assertEquals(PingOutcome.SENT, classify(200, """{"tracked_at": "2026-10-23T10:00:00+00:00", "interval_seconds": 60}"""))
    }

    @Test
    fun `an ending code or a 404 ends the task`() {
        TEST_CONFIG.endedCodes.forEach {
            assertEquals(it, PingOutcome.STOP_MISSION, classify(400, problem(400, it)))
        }
        assertEquals(PingOutcome.STOP_MISSION, classify(404, problem(404, "not_found")))
        assertEquals(PingOutcome.STOP_MISSION, classify(404, "<h1>Not Found</h1>"))
    }

    @Test
    fun `other 400s are retried at the next tick`() {
        assertEquals(PingOutcome.RETRY, classify(400, problem(400, "position_missing")))
        assertEquals(PingOutcome.RETRY, classify(400, "Bad Request"))
    }

    @Test
    fun `a lost session is the end of tracking`() {
        assertEquals(PingOutcome.STOP_SESSION, PingClassifier.classify(ApiOutcome.SessionLost, TEST_CONFIG.endedCodes))
        assertEquals(PingOutcome.STOP_SESSION, classify(302, ""))
        assertEquals(PingOutcome.STOP_SESSION, classify(401, ""))
        assertEquals(PingOutcome.STOP_SESSION, classify(403, csrfPage))
    }

    @Test
    fun `network and server errors are retried, and say so`() {
        assertEquals(PingOutcome.RETRY, PingClassifier.classify(ApiOutcome.Offline, TEST_CONFIG.endedCodes))
        assertEquals(TrackingService.ERROR_NETWORK, PingClassifier.retryError(ApiOutcome.Offline))
        listOf(500, 502, 503, 504, 429, 408).forEach {
            val answer = ApiOutcome.classify(it, "<html>oops</html>")
            assertEquals("$it", PingOutcome.RETRY, PingClassifier.classify(answer, TEST_CONFIG.endedCodes))
            assertEquals("http_$it", PingClassifier.retryError(answer))
        }
    }
}

package com.vougadev.shell.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The refusal reader: the site's RFC 9457 problem bodies, and what a lost session looks like. */
class ApiOutcomeTest {

    private fun problem(status: Int, code: String, detail: String) =
        """{"type": "about:blank", "title": "$detail", "status": $status, "detail": "$detail", "code": "$code"}"""

    @Test
    fun aJsonAnswerIsOk() {
        val answer = ApiOutcome.classify(200, """{"results": [], "next_cursor": null}""")
        assertTrue(answer is ApiOutcome.Ok)
    }

    @Test
    fun aRefusalShowsTheServersDetail() {
        val answer = ApiOutcome.classify(400, problem(400, "not_here", "Vous n'êtes pas au point de remise."))
        assertEquals(ApiOutcome.Refused(400, "not_here", "Vous n'êtes pas au point de remise."), answer)
    }

    @Test
    fun aProblem403IsARefusalNotALostSession() {
        val answer = ApiOutcome.classify(403, problem(403, "identity_expired", "Your identity must be verified."))
        assertEquals(ApiOutcome.Refused(403, "identity_expired", "Your identity must be verified."), answer)
    }

    @Test
    fun a404ProblemIsARefusal() {
        assertEquals(ApiOutcome.Refused(404, "not_found", "Not found."), ApiOutcome.classify(404, problem(404, "not_found", "Not found.")))
    }

    @Test
    fun aRedirectIsALostSession() {
        assertEquals(ApiOutcome.SessionLost, ApiOutcome.classify(302, ""))
        assertEquals(ApiOutcome.SessionLost, ApiOutcome.classify(302, null))
    }

    @Test
    fun a403WithoutJsonIsALostSession() {
        assertEquals(ApiOutcome.SessionLost, ApiOutcome.classify(403, "<html><body>CSRF verification failed</body></html>"))
    }

    @Test
    fun a401IsALostSessionEvenWithItsProblemBody() {
        assertEquals(ApiOutcome.SessionLost, ApiOutcome.classify(401, problem(401, "unauthenticated", "Sign in.")))
    }

    @Test
    fun anEdgeErrorPageIsAFailure() {
        assertEquals(ApiOutcome.Failed(502), ApiOutcome.classify(502, "<html>Bad gateway</html>"))
        assertEquals(ApiOutcome.Failed(500), ApiOutcome.classify(500, "{not json"))
        assertEquals(ApiOutcome.Failed(500), ApiOutcome.classify(500, "{}"))
    }

    @Test
    fun a2xxThatIsNotJsonIsAFailure() {
        assertEquals(ApiOutcome.Failed(200), ApiOutcome.classify(200, "<html>sign in</html>"))
    }
}

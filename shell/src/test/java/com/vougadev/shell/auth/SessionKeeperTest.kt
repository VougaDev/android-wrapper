package com.vougadev.shell.auth

import com.vougadev.shell.net.ApiOutcome
import com.vougadev.shell.net.AppCaller
import com.vougadev.shell.web.AppOrigin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A LOST SESSION -> THE REFRESH (rotated token stored) -> A NEW SITE SESSION, silently; the sign-in screen only when the account service
 * refuses the refresh. Then the same path as a native call meets it ([AppCaller]).
 */
class SessionKeeperTest {

    private val store = TokenStore(MapValues(), FakeSealer()).apply { save("rt-1") }
    private val jar = FakeJar("csrftoken=old; sessionid=lost")
    private val refreshed = mutableListOf<String>()
    private val exchanged = mutableListOf<String>()
    private var refreshAnswer: (String) -> TokenAnswer = { TokenAnswer.Tokens("at-2", "rt-2") }
    private var exchangeAnswer: (String) -> Exchange = {
        Exchange.Opened(listOf("sessionid=fresh; HttpOnly; Path=/; Secure", "csrftoken=new; Path=/; Secure"))
    }

    private val keeper = SessionKeeper(
        store,
        { token -> refreshed += token; delay(10); refreshAnswer(token) },
        { access -> exchanged += access; exchangeAnswer(access) },
        jar,
    )

    @Test
    fun aLostSessionIsRefreshedThenExchangedAndTheRotatedTokenKept() = runBlocking {
        assertEquals(Renewal.RENEWED, keeper.renew("csrftoken=old; sessionid=lost"))
        assertEquals(listOf("rt-1"), refreshed)
        assertEquals(listOf("at-2"), exchanged)
        assertEquals("rt-2", store.refreshToken())
        assertEquals("csrftoken=new; sessionid=fresh", jar.text)
    }

    @Test
    fun aRefusedRefreshForgetsTheTokenAndAsksForTheSignIn() = runBlocking {
        refreshAnswer = { TokenAnswer.Refused }
        assertEquals(Renewal.SIGN_IN, keeper.renew(jar.text))
        assertNull(store.refreshToken())
        assertTrue(exchanged.isEmpty())
        assertEquals("csrftoken=old; sessionid=lost", jar.text)
    }

    @Test
    fun authOutOfReachKeepsTheTokenForTheNextTry() = runBlocking {
        refreshAnswer = { TokenAnswer.Unreachable }
        assertEquals(Renewal.OFFLINE, keeper.renew(jar.text))
        assertEquals("rt-1", store.refreshToken())
    }

    @Test
    fun withoutATokenNothingIsAskedTheSignInIsShown() = runBlocking {
        store.clear()
        jar.text = null
        assertEquals(Renewal.SIGN_IN, keeper.renew(null))
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun siteRefusingAFreshTokenAsksForTheSignInAndSiteDownWaits() = runBlocking {
        exchangeAnswer = { Exchange.Refused }
        assertEquals(Renewal.SIGN_IN, keeper.renew(jar.text))
        exchangeAnswer = { Exchange.Failed(502) }
        assertEquals(Renewal.OFFLINE, keeper.renew(jar.text))
        exchangeAnswer = { Exchange.Offline }
        assertEquals(Renewal.OFFLINE, keeper.renew(jar.text))
    }

    @Test
    fun callersLosingTheSameSessionTogetherSpendTheRefreshTokenOnce() = runBlocking {
        val lost = jar.text
        val answers = (1..3).map { async { keeper.renew(lost) } }.awaitAll()
        assertEquals(listOf(Renewal.RENEWED, Renewal.RENEWED, Renewal.RENEWED), answers)
        assertEquals(listOf("rt-1"), refreshed)
    }

    @Test
    fun aCallerThatWaitedWhileAnotherRenewedRetriesWithoutARefresh() = runBlocking {
        // the call was made with no session; one is in the jar now
        assertEquals(Renewal.RENEWED, keeper.renew(null))
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun theBrowserSignInKeepsItsTokensAndOpensTheSession() = runBlocking {
        store.clear()
        assertEquals(Renewal.RENEWED, keeper.signedIn("at-9", "rt-9"))
        assertEquals("rt-9", store.refreshToken())
        assertEquals(listOf("at-9"), exchanged)
    }

    // --- The same path from a native call --------------------------------------------------------------------------

    private val origin = AppOrigin("https://app.example.org")
    private val mission = "/api/v1/courier/missions/open?size=12"

    private fun caller(server: FakeServer, renew: suspend (String?) -> Renewal = keeper::renew) =
        AppCaller(origin, jar::jar, { "UA" }, { "fr" }, renew, server.client)

    @Test
    fun aNativeCallThatLostTheSessionIsRenewedAndMadeAgainWithTheNewCookies() = runBlocking {
        val server = FakeServer { request ->
            if (request.header("Cookie")!!.contains("sessionid=lost")) {
                FakeServer.response(request, 302, headers = listOf("Location" to "/auth/login/?next=/"))
            } else {
                FakeServer.response(request, 200, """{"results": [], "next_cursor": null}""")
            }
        }
        val answer = caller(server).get(mission)
        assertTrue(answer is ApiOutcome.Ok)
        assertEquals(2, server.requests.size)
        assertEquals("csrftoken=new; sessionid=fresh", server.requests[1].header("Cookie"))
        assertEquals(listOf("rt-1"), refreshed)
    }

    @Test
    fun aPostIsRemadeWithTheNewCsrfToken() = runBlocking {
        val server = FakeServer { request ->
            if (request.header("X-CSRFToken") == "old") FakeServer.response(request, 403, "<html>CSRF</html>")
            else FakeServer.response(request, 200, """{"id": "x"}""")
        }
        val answer = caller(server).postJson("/api/v1/courier/missions/x/accept", org.json.JSONObject())
        assertTrue(answer is ApiOutcome.Ok)
        assertEquals("new", server.requests[1].header("X-CSRFToken"))
        assertEquals("{}", Buffer().also { server.requests[1].body!!.writeTo(it) }.readUtf8())
    }

    @Test
    fun aNativeCallReportsTheLostSessionOnlyWhenTheRefreshIsRefused() = runBlocking {
        refreshAnswer = { TokenAnswer.Refused }
        val server = FakeServer { FakeServer.response(it, 401, """{"code": "unauthenticated", "detail": "x"}""") }
        assertEquals(ApiOutcome.SessionLost, caller(server).get(mission))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aNativeCallWhoseRenewalCannotReachAuthIsOffline() = runBlocking {
        refreshAnswer = { TokenAnswer.Unreachable }
        val server = FakeServer { FakeServer.response(it, 302) }
        assertEquals(ApiOutcome.Offline, caller(server).get(mission))
    }

    @Test
    fun aRefusalInTheServersWordsIsNotALostSession() = runBlocking {
        val server = FakeServer { FakeServer.response(it, 403, """{"code": "identity_expired", "detail": "Renew it."}""") }
        assertEquals(ApiOutcome.Refused(403, "identity_expired", "Renew it."), caller(server).get(mission))
        assertTrue(refreshed.isEmpty())
    }
}

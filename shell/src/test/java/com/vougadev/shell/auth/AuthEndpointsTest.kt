package com.vougadev.shell.auth

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The refresh and the revocation, as the account service's discovery names their endpoints; the app's public client, no secret. */
class AuthEndpointsTest {

    private val client = AuthClient("https://auth.example.org", "test-client", "org.example.app:/oauth2redirect")
    private val discovery = Discovery.parse(
        """{"issuer": "https://auth.example.org", "authorization_endpoint": "https://auth.example.org/identity/o/authorize",
            "token_endpoint": "https://auth.example.org/identity/o/api/token",
            "revocation_endpoint": "https://auth.example.org/identity/o/api/revoke"}""",
    )!!

    private fun form(request: okhttp3.Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test
    fun theRefreshIsAFormPostOfThePublicClient() {
        val r = AuthEndpoints.refreshRequest(discovery.tokenEndpoint, client.clientId, "rt-1")
        assertEquals("https://auth.example.org/identity/o/api/token", r.url.toString())
        assertEquals("POST", r.method)
        assertEquals("grant_type=refresh_token&refresh_token=rt-1&client_id=test-client", form(r))
        assertNull(r.header("Authorization"))
    }

    @Test
    fun theRevocationNamesTheRefreshToken() {
        val r = AuthEndpoints.revokeRequest(discovery.revocationEndpoint!!, client.clientId, "rt-1")
        assertEquals("https://auth.example.org/identity/o/api/revoke", r.url.toString())
        assertEquals("token=rt-1&token_type_hint=refresh_token&client_id=test-client", form(r))
    }

    @Test
    fun tokenAnswersAreRead() {
        assertEquals(TokenAnswer.Tokens("at", "rt"), AuthEndpoints.readTokens(200, """{"access_token": "at", "refresh_token": "rt", "expires_in": 600}"""))
        assertEquals(TokenAnswer.Tokens("at", null), AuthEndpoints.readTokens(200, """{"access_token": "at"}"""))
        assertEquals(TokenAnswer.Refused, AuthEndpoints.readTokens(400, """{"error": "invalid_grant"}"""))
        assertEquals(TokenAnswer.Refused, AuthEndpoints.readTokens(401, """{"error": "invalid_client"}"""))
        assertEquals(TokenAnswer.Unreachable, AuthEndpoints.readTokens(503, "<html>"))
        assertEquals(TokenAnswer.Unreachable, AuthEndpoints.readTokens(200, "{}"))
    }

    @Test
    fun aRefreshWithoutNetworkOrDiscoveryIsUnreachable() = runBlocking {
        assertEquals(TokenAnswer.Unreachable, AuthEndpoints(client, FakeServer { null }.client) { discovery }.refresh("rt-1"))
        assertEquals(TokenAnswer.Unreachable, AuthEndpoints(client, FakeServer { null }.client) { null }.refresh("rt-1"))
        val server = FakeServer { FakeServer.response(it, 200, JSONObject().put("access_token", "at-2").put("refresh_token", "rt-2").toString()) }
        assertEquals(TokenAnswer.Tokens("at-2", "rt-2"), AuthEndpoints(client, server.client) { discovery }.refresh("rt-1"))
    }

    @Test
    fun aDiscoveryMissingAnEndpointOrNotHttpsIsRefused() {
        assertNotNull(discovery)
        assertNull(Discovery.parse("""{"issuer": "https://a", "authorization_endpoint": "https://a/o", "token_endpoint": "http://a/t"}"""))
        assertNull(Discovery.parse("""{"issuer": "https://a"}"""))
        assertNull(Discovery.parse("<html>"))
        assertEquals("https://auth.example.org/.well-known/openid-configuration", Discovery.path("https://auth.example.org/"))
    }
}

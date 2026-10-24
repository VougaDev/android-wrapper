package com.vougadev.shell.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** The app's the account service client: public (no secret), PKCE S256, its redirect a scheme of the app's own package. */
data class AuthClient(val issuer: String, val clientId: String, val redirectUri: String)

/** What the account service's `/.well-known/openid-configuration` names; kept on the phone so a refresh costs one request. */
data class Discovery(val json: JSONObject) {
    val tokenEndpoint: String get() = json.optString("token_endpoint")
    val revocationEndpoint: String? get() = json.optString("revocation_endpoint").takeIf { it.isNotBlank() }

    companion object {
        fun path(issuer: String) = issuer.trimEnd('/') + "/.well-known/openid-configuration"

        /** Null unless it names at least the endpoints the app calls, on https. */
        fun parse(text: String?): Discovery? {
            val json = try {
                JSONObject(text ?: return null)
            } catch (_: JSONException) {
                return null
            }
            val names = listOf("issuer", "authorization_endpoint", "token_endpoint")
            return Discovery(json).takeIf { names.all { json.optString(it).startsWith("https://") } }
        }
    }
}

/** One token endpoint answer. */
sealed interface TokenAnswer {
    data class Tokens(val accessToken: String, val refreshToken: String?) : TokenAnswer

    /** the account service said no (`invalid_grant`: revoked, expired or already spent): the person signs in again. */
    data object Refused : TokenAnswer

    /** No network, or the account service out of reach: the token is kept for the next try. */
    data object Unreachable : TokenAnswer
}

/** Refreshing a token, as the [SessionKeeper] needs it; the tests give a fake. */
fun interface Refresher {
    suspend fun refresh(refreshToken: String): TokenAnswer
}

/**
 * the account service's token and revocation endpoints over OkHttp (a refresh is one form POST; AppAuth is kept for the browser flow
 * and its code exchange, which checks the ID token). [discovery] is read when a call is made, from the phone's copy.
 */
class AuthEndpoints(
    private val client: AuthClient,
    private val http: OkHttpClient,
    private val discovery: suspend () -> Discovery?,
) : Refresher {

    override suspend fun refresh(refreshToken: String): TokenAnswer {
        val found = discovery() ?: return TokenAnswer.Unreachable
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(refreshRequest(found.tokenEndpoint, client.clientId, refreshToken)).execute().use {
                    readTokens(it.code, it.body.string())
                }
            } catch (_: IOException) {
                TokenAnswer.Unreachable
            }
        }
    }

    /** RFC 7009, best effort: the token is forgotten on the phone whatever the account service answers. */
    suspend fun revoke(refreshToken: String) {
        val endpoint = discovery()?.revocationEndpoint ?: return
        withContext(Dispatchers.IO) {
            try {
                http.newCall(revokeRequest(endpoint, client.clientId, refreshToken)).execute().close()
            } catch (_: IOException) {
                // signing out on the phone goes on; the account service lets the token expire
            }
        }
    }

    companion object {
        fun refreshRequest(endpoint: String, clientId: String, refreshToken: String): Request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .add("client_id", clientId)
                    .build(),
            )
            .build()

        fun revokeRequest(endpoint: String, clientId: String, refreshToken: String): Request = Request.Builder()
            .url(endpoint)
            .post(
                FormBody.Builder()
                    .add("token", refreshToken)
                    .add("token_type_hint", "refresh_token")
                    .add("client_id", clientId)
                    .build(),
            )
            .build()

        /** 200 with an access token; 400 or 401 (`invalid_grant`, `invalid_client`) refuses; anything else is unreachable. */
        fun readTokens(status: Int, body: String?): TokenAnswer {
            val json = try {
                JSONObject(body.orEmpty())
            } catch (_: JSONException) {
                null
            }
            val access = json?.optString("access_token").orEmpty()
            return when {
                status == 200 && access.isNotBlank() -> TokenAnswer.Tokens(access, json?.optString("refresh_token")?.takeIf { it.isNotBlank() })
                status == 400 || status == 401 -> TokenAnswer.Refused
                else -> TokenAnswer.Unreachable
            }
        }
    }
}

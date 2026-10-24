package com.vougadev.shell.auth

import com.vougadev.shell.net.SessionCookies
import com.vougadev.shell.web.AppOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What `POST /auth/app-session/` answered. */
sealed interface Exchange {
    /** 204: the site's `Set-Cookie` lines (`sessionid`, `csrftoken`), for the WebView's cookie jar. */
    data class Opened(val setCookies: List<String>) : Exchange

    /** 401: the site did not accept the token (expired, another client's, or the account service could not say who it is). */
    data object Refused : Exchange

    data object Offline : Exchange

    data class Failed(val status: Int) : Exchange
}

/**
 * THE APP'S the account service ACCESS TOKEN TRADED FOR THE SITE'S SESSION: a POST with
 * `Authorization: Bearer <token>` and no cookie, answered 204 with the session's cookies. Those go into the WebView's
 * jar, and the WebView and the native calls are signed in as that person.
 */
class SessionExchange(
    private val origin: AppOrigin,
    private val userAgent: () -> String,
    private val acceptLanguage: () -> String,
    private val http: OkHttpClient,
) {
    suspend fun open(accessToken: String): Exchange = withContext(Dispatchers.IO) {
        try {
            http.newCall(request(origin, accessToken, userAgent(), acceptLanguage())).execute().use {
                read(it.code, it.headers("Set-Cookie"))
            }
        } catch (_: IOException) {
            Exchange.Offline
        }
    }

    companion object {
        const val PATH = "/auth/app-session/"

        /** No cookie (a bearer token is the whole proof, the view is CSRF-exempt), the site's Referer as on every call. */
        fun request(origin: AppOrigin, accessToken: String, userAgent: String, acceptLanguage: String): Request = Request.Builder()
            .url(origin.baseUrl + PATH)
            .header("Authorization", "Bearer $accessToken")
            .header("Referer", origin.baseUrl + "/")
            .header("Accept", "application/json")
            .header("Accept-Language", acceptLanguage)
            .header("User-Agent", userAgent)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        /** A session only when the answer carries `sessionid`: a 204 without it opened nothing. */
        fun read(status: Int, setCookies: List<String>): Exchange = when {
            status in 200..299 && setCookies.any { SessionCookies.valueOf(it.substringBefore(';'), SessionCookies.SESSION_COOKIE) != null } ->
                Exchange.Opened(setCookies)
            status == 401 || status == 403 -> Exchange.Refused
            else -> Exchange.Failed(status)
        }
    }
}

package com.vougadev.shell.net

import com.vougadev.shell.auth.Renewal
import com.vougadev.shell.web.AppOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * One call to the site's API as the WebView's session ([AppHttp]'s headers), classified ([ApiOutcome]).
 *
 * A LOST SESSION IS RENEWED BEFORE IT IS REPORTED: [renew] refreshes the the account service token and opens a new site session, and
 * the call is made once more with the new cookies. Only when the renewal needs the person to sign in is the answer
 * [ApiOutcome.SessionLost]; when the account service or the site cannot be reached it is [ApiOutcome.Offline].
 */
class AppCaller(
    private val origin: AppOrigin,
    private val cookies: () -> String?,
    private val userAgent: () -> String,
    private val acceptLanguage: () -> String,
    private val renew: suspend (lostJar: String?) -> Renewal,
    private val client: OkHttpClient = AppHttp.client,
) {
    suspend fun get(path: String): ApiOutcome = call(path, unsafe = false) { it.get() }

    suspend fun post(path: String, body: RequestBody): ApiOutcome = call(path, unsafe = true) { it.post(body) }

    suspend fun postJson(path: String, body: JSONObject): ApiOutcome = post(path, body.toString().toRequestBody(JSON))

    suspend fun putJson(path: String, body: JSONObject): ApiOutcome = call(path, unsafe = true) { it.put(body.toString().toRequestBody(JSON)) }

    /** A small image or file of the site's (a photo), read once as the session; null on any failure. */
    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val jar = cookies()
        if (jar.isNullOrBlank()) return@withContext null
        val headers = AppHttp.headers(origin, jar, userAgent(), acceptLanguage(), unsafe = false) ?: return@withContext null
        try {
            client.newCall(Request.Builder().url(origin.baseUrl + path).headers(headers).header("Accept", "image/*").get().build())
                .execute().use { if (it.isSuccessful) it.body.bytes() else null }
        } catch (_: IOException) {
            null
        }
    }

    /** The jar and headers of the WebView's session, for a download this class does not make (the offline map). */
    fun session(): Triple<AppOrigin, String, Pair<String, String>>? = cookies()?.takeIf(String::isNotBlank)?.let {
        Triple(origin, it, userAgent() to acceptLanguage())
    }

    private suspend fun call(path: String, unsafe: Boolean, method: (Request.Builder) -> Request.Builder): ApiOutcome {
        val jar = cookies()
        val first = once(jar, path, unsafe, method)
        if (first != ApiOutcome.SessionLost) return first
        return when (renew(jar)) {
            Renewal.RENEWED -> once(cookies(), path, unsafe, method)
            Renewal.OFFLINE -> ApiOutcome.Offline
            Renewal.SIGN_IN -> ApiOutcome.SessionLost
        }
    }

    private suspend fun once(jar: String?, path: String, unsafe: Boolean, method: (Request.Builder) -> Request.Builder): ApiOutcome =
        withContext(Dispatchers.IO) {
            if (jar.isNullOrBlank()) return@withContext ApiOutcome.SessionLost
            val headers = AppHttp.headers(origin, jar, userAgent(), acceptLanguage(), unsafe) ?: return@withContext ApiOutcome.SessionLost
            val request = method(Request.Builder().url(origin.baseUrl + path).headers(headers)).build()
            try {
                client.newCall(request).execute().use { ApiOutcome.classify(it.code, it.body.string()) }
            } catch (_: IOException) {
                ApiOutcome.Offline
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

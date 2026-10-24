package com.vougadev.shell.net

import org.json.JSONException
import org.json.JSONObject

/**
 * What one call to the site's API answered, read from its status and body. The site refuses with RFC 9457 problem bodies
 * `{"type", "title", "status", "detail", "code"}`, `detail` translated into the request's
 * language - that sentence is what the screen shows.
 */
sealed interface ApiOutcome {
    data class Ok(val body: JSONObject) : ApiOutcome

    /** The server said no, in its own words. */
    data class Refused(val status: Int, val code: String, val detail: String) : ApiOutcome

    /** No network, or it broke mid-call: what the app stored stays on screen. */
    data object Offline : ApiOutcome

    /**
     * The WebView's session is gone: a redirect (to the sign-in), a 401 (the site's `unauthenticated`), or a 403 without
     * a problem body (the site's CSRF refusal page). The store is cleared and the web signs in again.
     */
    data object SessionLost : ApiOutcome

    /** Neither an answer nor a refusal (an edge's HTML error page, a body that is not JSON). */
    data class Failed(val status: Int) : ApiOutcome

    companion object {
        fun classify(status: Int, body: String?): ApiOutcome {
            val json = parse(body)
            val detail = json?.optString("detail").orEmpty()
            val code = json?.optString("code").orEmpty()
            return when {
                status in 200..299 && json != null -> Ok(json)
                status in 300..399 || status == 401 -> SessionLost
                status == 403 && json == null -> SessionLost
                json != null && (detail.isNotBlank() || code.isNotBlank()) -> Refused(status, code, detail)
                else -> Failed(status)
            }
        }

        private fun parse(body: String?): JSONObject? {
            val text = body?.trim().orEmpty()
            if (!text.startsWith("{")) return null
            return try {
                JSONObject(text)
            } catch (_: JSONException) {
                null
            }
        }
    }
}

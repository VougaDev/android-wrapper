package com.vougadev.shell.web

import org.json.JSONException
import org.json.JSONObject

/**
 * The Kotlin end of `window.VougaDevNative` ([NativeShim]): one message in, one reply out, handed to the app's own
 * [methods] by name.
 *
 * The WebView delivers a message only from a frame whose origin matches the listener's rule, and this class checks the
 * source origin again anyway: the rule is the platform's promise, this is ours, and it is the one the JVM tests can
 * break. Pure Kotlin - the activity passes the message text, the source origin as a string and a function that posts the
 * reply to that frame.
 *
 * Every call is answered exactly once, `{"id": n, "result": {...}}`, however often a method calls its answer. A message
 * without a numeric id cannot be answered (the shim never sends one) and is dropped.
 */
class NativeBridge(private val origin: AppOrigin, private val methods: Map<String, Method>) {

    /**
     * One member of the app's features. Called on the main thread with the call's `args` object (null when the page
     * gave none, or not an object); [answer] may be called long after this returns - after the person answered the
     * system dialogs.
     */
    fun interface Method {
        fun call(args: JSONObject?, answer: (JSONObject) -> Unit)
    }

    fun onMessage(message: String?, sourceOrigin: String?, reply: (String) -> Unit) {
        val envelope = parseEnvelope(message) ?: return
        val once = Once(envelope.id, reply)
        if (!origin.matches(sourceOrigin)) {
            once.send(refused(REFUSED_ORIGIN))
            return
        }
        val method = envelope.method?.let(methods::get) ?: return once.send(refused(UNKNOWN_METHOD))
        method.call(envelope.args, once::send)
    }

    private class Envelope(val id: Long, val method: String?, val args: JSONObject?)

    private class Once(private val id: Long, private val reply: (String) -> Unit) {
        private var sent = false
        fun send(result: JSONObject) {
            if (sent) return
            sent = true
            reply(JSONObject().put("id", id).put("result", result).toString())
        }
    }

    private fun parseEnvelope(message: String?): Envelope? {
        if (message.isNullOrBlank()) return null
        val obj = try {
            JSONObject(message)
        } catch (_: JSONException) {
            return null
        }
        val id = (obj.opt("id") as? Number)?.toLong() ?: return null
        return Envelope(id, obj.opt("method") as? String, obj.optJSONObject("args"))
    }

    companion object {
        const val REFUSED_ORIGIN = "origin_refused"
        const val UNKNOWN_METHOD = "unknown_method"

        fun ok(): JSONObject = JSONObject().put("ok", true)

        fun refused(reason: String): JSONObject = JSONObject().put("ok", false).put("error", reason)
    }
}

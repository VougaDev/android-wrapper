package com.vougadev.shell.web

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBridgeTest {

    private val origin = AppOrigin("https://app.example.org")
    private val site = "https://app.example.org"

    private val calls = mutableListOf<String>()
    private var pending: ((JSONObject) -> Unit)? = null
    private var lastArgs: JSONObject? = null

    private val bridge = NativeBridge(
        origin,
        mapOf(
            "later" to NativeBridge.Method { args, answer ->
                calls += "later"
                lastArgs = args
                pending = answer
            },
            "now" to NativeBridge.Method { _, answer ->
                calls += "now"
                answer(NativeBridge.ok())
            },
        ),
    )
    private val replies = mutableListOf<JSONObject>()

    private fun send(message: String?, source: String? = site) = bridge.onMessage(message, source) { replies += JSONObject(it) }

    private fun message(n: Int, method: String, args: Any? = null) =
        JSONObject().put("id", n).put("method", method).put("args", args ?: JSONObject.NULL).toString()

    @Test
    fun `a method answers once, when it answers, on the call's id`() {
        send(message(7, "later", JSONObject().put("a", 1)))
        assertEquals(listOf("later"), calls)
        assertEquals(1, lastArgs!!.getInt("a"))
        assertTrue("answered before the method did", replies.isEmpty())

        pending!!(NativeBridge.ok())
        assertEquals(1, replies.size)
        assertEquals(7, replies[0].getInt("id"))
        assertTrue(replies[0].getJSONObject("result").getBoolean("ok"))

        // A second answer from a confused method never reaches the page.
        pending!!(NativeBridge.refused("late"))
        assertEquals(1, replies.size)
    }

    @Test
    fun `args that are not an object reach the method as null`() {
        send(message(1, "later", "text"))
        assertNull(lastArgs)
        send(message(2, "later"))
        assertNull(lastArgs)
    }

    @Test
    fun `a refusal carries its code`() {
        send(message(1, "later"))
        pending!!(NativeBridge.refused("location_disabled"))
        val result = replies.single().getJSONObject("result")
        assertEquals(false, result.getBoolean("ok"))
        assertEquals("location_disabled", result.getString("error"))
    }

    @Test
    fun `a message from another origin is refused and nothing runs`() {
        listOf("https://evil.io", "https://app.example.org.evil.io", "http://app.example.org", "null", "", null).forEach { source ->
            replies.clear()
            send(message(9, "now"), source)
            send(message(10, "later"), source)
            assertEquals("$source", emptyList<String>(), calls)
            assertEquals(2, replies.size)
            replies.forEach { assertEquals(NativeBridge.REFUSED_ORIGIN, it.getJSONObject("result").getString("error")) }
        }
    }

    @Test
    fun `an unknown method is refused and an unanswerable message dropped`() {
        send(message(3, "openCamera"))
        send("""{"id":4}""")
        assertEquals(listOf(NativeBridge.UNKNOWN_METHOD, NativeBridge.UNKNOWN_METHOD), replies.map { it.getJSONObject("result").getString("error") })
        replies.clear()
        send(null)
        send("not json")
        send("""{"method":"now"}""")
        send("""{"id":"1","method":"now"}""")
        assertTrue(replies.isEmpty())
        assertEquals(emptyList<String>(), calls)
    }
}

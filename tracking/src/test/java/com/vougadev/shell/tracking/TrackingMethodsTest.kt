package com.vougadev.shell.tracking

import com.vougadev.shell.web.AppOrigin
import com.vougadev.shell.web.NativeBridge
import com.vougadev.shell.web.NativeShim
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `tracking` extension's members through the shell's bridge, as an activity wires them. */
class TrackingMethodsTest {

    private val origin = AppOrigin("https://app.example.org")
    private val site = "https://app.example.org"
    private val id = "3f2b7c1e-8a4d-4c1b-9a53-0d6e2f1a9b77"
    private val calls = mutableListOf<String>()

    /** A phone that grants everything at once. */
    private val phone = object : TrackingStartFlow.Phone {
        override fun disclose(then: (Boolean) -> Unit) = then(true)
        override fun hasPreciseLocation() = true
        override fun hasApproximateLocation() = true
        override fun askLocation(then: () -> Unit) = then()
        override fun shouldAskNotifications() = false
        override fun askNotifications(then: () -> Unit) = then()
        override fun ensureLocationOn(then: (Boolean) -> Unit) = then(true)
        override fun start(request: TrackingRequest) {
            calls += "start:${request.missionId}:${request.path}"
        }
        override fun recordRefusal(missionId: String, error: String) {
            calls += "refused:$error"
        }
    }

    private var state = """{"active":true,"missionId":"m","lastSentAt":null,"lastError":null}"""
    private val methods = TrackingExtension.methods(TEST_CONFIG, TrackingStartFlow(phone), { state }) { calls += "stop" }
    private val bridge = NativeBridge(origin, methods)
    private val replies = mutableListOf<JSONObject>()

    private fun send(message: String) = bridge.onMessage(message, site) { replies += JSONObject(it) }

    private fun result() = replies.last().getJSONObject("result")

    @Test
    fun `the members are exactly the extension's, in its order`() {
        assertEquals(listOf("startTracking", "stopTracking", "trackingState"), methods.keys.toList())
        val script = NativeShim.script("1.0.0", listOf("tracking"), methods.keys.toList())
        assertTrue(script.contains("""features: function () { return ["tracking"]; }"""))
    }

    @Test
    fun `a start goes to the id's own address once the phone allowed it`() {
        send("""{"id":1,"method":"startTracking","args":{"missionId":"$id"}}""")
        assertEquals(listOf("start:$id:/api/tasks/$id/position"), calls)
        assertTrue(result().getBoolean("ok"))
    }

    @Test
    fun `a bad start object is refused without reaching the phone`() {
        send("""{"id":1,"method":"startTracking","args":null}""")
        send("""{"id":2,"method":"startTracking","args":{"missionId":"$id","url":"https://evil.io/api/tasks/$id/position"}}""")
        send("""{"id":3,"method":"startTracking","args":{"missionId":"42"}}""")
        assertEquals(listOf("invalid_request", "invalid_url", "invalid_mission_id"), replies.map { it.getJSONObject("result").getString("error") })
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `stop and state answer at once, an unreadable state with its code`() {
        send("""{"id":4,"method":"stopTracking"}""")
        assertEquals(listOf("stop"), calls)
        send("""{"id":5,"method":"trackingState"}""")
        assertEquals("m", result().getString("missionId"))
        state = "not json"
        send("""{"id":6,"method":"trackingState"}""")
        assertEquals(TrackingExtension.ERROR_STATE_UNREADABLE, result().getString("error"))
    }
}

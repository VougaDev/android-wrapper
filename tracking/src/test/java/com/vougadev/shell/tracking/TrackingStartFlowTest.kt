package com.vougadev.shell.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingStartFlowTest {

    private val request = TrackingRequest(
        missionId = "3f2b7c1e-8a4d-4c1b-9a53-0d6e2f1a9b77",
        path = "/api/tasks/3f2b7c1e-8a4d-4c1b-9a53-0d6e2f1a9b77/position",
        intervalSeconds = 60,
        target = null,
        title = null,
        text = null,
    )

    /**
     * A phone whose dialogs are answered from a script: each location dialog pops the next
     * (precise, approximate) answer; the switch dialog answers [switchAnswer]. Dialogs stay open
     * (their `then` held) when [holdDialogs] is set, as on a real phone.
     */
    private class ScriptedPhone(
        var precise: Boolean = false,
        var approximate: Boolean = false,
        val locationAnswers: ArrayDeque<Pair<Boolean, Boolean>> = ArrayDeque(),
        var notificationsMissing: Boolean = false,
        var locationOn: Boolean = true,
        var switchAnswer: Boolean = false,
        var holdDialogs: Boolean = false,
        var disclosed: Boolean = true,
    ) : TrackingStartFlow.Phone {
        val log = mutableListOf<String>()
        val held = ArrayDeque<() -> Unit>()

        override fun disclose(then: (Boolean) -> Unit) {
            if (!disclosed) log += "disclose"  // an agreed disclosure shows nothing: the other tests' logs stay as they are
            then(disclosed)
        }

        override fun hasPreciseLocation() = precise
        override fun hasApproximateLocation() = approximate
        override fun askLocation(then: () -> Unit) {
            log += "ask-location"
            val answer = {
                locationAnswers.removeFirstOrNull()?.let { (p, a) ->
                    precise = p
                    approximate = a
                }
                then()
            }
            if (holdDialogs) held.addLast(answer) else answer()
        }
        override fun shouldAskNotifications() = notificationsMissing
        override fun askNotifications(then: () -> Unit) {
            log += "ask-notifications"
            notificationsMissing = false
            then()
        }
        override fun ensureLocationOn(then: (Boolean) -> Unit) {
            if (locationOn) return then(true)
            log += "ask-switch"
            locationOn = switchAnswer
            then(switchAnswer)
        }
        override fun start(request: TrackingRequest) {
            log += "start"
        }
        override fun recordRefusal(missionId: String, error: String) {
            log += "refused:$error"
        }
    }

    @Test
    fun `a declined disclosure starts nothing and asks no permission`() {
        val phone = ScriptedPhone(precise = true, approximate = true, disclosed = false)
        assertEquals(listOf<String?>("tracking_declined"), run(phone))
        assertEquals(listOf("disclose", "refused:tracking_declined"), phone.log)
    }

    private fun run(phone: ScriptedPhone): MutableList<String?> {
        val answers = mutableListOf<String?>()
        TrackingStartFlow(phone).submit(request) { answers += it }
        return answers
    }

    @Test
    fun `everything granted and on starts without a single dialog`() {
        val phone = ScriptedPhone(precise = true, approximate = true)
        assertEquals(listOf<String?>(null), run(phone))
        assertEquals(listOf("start"), phone.log)
    }

    @Test
    fun `the three dialogs come in order and the answer waits for the last`() {
        val phone = ScriptedPhone(
            locationAnswers = ArrayDeque(listOf(true to true)),
            notificationsMissing = true,
            locationOn = false,
            switchAnswer = true,
        )
        assertEquals(listOf<String?>(null), run(phone))
        assertEquals(listOf("ask-location", "ask-notifications", "ask-switch", "start"), phone.log)
    }

    @Test
    fun `approximate only asks once more for precise`() {
        val granted = ScriptedPhone(locationAnswers = ArrayDeque(listOf(false to true, true to true)))
        assertEquals(listOf<String?>(null), run(granted))
        assertEquals(listOf("ask-location", "ask-location", "start"), granted.log)

        val refused = ScriptedPhone(locationAnswers = ArrayDeque(listOf(false to true, false to true)))
        assertEquals(listOf<String?>("location_permission_denied"), run(refused))
        assertEquals(listOf("ask-location", "ask-location", "refused:location_permission_denied"), refused.log)
    }

    @Test
    fun `approximate already held means the first dialog was the precise one`() {
        val phone = ScriptedPhone(approximate = true, locationAnswers = ArrayDeque(listOf(false to true)))
        assertEquals(listOf<String?>("location_permission_denied"), run(phone))
        assertEquals(listOf("ask-location", "refused:location_permission_denied"), phone.log)
    }

    @Test
    fun `location refused stops before notifications and the switch`() {
        val phone = ScriptedPhone(locationAnswers = ArrayDeque(listOf(false to false)), notificationsMissing = true, locationOn = false)
        assertEquals(listOf<String?>("location_permission_denied"), run(phone))
        assertEquals(listOf("ask-location", "refused:location_permission_denied"), phone.log)
    }

    @Test
    fun `refused notifications do not block tracking`() {
        val phone = object : ScriptedPhoneBase() {
            override fun askNotifications(then: () -> Unit) {
                log += "ask-notifications-refused"
                then()
            }
        }
        val answers = mutableListOf<String?>()
        TrackingStartFlow(phone).submit(request) { answers += it }
        assertEquals(listOf<String?>(null), answers)
        assertEquals(listOf("ask-notifications-refused", "start"), phone.log)
    }

    @Test
    fun `location left off is location_disabled`() {
        val phone = ScriptedPhone(precise = true, approximate = true, locationOn = false, switchAnswer = false)
        assertEquals(listOf<String?>("location_disabled"), run(phone))
        assertEquals(listOf("ask-switch", "refused:location_disabled"), phone.log)
    }

    @Test
    fun `a second start waits for the first and reuses its permission`() {
        val phone = ScriptedPhone(locationAnswers = ArrayDeque(listOf(true to true)), holdDialogs = true)
        val flow = TrackingStartFlow(phone)
        val answers = mutableListOf<String>()
        flow.submit(request) { answers += "first:$it" }
        flow.submit(request.copy(intervalSeconds = 120)) { answers += "second:$it" }
        assertEquals(listOf("ask-location"), phone.log)
        assertTrue(answers.isEmpty())

        phone.held.removeFirst()()
        assertEquals(listOf("first:null", "second:null"), answers)
        assertEquals(listOf("ask-location", "start", "start"), phone.log)
    }

    @Test
    fun `an abandoned flow never starts nor answers`() {
        val phone = ScriptedPhone(locationAnswers = ArrayDeque(listOf(true to true)), holdDialogs = true)
        val flow = TrackingStartFlow(phone)
        val answers = mutableListOf<String?>()
        flow.submit(request) { answers += it }
        flow.abandon()
        phone.held.removeFirst()()
        assertTrue(answers.isEmpty())
        assertEquals(listOf("ask-location"), phone.log)
    }

    @Test
    fun `the mission screen's Start asks the notification only when missing, and starts whatever the answer`() {
        val log = mutableListOf<String>()
        val refusingDialog: (() -> Unit) -> Unit = { next ->
            log += "ask-notifications"
            next()  // refused: the start goes on, the service runs unseen
        }
        TrackingStartFlow.notificationsThen(missing = true, ask = refusingDialog) { log += "start" }
        assertEquals(listOf("ask-notifications", "start"), log)

        log.clear()
        TrackingStartFlow.notificationsThen(missing = false, ask = refusingDialog) { log += "start" }
        assertEquals(listOf("start"), log)

        // The dialog still open: nothing starts before the person answered.
        log.clear()
        var held: (() -> Unit)? = null
        TrackingStartFlow.notificationsThen(missing = true, ask = { held = it }) { log += "start" }
        assertTrue(log.isEmpty())
        held!!()
        assertEquals(listOf("start"), log)
    }

    /** Precise held, notifications missing, location on: only the notification step varies. */
    private abstract class ScriptedPhoneBase : TrackingStartFlow.Phone {
        val log = mutableListOf<String>()
        override fun disclose(then: (Boolean) -> Unit) = then(true)
        override fun hasPreciseLocation() = true
        override fun hasApproximateLocation() = true
        override fun askLocation(then: () -> Unit) = error("not asked")
        override fun shouldAskNotifications() = true
        override fun ensureLocationOn(then: (Boolean) -> Unit) = then(true)
        override fun start(request: TrackingRequest) {
            log += "start"
        }
        override fun recordRefusal(missionId: String, error: String) {
            log += "refused:$error"
        }
    }
}

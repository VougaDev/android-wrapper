package com.vougadev.shell.tracking

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The follow address's answer ([TrackingConfig.followPath]). */
class TrackingTargetTest {

    private val ended = TrackingRequest(
        missionId = "3f2b7c1e-8a4d-4c1b-9a53-0d6e2f1a9b77",
        path = "/api/tasks/3f2b7c1e-8a4d-4c1b-9a53-0d6e2f1a9b77/position",
        intervalSeconds = 60,
        target = GeoPoint(0.39, 9.45),
        title = "Mission en cours",
        text = "Vers le client",
    )
    private val nextId = "a1b2c3d4-0000-4000-8000-000000000001"

    private fun answer(id: String?, interval: Int = 60) = JSONObject(
        if (id == null) """{"mission": null, "interval_seconds": $interval}"""
        else """{"mission": {"id": "$id", "status": "underway", "url": "/api/tasks/$id"}, "interval_seconds": $interval}""",
    )

    @Test
    fun `the next mission is tracked with no target and the same words`() {
        assertEquals(TrackingRequest(nextId, "/api/tasks/$nextId/position", 60, null, "Mission en cours", "Vers le client"), TrackingTarget.next(answer(nextId), ended, TEST_CONFIG))
    }

    @Test
    fun `the interval is clamped like a start`() {
        assertEquals(15, TrackingTarget.next(answer(nextId, interval = 2), ended, TEST_CONFIG)!!.intervalSeconds)
    }

    @Test
    fun `no mission, the ended mission, or a bad answer means stop`() {
        listOf(
            answer(null),
            JSONObject("""{"interval_seconds": 60}"""),
            answer(ended.missionId),
            answer("not-a-uuid"),
            JSONObject("""{"mission": {"url": "/api/tasks/$nextId"}}"""),
            null,
        ).forEach { assertNull("$it", TrackingTarget.next(it, ended, TEST_CONFIG)) }
    }

    @Test
    fun `at launch the server's mission is started, kept, or tracking stops`() {
        assertEquals(TrackingTarget.AtLaunch.Start(TrackingRequest(nextId, "/api/tasks/$nextId/position", 60, null, null, null)), TrackingTarget.atLaunch(answer(nextId), null, TEST_CONFIG))
        assertEquals(TrackingTarget.AtLaunch.Start(TrackingRequest(nextId, "/api/tasks/$nextId/position", 60, null, null, null)), TrackingTarget.atLaunch(answer(nextId), ended.missionId, TEST_CONFIG))
        // The mission already tracked keeps its stop and its words.
        assertEquals(TrackingTarget.AtLaunch.Keep, TrackingTarget.atLaunch(answer(ended.missionId), ended.missionId, TEST_CONFIG))
        assertEquals(TrackingTarget.AtLaunch.Stop, TrackingTarget.atLaunch(answer(null), ended.missionId, TEST_CONFIG))
        assertEquals(TrackingTarget.AtLaunch.Stop, TrackingTarget.atLaunch(null, null, TEST_CONFIG))
    }
}

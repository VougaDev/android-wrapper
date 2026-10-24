package com.vougadev.shell.tracking

import com.vougadev.shell.tracking.TrackingRequest.Parsed
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingRequestTest {

    private val id = "3f2b7c1e-8a4d-4c1b-9a53-0d6e2f1a9b77"
    private val path = "/api/tasks/$id/position"

    private fun accept(json: String): TrackingRequest {
        val parsed = TrackingRequest.parse(json, TEST_CONFIG)
        assertTrue("refused: $parsed", parsed is Parsed.Accepted)
        return (parsed as Parsed.Accepted).request
    }

    private fun refusal(json: String?): String {
        val parsed = TrackingRequest.parse(json, TEST_CONFIG)
        assertTrue("accepted: $parsed", parsed is Parsed.Refused)
        return (parsed as Parsed.Refused).reason
    }

    @Test
    fun `the documented payload is read whole`() {
        val r = accept(
            """{"missionId": "$id", "url": "$path", "intervalSeconds": 30,
               "target": {"lat": 0.39, "lng": 9.45}, "title": "Mission en cours", "text": "Vers le client"}""",
        )
        assertEquals(id, r.missionId)
        assertEquals(path, r.path)
        assertEquals(30, r.intervalSeconds)
        assertEquals(GeoPoint(0.39, 9.45), r.target)
        assertEquals("Mission en cours", r.title)
        assertEquals("Vers le client", r.text)
    }

    @Test
    fun `the url may be left out, the position goes to the mission's own address`() {
        assertEquals(path, accept("""{"missionId":"$id"}""").path)
    }

    @Test
    fun `the interval is clamped to 15 to 300 seconds`() {
        assertEquals(15, accept("""{"missionId":"$id","intervalSeconds":1}""").intervalSeconds)
        assertEquals(15, accept("""{"missionId":"$id","intervalSeconds":-40}""").intervalSeconds)
        assertEquals(300, accept("""{"missionId":"$id","intervalSeconds":86400}""").intervalSeconds)
        assertEquals(45, accept("""{"missionId":"$id","intervalSeconds":45.9}""").intervalSeconds)
        assertEquals(60, accept("""{"missionId":"$id"}""").intervalSeconds)
        assertEquals(60, accept("""{"missionId":"$id","intervalSeconds":null}""").intervalSeconds)
        assertEquals(60, accept("""{"missionId":"$id","intervalSeconds":"fast"}""").intervalSeconds)
        assertEquals(60, TrackingRequest.clampInterval(Double.NaN))
        assertEquals(300, TrackingRequest.clampInterval(Double.MAX_VALUE))
    }

    @Test
    fun `a missing or unreadable target is dropped, not fatal`() {
        assertNull(accept("""{"missionId":"$id","target":null}""").target)
        assertNull(accept("""{"missionId":"$id","target":{"lat":0.39}}""").target)
        assertNull(accept("""{"missionId":"$id","target":{"lat":"0.39","lng":"9.45"}}""").target)
        assertNull(accept("""{"missionId":"$id","target":{"lat":91,"lng":9.45}}""").target)
        assertNull(accept("""{"missionId":"$id","target":{"lat":0.39,"lng":181}}""").target)
    }

    @Test
    fun `blank or null words fall back to the app's own`() {
        val r = accept("""{"missionId":"$id","title":"  ","text":null}""")
        assertNull(r.title)
        assertNull(r.text)
        assertEquals(80, accept("""{"missionId":"$id","title":"${"x".repeat(500)}"}""").title!!.length)
    }

    @Test
    fun `bad payloads are refused with a reason`() {
        assertEquals("invalid_json", refusal(null))
        assertEquals("invalid_json", refusal(""))
        assertEquals("invalid_json", refusal("not json"))
        assertEquals("invalid_json", refusal("[1,2]"))
        assertEquals("invalid_mission_id", refusal("""{"url":"$path"}"""))
        assertEquals("invalid_mission_id", refusal("""{"missionId":"42","url":"$path"}"""))
        assertEquals("invalid_mission_id", refusal("""{"missionId":null,"url":"$path"}"""))
        assertEquals("invalid_url", refusal("""{"missionId":"$id","url":"https://evil.io$path"}"""))
        assertEquals("invalid_url", refusal("""{"missionId":"$id","url":"/auth/logout/"}"""))
        // Another id's address: the session never goes where the id does not say.
        assertEquals("invalid_url", refusal("""{"missionId":"$id","url":"/api/tasks/a1b2c3d4-0000-4000-8000-000000000001/position"}"""))
    }

    @Test
    fun `a persisted request reads back identical`() {
        val r = accept("""{"missionId":"$id","intervalSeconds":60,"target":{"lat":-1.5,"lng":10.25},"title":"T","text":"X"}""")
        assertEquals(r, accept(r.toJson()))
        val bare = accept("""{"missionId":"$id"}""")
        assertEquals(bare, accept(bare.toJson()))
    }

    @Test
    fun `the page's object is read with the same checks as the text`() {
        val obj = JSONObject()
            .put("missionId", id.uppercase())
            .put("url", path)
            .put("intervalSeconds", 20)
            .put("target", JSONObject().put("lat", 0.39).put("lng", 9.45))
            .put("title", "T")
            .put("text", JSONObject.NULL)
        val parsed = TrackingRequest.parse(obj, TEST_CONFIG)
        assertTrue("refused: $parsed", parsed is Parsed.Accepted)
        val r = (parsed as Parsed.Accepted).request
        assertEquals(id, r.missionId)
        assertEquals(20, r.intervalSeconds)
        assertEquals(GeoPoint(0.39, 9.45), r.target)
        assertNull(r.text)
        assertEquals(r, accept(r.toJson()))
        assertEquals(Parsed.Refused("invalid_request"), TrackingRequest.parse(null as JSONObject?, TEST_CONFIG))
    }

    @Test
    fun `an app's own screen asks for an id with the same checks`() {
        assertEquals(TrackingRequest(id, path, 60, GeoPoint(0.41, 9.46), null, null), TrackingRequest.of(TEST_CONFIG, id.uppercase(), 60, 0.41, 9.46))
        assertEquals(15, TrackingRequest.of(TEST_CONFIG, id, 1, null, null)!!.intervalSeconds)
        assertNull(TrackingRequest.of(TEST_CONFIG, "../../x", 60, null, null))
    }
}

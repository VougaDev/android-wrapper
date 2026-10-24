package com.vougadev.shell.tracking

import org.json.JSONException
import org.json.JSONObject

/**
 * What the app is asked to track: by an app's own native screen ([of]), by the server at launch or at a hand-over
 * ([TrackingTarget]), or by a page through `window.VougaDevNative.startTracking(object)`:
 *
 * ```
 * {"missionId": "<id>", "url": "<the position address for that id>", "intervalSeconds": 60,
 *  "target": {"lat": 0.39, "lng": 9.45} | null, "title": "...", "text": "..."}
 * ```
 *
 * The position always goes to the id's own address, [path] ([TrackingConfig.positionPath]); a page's `url` may be left
 * out, and is refused when it names any other address. [target] is where the person heads for: without it
 * `distance_m` is left out and the server measures it.
 */
data class TrackingRequest(
    val missionId: String,
    val path: String,
    val intervalSeconds: Int,
    val target: GeoPoint?,
    val title: String?,
    val text: String?,
) {

    /** The shape [parse] reads, so a persisted request goes back through the same checks. */
    fun toJson(): String {
        val json = JSONObject()
            .put("missionId", missionId)
            .put("url", path)
            .put("intervalSeconds", intervalSeconds)
        target?.let { json.put("target", JSONObject().put("lat", it.lat).put("lng", it.lng)) }
        title?.let { json.put("title", it) }
        text?.let { json.put("text", it) }
        return json.toString()
    }

    sealed interface Parsed {
        data class Accepted(val request: TrackingRequest) : Parsed
        data class Refused(val reason: String) : Parsed
    }

    companion object {
        const val MIN_INTERVAL_SECONDS = 15
        const val MAX_INTERVAL_SECONDS = 300

        /** Unless the page or the server says otherwise. */
        const val DEFAULT_INTERVAL_SECONDS = 60

        const val INVALID_JSON = "invalid_json"
        const val INVALID_REQUEST = "invalid_request"

        /** Notification words are the page's; a runaway string must not fill the shade. */
        private const val MAX_TITLE_LENGTH = 80
        private const val MAX_TEXT_LENGTH = 200

        /** Missing or unreadable means the default; anything else is held to 15..300 seconds. */
        fun clampInterval(seconds: Double?): Int {
            if (seconds == null || !seconds.isFinite()) return DEFAULT_INTERVAL_SECONDS
            return seconds.toInt().coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        }

        /** A request an app's native screen makes for [id] (lowercased), toward [lat], [lng] if known; null for a bad id. */
        fun of(config: TrackingConfig, id: String, intervalSeconds: Int?, lat: Double?, lng: Double?): TrackingRequest? {
            val missionId = id.lowercase()
            val path = config.positionPathOf(missionId) ?: return null
            return TrackingRequest(missionId, path, clampInterval(intervalSeconds?.toDouble()), point(lat, lng), null, null)
        }

        /** A request persisted by [toJson] (or any JSON text): `invalid_json` when unreadable. */
        fun parse(json: String?, config: TrackingConfig): Parsed {
            if (json.isNullOrBlank()) return Parsed.Refused(INVALID_JSON)
            val obj = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return Parsed.Refused(INVALID_JSON)
            }
            return parse(obj, config)
        }

        /** The object a page passed to `VougaDevNative.startTracking(...)`; null (a string, a number, nothing) is `invalid_request`. */
        fun parse(obj: JSONObject?, config: TrackingConfig): Parsed {
            if (obj == null) return Parsed.Refused(INVALID_REQUEST)

            val missionId = obj.stringOrNull("missionId")?.lowercase()
            val path = config.positionPathOf(missionId) ?: return Parsed.Refused("invalid_mission_id")

            // The page cannot make the service carry the person's session anywhere but this id's own address.
            val url = obj.stringOrNull("url")
            if (url != null && url != path) return Parsed.Refused("invalid_url")

            // A target the page could not describe is dropped, not fatal: the position is still worth sending, and the
            // server measures the distance when the phone does not.
            val target = obj.optJSONObject("target")?.let { point(it.numberOrNull("lat"), it.numberOrNull("lng")) }

            return Parsed.Accepted(
                TrackingRequest(
                    missionId = missionId!!,
                    path = path,
                    intervalSeconds = clampInterval(obj.numberOrNull("intervalSeconds")),
                    target = target,
                    title = obj.stringOrNull("title")?.take(MAX_TITLE_LENGTH),
                    text = obj.stringOrNull("text")?.take(MAX_TEXT_LENGTH),
                ),
            )
        }

        /** Null when either coordinate is missing, not finite or out of range. */
        private fun point(lat: Double?, lng: Double?): GeoPoint? {
            if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return GeoPoint(lat, lng)
        }

        /** `optString` answers "null" for a JSON null; this answers null, and trims. */
        private fun JSONObject.stringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val value = opt(key) as? String ?: return null
            return value.trim().ifEmpty { null }
        }

        private fun JSONObject.numberOrNull(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            return (opt(key) as? Number)?.toDouble()
        }
    }
}

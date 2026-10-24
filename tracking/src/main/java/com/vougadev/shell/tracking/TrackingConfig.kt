package com.vougadev.shell.tracking

/**
 * What the `tracking` extension needs from the app's site, given by the app (nothing here has a default of one app's):
 *
 * - [positionPath]: where each position goes, `{id}` being the tracked id: `POST {lat, lon, accuracy_m, fix_at_ms,
 *   distance_m}` as the WebView's session;
 * - [followPath]: asked when the app opens and when a tracked id ends, answering
 *   `{"mission": {"id": "<id>"} | null, "interval_seconds": 60}`: what to track now, if anything; null: never asked;
 * - [endedCodes]: the refusal codes that mean nothing is left to track for that id (a 404 always does);
 * - [idPattern]: what a tracked id looks like (it becomes part of a URL); a UUID unless the app says otherwise;
 * - [signInNextPath]: the page the "sign in again" notification leads to once signed in.
 */
data class TrackingConfig(
    val positionPath: String,
    val followPath: String? = null,
    val endedCodes: Set<String> = emptySet(),
    val idPattern: Regex = UUID,
    val signInNextPath: String = "/",
) {
    init {
        require(positionPath.startsWith("/") && !positionPath.startsWith("//") && "{id}" in positionPath) { "positionPath: $positionPath" }
        require(followPath == null || (followPath.startsWith("/") && !followPath.startsWith("//"))) { "followPath: $followPath" }
    }

    /** The position address for [id], or null when [id] is not one ([idPattern]). */
    fun positionPathOf(id: String?): String? = id?.takeIf { idPattern.matches(it) }?.let { positionPath.replace("{id}", it) }

    companion object {
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/** A point, `{lat, lng}` in degrees. */
data class GeoPoint(val lat: Double, val lng: Double)

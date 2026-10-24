package com.vougadev.shell.tracking

import org.json.JSONObject

/**
 * `GET` [TrackingConfig.followPath]: which id, if any, this person's position belongs to now.
 *
 * ```
 * {"mission": {"id": "<id>", ...} | null, "interval_seconds": 60}
 * ```
 *
 * Asked when the app opens ([atLaunch]), and ONCE when a post answers that the tracked id is over ([next]), so a person
 * with several in a row hands over from one to the next without opening the app. Never on a timer.
 */
object TrackingTarget {

    /** What the app does with the answer when it opens. */
    sealed interface AtLaunch {
        /** Nothing to track: a service still running (or kept on disk) stops. */
        data object Stop : AtLaunch

        /** The mission already tracked: carry on with it as it is (its stop, its words). */
        data object Keep : AtLaunch

        data class Start(val request: TrackingRequest) : AtLaunch
    }

    /** [active]: the mission the service tracks, or the one kept on disk; null when none. */
    fun atLaunch(body: JSONObject?, active: String?, config: TrackingConfig): AtLaunch {
        val request = requestOf(body, null, null, config) ?: return AtLaunch.Stop
        return if (request.missionId == active) AtLaunch.Keep else AtLaunch.Start(request)
    }

    /**
     * The request to continue with, or null to stop. The next mission has no target (the server measures the distance)
     * and keeps the notification's words. The mission that has just ended is never handed back: that would be a loop
     * of refused posts.
     */
    fun next(body: JSONObject?, current: TrackingRequest, config: TrackingConfig): TrackingRequest? =
        requestOf(body, current.title, current.text, config)?.takeIf { it.missionId != current.missionId }

    private fun requestOf(body: JSONObject?, title: String?, text: String?, config: TrackingConfig): TrackingRequest? {
        val mission = body?.optJSONObject("mission") ?: return null
        // Any `url` in the answer is not where positions go: the request derives its own.
        val candidate = JSONObject()
            .put("missionId", mission.opt("id") ?: JSONObject.NULL)
            .put("intervalSeconds", body.opt("interval_seconds") ?: JSONObject.NULL)
            .put("title", title ?: JSONObject.NULL)
            .put("text", text ?: JSONObject.NULL)
        return (TrackingRequest.parse(candidate, config) as? TrackingRequest.Parsed.Accepted)?.request
    }
}

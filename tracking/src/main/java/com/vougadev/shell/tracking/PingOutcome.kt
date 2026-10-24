package com.vougadev.shell.tracking

import com.vougadev.shell.net.ApiOutcome

/** What one position POST means for the service. */
enum class PingOutcome {
    /** The server stored the position. */
    SENT,

    /** The tracked id is over, or not this person's: ask once which comes next. */
    STOP_MISSION,

    /** The site's session is gone and the account service refused to renew it: the person must sign in again. */
    STOP_SESSION,

    /** Network, server or a one-off refusal: try again at the next tick, never in a burst. */
    RETRY,
}

/**
 * Reads the answer of a position `POST` ([TrackingConfig.positionPath]), already classified by [ApiOutcome] after
 * [com.vougadev.shell.net.AppCaller] tried to renew a lost session:
 *  - `2xx` stored;
 *  - `404` or `410`: not this person's, or gone;
 *  - a refusal whose code is one of the app's [TrackingConfig.endedCodes]: nothing left to track;
 *  - anything else: retried.
 */
object PingClassifier {

    private val GONE = setOf(404, 410)

    fun classify(answer: ApiOutcome, endedCodes: Set<String>): PingOutcome = when (answer) {
        is ApiOutcome.Ok -> PingOutcome.SENT
        ApiOutcome.SessionLost -> PingOutcome.STOP_SESSION
        is ApiOutcome.Refused -> if (answer.status in GONE || answer.code in endedCodes) PingOutcome.STOP_MISSION else PingOutcome.RETRY
        is ApiOutcome.Failed -> if (answer.status in GONE) PingOutcome.STOP_MISSION else PingOutcome.RETRY
        ApiOutcome.Offline -> PingOutcome.RETRY
    }

    /** What `trackingState().lastError` says of a retried answer. */
    fun retryError(answer: ApiOutcome): String = when (answer) {
        is ApiOutcome.Refused -> "http_${answer.status}"
        is ApiOutcome.Failed -> "http_${answer.status}"
        else -> TrackingService.ERROR_NETWORK
    }
}

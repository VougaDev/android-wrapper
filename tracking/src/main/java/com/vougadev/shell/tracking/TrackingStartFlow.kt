package com.vougadev.shell.tracking

/**
 * What `VougaDevNative.startTracking` does before the service starts, in the order the person sees
 * it - the page's Promise resolves only once they answered:
 *
 * 0. The disclosure ([TrackingDisclosure]), once: declined -> `tracking_declined`.
 * 1. Location permission, precise and approximate in the same system dialog. If only
 *    approximate is granted, the precise dialog is shown ONCE more; still no precise ->
 *    `location_permission_denied` (the service needs GPS-grade fixes).
 * 2. Notifications (Android 13+). A refusal does not block: the foreground service runs, the
 *    person just does not see its notification.
 * 3. The device's location switch. Off -> the system "Turn on location" dialog; still off, or no
 *    dialog available -> `location_disabled`.
 *
 * One start at a time: a second call while the first is waiting for the person is queued and
 * runs after it, against the permissions the first one obtained.
 *
 * Pure Kotlin; the activity is the [Phone], the JVM tests are another.
 */
class TrackingStartFlow(private val phone: Phone) {

    /** Every `then` is called on the main thread, once, after the person answered. */
    interface Phone {
        /** [TrackingDisclosure]: true at once when already agreed. */
        fun disclose(then: (Boolean) -> Unit)

        fun hasPreciseLocation(): Boolean
        fun hasApproximateLocation(): Boolean

        /** The system dialog for ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION, in one request. */
        fun askLocation(then: () -> Unit)

        /** True on Android 13+ while POST_NOTIFICATIONS is not granted. */
        fun shouldAskNotifications(): Boolean
        fun askNotifications(then: () -> Unit)

        /** True when location is on, possibly after the system "Turn on location" dialog. */
        fun ensureLocationOn(then: (Boolean) -> Unit)

        fun start(request: TrackingRequest)

        /** trackingState().lastError for a start refused before any service existed. */
        fun recordRefusal(missionId: String, error: String)
    }

    private class Call(val request: TrackingRequest, val answer: (String?) -> Unit)

    private val queue = ArrayDeque<Call>()
    private var running: Call? = null

    fun submit(request: TrackingRequest, answer: (String?) -> Unit) {
        queue.addLast(Call(request, answer))
        runNext()
    }

    /** The activity is going away: nobody is left to answer, nor to show a dialog to. */
    fun abandon() {
        queue.clear()
        running = null
    }

    private fun runNext() {
        if (running != null) return
        val call = queue.removeFirstOrNull() ?: return
        running = call
        phone.disclose { agreed -> if (agreed) locationPermission(call) else refuse(call, ERROR_DECLINED) }
    }

    private fun locationPermission(call: Call) {
        if (phone.hasPreciseLocation()) return notifications(call)
        // Approximate already held: this first dialog IS the precise one, and is not repeated.
        val approximateBefore = phone.hasApproximateLocation()
        phone.askLocation {
            when {
                phone.hasPreciseLocation() -> notifications(call)
                phone.hasApproximateLocation() && !approximateBefore -> phone.askLocation {
                    if (phone.hasPreciseLocation()) notifications(call) else refuse(call, ERROR_LOCATION_PERMISSION)
                }
                else -> refuse(call, ERROR_LOCATION_PERMISSION)
            }
        }
    }

    private fun notifications(call: Call) = notificationsThen(phone.shouldAskNotifications(), phone::askNotifications) { locationSwitch(call) }

    private fun locationSwitch(call: Call) {
        phone.ensureLocationOn { on ->
            if (running !== call) return@ensureLocationOn
            if (!on) return@ensureLocationOn refuse(call, ERROR_LOCATION_DISABLED)
            phone.start(call.request)
            finish(call, null)
        }
    }

    private fun refuse(call: Call, error: String) {
        if (running !== call) return
        phone.recordRefusal(call.request.missionId, error)
        finish(call, error)
    }

    private fun finish(call: Call, error: String?) {
        // Abandoned while the person was answering: the page that asked is gone.
        if (running !== call) return
        running = null
        call.answer(error)
        runNext()
    }

    companion object {
        /**
         * The tracking notification's permission before a start, here and at the mission screen's Start: asked when
         * [missing] (Android 13+, not granted), then [then] - whatever the answer, as Android lets the service run unseen.
         */
        fun notificationsThen(missing: Boolean, ask: (() -> Unit) -> Unit, then: () -> Unit) = if (missing) ask(then) else then()

        const val ERROR_LOCATION_PERMISSION = TrackingService.ERROR_LOCATION_PERMISSION
        const val ERROR_LOCATION_DISABLED = TrackingService.ERROR_LOCATION_DISABLED
        const val ERROR_DECLINED = TrackingService.ERROR_DECLINED
    }
}

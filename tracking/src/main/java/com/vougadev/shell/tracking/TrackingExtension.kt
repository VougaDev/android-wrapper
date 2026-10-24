package com.vougadev.shell.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.vougadev.shell.Attachment
import com.vougadev.shell.Extension
import com.vougadev.shell.ExtensionHost
import com.vougadev.shell.Shell
import com.vougadev.shell.net.ApiOutcome
import com.vougadev.shell.web.NativeBridge
import com.vougadev.shell.web.NativeBridge.Companion.ok
import com.vougadev.shell.web.NativeBridge.Companion.refused
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * THE `tracking` EXTENSION: the person's position shared with the site while a task is under way, from the background
 * too (a `location` foreground service with its notification), after a disclosure of our own ([TrackingDisclosure]).
 *
 * ```
 * VougaDevNative.startTracking(object) -> Promise<{ok: true} | {ok: false, error: "<code>"}>
 * VougaDevNative.stopTracking()        -> Promise<{ok: true}>
 * VougaDevNative.trackingState()       -> Promise<{active, missionId, lastSentAt, lastError}>
 * ```
 *
 * Its manifest brings the location permissions (while in use only, never background), the foreground-service ones and
 * the service. An app's own native screens use [TrackingService] and [TrackingDisclosure] directly.
 */
class TrackingExtension(val config: TrackingConfig) : Extension {

    override val feature = "tracking"

    /** The follow question is asked once per app start, signed in. */
    private val followed = AtomicBoolean(false)

    override fun attach(host: ExtensionHost): Attachment = OnScreen(host)

    override suspend fun forget(context: Context) = TrackingService.stop(context)

    private inner class OnScreen(private val host: ExtensionHost) : Attachment {
        private val activity = host.activity
        private val store = TrackingStore(activity, config)
        private val flow = TrackingStartFlow(Phone())

        override fun bridgeMethods(): Map<String, NativeBridge.Method> = methods(config, flow, { stateJson() }) { TrackingService.stop(activity) }

        private fun stateJson() = store.stateJson(TrackingService.runningMissionId)

        override fun onSignedInPage() {
            val path = config.followPath ?: return
            if (!followed.compareAndSet(false, true)) return
            // What to track now: a person with nothing to track is answered 404, and nothing happens.
            activity.lifecycleScope.launch {
                val answer = Shell.of(activity).session.caller().get(path)
                if (answer is ApiOutcome.Ok) TrackingService.follow(activity, answer.body)
            }
        }

        /** A request on disk with no service: the system reclaimed the process, or would not start it from the background. */
        override fun onResume() = TrackingService.resumeIfPending(activity)

        /** The next person on this phone shares no position for this one's task. */
        override fun beforeSignOut() = TrackingService.stop(activity)

        override fun onDestroy() = flow.abandon()

        private inner class Phone : TrackingStartFlow.Phone {
            private fun has(permission: String) =
                ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

            override fun disclose(then: (Boolean) -> Unit) = TrackingDisclosure.ask(activity, then)

            override fun hasPreciseLocation() = has(Manifest.permission.ACCESS_FINE_LOCATION)

            override fun hasApproximateLocation() = has(Manifest.permission.ACCESS_COARSE_LOCATION)

            /** The WHOLE set goes into the dialog: Android 12+ ignores a request for precise location without approximate. */
            override fun askLocation(then: () -> Unit) =
                host.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), then)

            override fun shouldAskNotifications() =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS)

            override fun askNotifications(then: () -> Unit) = host.askNotifications(then)

            /** The permission is held by now: the shell's location ask only shows the "Turn on location" dialog when it is off. */
            override fun ensureLocationOn(then: (Boolean) -> Unit) = host.location.ask(then)

            override fun start(request: TrackingRequest) = TrackingService.start(activity, request)

            override fun recordRefusal(missionId: String, error: String) = store.recordRefusal(missionId, error)
        }
    }

    companion object {
        const val ERROR_STATE_UNREADABLE = "state_unreadable"

        /** The three members, from what an activity gives them. Pure enough for the JVM: [flow] takes a fake phone there. */
        fun methods(config: TrackingConfig, flow: TrackingStartFlow, state: () -> String, stop: () -> Unit): Map<String, NativeBridge.Method> =
            linkedMapOf(
                "startTracking" to NativeBridge.Method { args, answer ->
                    when (val parsed = TrackingRequest.parse(args, config)) {
                        is TrackingRequest.Parsed.Refused -> answer(refused(parsed.reason))
                        is TrackingRequest.Parsed.Accepted -> flow.submit(parsed.request) { refusal ->
                            answer(if (refusal == null) ok() else refused(refusal))
                        }
                    }
                },
                "stopTracking" to NativeBridge.Method { _, answer ->
                    stop()
                    answer(ok())
                },
                "trackingState" to NativeBridge.Method { _, answer ->
                    answer(
                        try {
                            JSONObject(state())
                        } catch (_: JSONException) {
                            refused(ERROR_STATE_UNREADABLE)
                        },
                    )
                },
            )
    }
}

/** The app's tracking configuration, from anywhere in the process (the service, its notifications). */
object Tracking {
    fun config(context: Context): TrackingConfig =
        checkNotNull(Shell.of(context).extension<TrackingExtension>()) { "The app has no TrackingExtension" }.config
}

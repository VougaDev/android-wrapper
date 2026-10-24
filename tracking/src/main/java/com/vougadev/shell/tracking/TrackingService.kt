package com.vougadev.shell.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.vougadev.shell.Shell
import com.vougadev.shell.net.ApiOutcome
import com.vougadev.shell.net.AppCaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shares the person's position with the site while a task is under way, with the screen off too.
 *
 * Started while the app is in front - which is what makes a `location` foreground service legal with while-in-use
 * permission only: by an app's native screen ([TrackingRequest.of]), when the app opens and the server names something
 * to track ([follow]), or by a page through `window.VougaDevNative.startTracking`. It keeps the best recent platform fix
 * (GPS and network providers, no Play services) and, every `intervalSeconds`, posts it to the id's own address
 * ([TrackingConfig.positionPath]) with the distance to where the person heads for, AS THE WEBVIEW'S SESSION through
 * [AppCaller], which renews a lost session itself.
 *
 * When the server says the id is over (404, or one of [TrackingConfig.endedCodes]), or the app's screen sees it end
 * ([ended]), it asks ONCE what comes next ([TrackingTarget]) and carries on with it, or stops. It stops when the account
 * service refuses to renew the session - with a notification asking the person to sign in again - on [stop], and six
 * hours after the start. A network or server error is retried at the next tick and never sooner.
 *
 * Everything runs on the main thread except the HTTP call itself, so state needs no locks.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: TrackingStore
    private lateinit var notifications: TrackingNotifications
    private lateinit var locationManager: LocationManager
    private lateinit var caller: AppCaller
    private lateinit var config: TrackingConfig

    private var request: TrackingRequest? = null
    private var startedAtMillis: Long = 0L
    private var tickJob: Job? = null
    private var listening = false
    private var latest: Location? = null

    private val listener = LocationListenerCompat { location ->
        val current = latest
        if (FixPolicy.isBetter(
                location.elapsedRealtimeNanos,
                if (location.hasAccuracy()) location.accuracy else null,
                current?.elapsedRealtimeNanos,
                current?.let { if (it.hasAccuracy()) it.accuracy else null },
            )
        ) {
            latest = location
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        config = Tracking.config(this)
        store = TrackingStore(this, config)
        notifications = TrackingNotifications(this)
        notifications.ensureChannels()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        caller = Shell.of(this).session.caller()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ENDED) {
            // A plain start of a service already in the foreground: nothing to promote.
            val current = request ?: run {
                stopSelf()
                return START_NOT_STICKY
            }
            if (current.missionId == intent.getStringExtra(EXTRA_MISSION_ID)) {
                tickJob?.cancel()
                tickJob = scope.launch { if (handOver(current)) startTicking() }
            }
            return START_STICKY
        }
        val incoming = intent?.getStringExtra(EXTRA_REQUEST)?.let { TrackingRequest.parse(it, config) }
        if (incoming is TrackingRequest.Parsed.Accepted) {
            begin(incoming.request)
        } else {
            // A sticky restart (null intent) or the app asking to resume: the disk says what.
            val saved = store.load()
            if (saved == null) {
                promote(null)
                finish()
                return START_NOT_STICKY
            }
            resume(saved)
        }
        return START_STICKY
    }

    /** The same mission is an update (its stop moved, its words changed); another mission replaces this one. */
    private fun begin(next: TrackingRequest) {
        val now = System.currentTimeMillis()
        val saved = store.load()
        val sameMission = saved?.request?.missionId == next.missionId
        val startedAt = if (sameMission) saved.startedAtMillis else now
        if (!sameMission) store.resetOutcome(next.missionId)
        store.save(next, startedAt)
        notifications.cancelSignInAgain()

        val previous = request
        if (previous != null && previous.missionId == next.missionId && tickJob?.isActive == true) {
            // Nothing restarts; the notification is re-posted only when its words changed, so the person sees no flicker.
            request = next
            if (previous.title != next.title || previous.text != next.text) notifications.updateTracking(next)
            if (previous.intervalSeconds != next.intervalSeconds) startTicking()
            return
        }
        start(next, startedAt)
    }

    private fun resume(saved: TrackingStore.Active) {
        if (request?.missionId == saved.request.missionId && tickJob?.isActive == true) return
        start(saved.request, saved.startedAtMillis)
    }

    private fun start(next: TrackingRequest, startedAt: Long) {
        if (!promote(next)) {
            // Android refused a location service now (app in the background after a restart, location switched off,
            // permission revoked). The request stays on disk: opening the app resumes it.
            store.recordError(ERROR_FOREGROUND_REFUSED)
            finish(keepRequest = true)
            return
        }
        request = next
        startedAtMillis = startedAt
        runningMissionId = next.missionId
        if (!listening && !listen()) {
            stopWith(ERROR_LOCATION_PERMISSION)
            return
        }
        startTicking()
    }

    /**
     * Called on every start, even one about to stop: a service started with startForegroundService that never promotes
     * crashes. ServiceCompat drops the type below API 29, where it does not exist.
     */
    @SuppressLint("InlinedApi")
    private fun promote(next: TrackingRequest?): Boolean = try {
        ServiceCompat.startForeground(this, TrackingNotifications.ID_TRACKING, notifications.tracking(next), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Location foreground service refused", e)
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
        Log.w(TAG, "Foreground service start not allowed", e)
        false
    }

    @SuppressLint("MissingPermission") // Checked by hasFineLocation() just above the requests.
    private fun listen(): Boolean {
        if (!hasFineLocation(this)) return false
        // No distance filter: a person waiting at a door does not move 25 m, and with a filter the last fix would age
        // past FixPolicy.MAX_AGE_NANOS and the position stop being sent exactly while the customer is waiting for it.
        val locationRequest = LocationRequestCompat.Builder(MIN_UPDATE_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(0f)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        var any = false
        for (provider in PROVIDERS) {
            if (provider !in locationManager.allProviders) continue
            try {
                locationManager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
                LocationManagerCompat.requestLocationUpdates(locationManager, provider, locationRequest, ContextCompat.getMainExecutor(this), listener)
                any = true
            } catch (e: SecurityException) {
                Log.w(TAG, "No location permission for $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $provider unavailable", e)
            }
        }
        listening = any
        return any
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val current = request ?: break
                if (System.currentTimeMillis() - startedAtMillis > MAX_DURATION_MILLIS) {
                    stopWith(ERROR_TIME_LIMIT)
                    break
                }
                if (!tick(current)) break
                // Read again: a hand-over may have brought another mission and its interval.
                delay((request ?: break).intervalSeconds * 1000L)
            }
        }
    }

    /** One position, one POST. False when tracking has stopped. */
    private suspend fun tick(current: TrackingRequest): Boolean {
        val fix = latest
        if (fix == null || !FixPolicy.isFresh(fix.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())) {
            store.recordError(ERROR_NO_RECENT_FIX)
            return true
        }
        val here = GeoPoint(fix.latitude, fix.longitude)
        val answer = caller.postJson(
            current.path,
            positionBody(
                fix.latitude, fix.longitude, if (fix.hasAccuracy()) fix.accuracy else null, fix.time,
                // No target: distance_m is left out and the server measures it.
                current.target?.let { Math.round(Geo.distanceMeters(here, it)).toDouble() },
            ),
        )
        // The request may have been replaced or stopped while the call was in flight.
        if (request?.missionId != current.missionId) return request != null
        return when (PingClassifier.classify(answer, config.endedCodes)) {
            PingOutcome.SENT -> {
                store.recordSent(System.currentTimeMillis())
                true
            }
            PingOutcome.RETRY -> {
                store.recordError(PingClassifier.retryError(answer))
                true
            }
            PingOutcome.STOP_MISSION -> handOver(current)
            PingOutcome.STOP_SESSION -> {
                endSession()
                false
            }
        }
    }

    /**
     * The tracked mission is over. Ask the server ONCE what this person's position belongs to now: carry on
     * with it (no target, the same notification), or stop. A failed question is a stop too - the app's screen or the
     * next opening of the app asks again.
     */
    private suspend fun handOver(ended: TrackingRequest): Boolean {
        val path = config.followPath ?: run {
            stopWith(ERROR_MISSION_ENDED)
            return false
        }
        val answer = caller.get(path)
        if (request?.missionId != ended.missionId) return request != null
        if (answer == ApiOutcome.SessionLost) {
            endSession()
            return false
        }
        val next = (answer as? ApiOutcome.Ok)?.let { TrackingTarget.next(it.body, ended, config) }
        if (next == null) {
            stopWith(ERROR_MISSION_ENDED)
            return false
        }
        // A hand-over is not a new start by the person: the six-hour cap keeps counting from the first start.
        store.resetOutcome(next.missionId)
        store.save(next, startedAtMillis)
        request = next
        runningMissionId = next.missionId
        return true
    }

    private fun endSession() {
        notifications.showSignInAgain()
        stopWith(ERROR_SESSION_LOST)
    }

    private fun stopWith(error: String) {
        store.recordError(error)
        finish()
    }

    private fun finish(keepRequest: Boolean = false) {
        if (!keepRequest) store.clearRequest()
        tearDown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun tearDown() {
        tickJob?.cancel()
        tickJob = null
        if (listening) {
            try {
                LocationManagerCompat.removeUpdates(locationManager, listener)
            } catch (e: SecurityException) {
                // Permission revoked while tracking: the system has already dropped the updates.
                Log.i(TAG, "Location updates already revoked", e)
            }
            listening = false
        }
        request = null
        latest = null
        runningMissionId = null
    }

    override fun onDestroy() {
        tearDown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShellTracking"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_MISSION_ID = "mission_id"
        private const val ACTION_ENDED = "com.vougadev.shell.tracking.action.ENDED"

        private const val MIN_UPDATE_INTERVAL_MILLIS = 15_000L
        private const val MAX_DURATION_MILLIS = 6L * 60 * 60 * 1000
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        // What trackingState() reports as lastError.
        const val ERROR_LOCATION_PERMISSION = "location_permission_denied"
        const val ERROR_LOCATION_DISABLED = "location_disabled"
        const val ERROR_FOREGROUND_REFUSED = "foreground_start_refused"
        const val ERROR_NO_RECENT_FIX = "no_recent_fix"
        const val ERROR_NETWORK = "network_error"
        const val ERROR_MISSION_ENDED = "mission_ended"
        const val ERROR_SESSION_LOST = "session_lost"
        const val ERROR_TIME_LIMIT = "time_limit"
        const val ERROR_DECLINED = "tracking_declined"

        /** The mission the running service is tracking; null when it is not running. Main thread. */
        @Volatile
        var runningMissionId: String? = null
            private set

        /** Call from the foreground only: a location service cannot start from the background. */
        fun start(context: Context, request: TrackingRequest) {
            if (!TrackingDisclosure.accepted(context)) return  // never before the person agreed (Play's disclosure)
            val intent = Intent(context, TrackingService::class.java).putExtra(EXTRA_REQUEST, request.toJson())
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                // The app went to the background as the call was made: the app's screen asks again when it is seen.
                Log.w(TAG, "Tracking service start not allowed", e)
            }
        }

        /** The app's screen saw [missionId] end or stop: if it is the one tracked, the service asks at once what next. */
        fun ended(context: Context, missionId: String) {
            if (runningMissionId != missionId) return
            try {
                context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_ENDED).putExtra(EXTRA_MISSION_ID, missionId))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Tracking service not reachable", e)
            }
        }

        /** The server's [TrackingConfig.followPath] answer as the app opens: start, keep or stop ([TrackingTarget.atLaunch]). */
        fun follow(context: Context, body: JSONObject) {
            val config = Tracking.config(context)
            val active = runningMissionId ?: TrackingStore(context, config).load()?.request?.missionId
            when (val next = TrackingTarget.atLaunch(body, active, config)) {
                TrackingTarget.AtLaunch.Stop -> if (active != null) stop(context)
                TrackingTarget.AtLaunch.Keep -> resumeIfPending(context)
                is TrackingTarget.AtLaunch.Start -> if (canTrack(context)) start(context, next.request)
            }
        }

        /** The app came back to the foreground with a mission on disk and no service running. */
        fun resumeIfPending(context: Context) {
            if (runningMissionId != null) return
            val store = TrackingStore(context, Tracking.config(context))
            val saved = store.load() ?: return
            if (System.currentTimeMillis() - saved.startedAtMillis > MAX_DURATION_MILLIS) {
                store.recordError(ERROR_TIME_LIMIT)
                store.clearRequest()
                return
            }
            if (!canTrack(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Tracking service resume not allowed", e)
            }
        }

        fun stop(context: Context) {
            TrackingStore(context, Tracking.config(context)).clearRequest()
            context.stopService(Intent(context, TrackingService::class.java))
        }

        /** Precise location held and switched on: no dialog is shown for a start the person did not ask for. */
        private fun canTrack(context: Context): Boolean =
            hasFineLocation(context) && LocationManagerCompat.isLocationEnabled(context.getSystemService(LOCATION_SERVICE) as LocationManager)

        private fun hasFineLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

/** The position body: `{lat, lon, accuracy_m?, fix_at_ms?, distance_m?}`. Pure, tested on the JVM. */
fun positionBody(lat: Double, lon: Double, accuracyM: Float?, atMs: Long?, distanceM: Double?): JSONObject =
    JSONObject().put("lat", lat).put("lon", lon).apply {
        accuracyM?.let { put("accuracy_m", it.toDouble()) }
        atMs?.let { put("fix_at_ms", it) }
        distanceM?.let { put("distance_m", it) }
    }

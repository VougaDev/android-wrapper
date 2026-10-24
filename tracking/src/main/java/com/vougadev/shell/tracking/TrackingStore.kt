package com.vougadev.shell.tracking

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * The mission being tracked, kept on disk so a service the system restarts - or an app the person reopens after the
 * process was reclaimed - carries on with the same mission.
 *
 * Two lifetimes live here. The REQUEST is the intent to track and is cleared the moment tracking stops. The OUTCOME
 * (`lastSentAt`, `lastError`) outlives it, so a page can still read why tracking stopped; it is reset only when another
 * mission starts.
 */
class TrackingStore(context: Context, private val config: TrackingConfig) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Active(val request: TrackingRequest, val startedAtMillis: Long)

    fun load(): Active? {
        val json = prefs.getString(KEY_REQUEST, null) ?: return null
        val parsed = TrackingRequest.parse(json, config)
        if (parsed !is TrackingRequest.Parsed.Accepted) {
            // Written by a build with another shape: not ours to resume.
            clearRequest()
            return null
        }
        return Active(parsed.request, prefs.getLong(KEY_STARTED_AT, 0L))
    }

    fun save(request: TrackingRequest, startedAtMillis: Long) = prefs.edit {
        putString(KEY_REQUEST, request.toJson())
        putLong(KEY_STARTED_AT, startedAtMillis)
        putString(KEY_OUTCOME_MISSION, request.missionId)
    }

    fun clearRequest() = prefs.edit {
        remove(KEY_REQUEST)
        remove(KEY_STARTED_AT)
    }

    /** A new mission: what happened to the previous one is not this one's history. */
    fun resetOutcome(missionId: String) = prefs.edit {
        putString(KEY_OUTCOME_MISSION, missionId)
        remove(KEY_LAST_SENT_AT)
        remove(KEY_LAST_ERROR)
    }

    fun recordSent(atMillis: Long) = prefs.edit {
        putLong(KEY_LAST_SENT_AT, atMillis)
        remove(KEY_LAST_ERROR)
    }

    fun recordError(error: String) = prefs.edit { putString(KEY_LAST_ERROR, error) }

    /** A refusal before any service existed (permission denied): it names the mission it was for. */
    fun recordRefusal(missionId: String, error: String) {
        if (prefs.getString(KEY_OUTCOME_MISSION, null) != missionId) resetOutcome(missionId)
        recordError(error)
    }

    /** `trackingState()` for the bridge. [runningMissionId] is the service's own word. */
    fun stateJson(runningMissionId: String?): String {
        val missionId = runningMissionId
            ?: prefs.getString(KEY_REQUEST, null)?.let { runCatching { JSONObject(it).optString("missionId") }.getOrNull() }
            ?: prefs.getString(KEY_OUTCOME_MISSION, null)
        return JSONObject()
            .put("active", runningMissionId != null)
            .put("missionId", missionId ?: JSONObject.NULL)
            .put("lastSentAt", if (prefs.contains(KEY_LAST_SENT_AT)) prefs.getLong(KEY_LAST_SENT_AT, 0L) else JSONObject.NULL)
            .put("lastError", prefs.getString(KEY_LAST_ERROR, null) ?: JSONObject.NULL)
            .toString()
    }

    companion object {
        private const val PREFS = "tracking"
        private const val KEY_REQUEST = "request"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_OUTCOME_MISSION = "outcome_mission"
        private const val KEY_LAST_SENT_AT = "last_sent_at"
        private const val KEY_LAST_ERROR = "last_error"
    }
}

package com.vougadev.shell

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * THE APP RAISES THE PROMPTS ITSELF (PIVO's rule): the location permission, then the system "Turn on location" dialog.
 * The person answers system dialogs, never instructions; the screen speaks only after a refusal.
 *
 * Created as a property of the activity (its launchers must be registered before it starts). [ask] runs `then(true)`
 * once location is allowed and switched on, `then(false)` when the person said no to either.
 */
class LocationAsk(private val activity: ComponentActivity) {

    private val waiting = mutableListOf<(Boolean) -> Unit>()
    private var asking = false

    private val permissionLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (allowed()) ensureOn() else answer(false)
    }

    private val switchLauncher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        answer(result.resultCode == Activity.RESULT_OK || isOn())
    }

    fun allowed(): Boolean = PERMISSIONS.any { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }

    fun ask(then: (Boolean) -> Unit) {
        waiting += then
        if (asking) return
        asking = true
        // The WHOLE set goes into the dialog: Android 12+ ignores a request for precise location without approximate.
        if (allowed()) ensureOn() else permissionLauncher.launch(PERMISSIONS)
    }

    private fun isOn(): Boolean =
        LocationManagerCompat.isLocationEnabled(activity.getSystemService(LocationManager::class.java))

    /**
     * The "Turn on location" dialog exists only through Google Play services (`SettingsClient`); without them, or when
     * the person says no, the answer is whether location is on anyway.
     */
    private fun ensureOn() {
        if (isOn()) return answer(true)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L).build()
        val settings = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true).build()
        LocationServices.getSettingsClient(activity).checkLocationSettings(settings).addOnCompleteListener { task ->
            if (task.isSuccessful) return@addOnCompleteListener answer(true)
            val resolvable = task.exception as? ResolvableApiException
            if (resolvable != null && !activity.isFinishing && !activity.isDestroyed) {
                try {
                    switchLauncher.launch(IntentSenderRequest.Builder(resolvable.resolution).build())
                    return@addOnCompleteListener
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No activity for the location settings dialog", e)
                } catch (e: IntentSender.SendIntentException) {
                    Log.w(TAG, "Location settings dialog could not be sent", e)
                }
            }
            answer(isOn())
        }
    }

    private fun answer(ok: Boolean) {
        asking = false
        val all = waiting.toList()
        waiting.clear()
        all.forEach { it(ok) }
    }

    private companion object {
        const val TAG = "ShellLocation"
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

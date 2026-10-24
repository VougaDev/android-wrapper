package com.vougadev.shell.tracking

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * GOOGLE PLAY'S PROMINENT DISCLOSURE (User Data policy) and the App Store's purpose explanation (5.1.5): before the first
 * tracking start, the person reads that the position is shared while a task is under way, including when the app is
 * closed or not in use, and agrees. Asked once; until agreed, no tracking starts from anywhere ([TrackingService] checks
 * [accepted]). A refusal never blocks the mission: only the sharing is off, and it is asked again at the next start.
 */
object TrackingDisclosure {

    private const val PREFS = "tracking_disclosure"
    private const val KEY_ACCEPTED = "accepted"

    fun accepted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    /** [then] runs once with the answer: at once when already agreed, else after the dialog. */
    fun ask(activity: Activity, then: (Boolean) -> Unit) {
        if (accepted(activity)) return then(true)
        var answered = false
        fun answer(yes: Boolean) {
            if (answered) return
            answered = true
            if (yes) {
                activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ACCEPTED, true) }
            }
            then(yes)
        }
        MaterialAlertDialogBuilder(activity)  // the app's theme, as its other dialogs
            .setTitle(R.string.tracking_disclosure_title)
            .setMessage(R.string.tracking_disclosure_text)
            .setPositiveButton(R.string.tracking_disclosure_accept) { _, _ -> answer(true) }
            .setNegativeButton(R.string.tracking_disclosure_decline) { _, _ -> answer(false) }
            .setOnCancelListener { answer(false) }
            .show()
    }
}

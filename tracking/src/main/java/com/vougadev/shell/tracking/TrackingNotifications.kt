package com.vougadev.shell.tracking

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vougadev.shell.Shell
import com.vougadev.shell.R as ShellR

/** The two things the tracking service ever says: "sharing your position" and "sign in again". */
class TrackingNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_TRACKING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.tracking_channel_name))
                .setDescription(context.getString(R.string.tracking_channel_description))
                .setShowBadge(false)
                .build(),
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ACCOUNT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.account_channel_name))
                .setDescription(context.getString(R.string.account_channel_description))
                .build(),
        )
    }

    /** The ongoing notification of the foreground service; a page's words when it sent any. */
    fun tracking(request: TrackingRequest?): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRACKING)
            .setSmallIcon(ShellR.drawable.shell_notification_icon)
            .setContentTitle(request?.title ?: context.getString(R.string.tracking_default_title))
            .setContentText(request?.text ?: context.getString(R.string.tracking_default_text))
            .setContentIntent(open(Shell.of(context).launcher()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun updateTracking(request: TrackingRequest) {
        if (!canPost()) return
        try {
            manager.notify(ID_TRACKING, tracking(request))
        } catch (_: SecurityException) {
            // Revoked between the check and the call: the person said no, nothing to post.
        }
    }

    /** Tracking stopped because the account service refused to renew the session; tapping it opens the app's sign-in. */
    fun showSignInAgain() {
        if (!canPost()) return
        val text = context.getString(R.string.tracking_sign_in_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ACCOUNT)
            .setSmallIcon(ShellR.drawable.shell_notification_icon)
            .setContentTitle(context.getString(R.string.tracking_sign_in_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(Shell.of(context).signIn(Tracking.config(context).signInNextPath)))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(ID_SIGN_IN, notification)
        } catch (_: SecurityException) {
            // Revoked between the check and the call: the person said no, nothing to post.
        }
    }

    fun cancelSignInAgain() = manager.cancel(ID_SIGN_IN)

    /** Without POST_NOTIFICATIONS (API 33+) there is nothing to post; the person refused it. */
    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        const val ID_TRACKING = 1
        const val ID_SIGN_IN = 2
        private const val CHANNEL_TRACKING = "delivery_tracking"
        private const val CHANNEL_ACCOUNT = "account"
    }
}

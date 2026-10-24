package com.vougadev.shell.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vougadev.shell.R
import com.vougadev.shell.Shell
import com.vougadev.shell.net.ApiOutcome
import com.vougadev.shell.net.AppCaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PUSH, ON WHEN CONFIGURED: the app built with its Firebase file (git-ignored, never in this repository) AND a
 * [com.vougadev.shell.ShellConfig.pushDevicesPath]. Off, Firebase is never asked anything.
 *
 * The phone's token goes to the site ([Bodies.report]), which hands it to the notification hub: after each sign-in,
 * once per app start while signed in, and whenever Firebase rotates it; it is forgotten there on sign-out.
 */
object Push {

    private const val TAG = "ShellPush"
    private const val KEY_TOKEN = "token"
    private val asked = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enabled(context: Context): Boolean =
        Shell.of(context).config.pushDevicesPath != null && FirebaseApp.getApps(context).isNotEmpty()

    /** Once per app start, when signed in. */
    fun startOncePerProcess(context: Context) {
        if (!enabled(context) || !asked.compareAndSet(false, true)) return
        askToken(context.applicationContext)
    }

    /** Just signed in (perhaps as someone else): the token is reported again for this person. */
    fun afterSignIn(context: Context) {
        if (!enabled(context)) return
        asked.set(true)
        askToken(context.applicationContext)
    }

    private fun askToken(app: Context) {
        // A registration TOKEN: firebase-messaging 25 deprecates it for Installation IDs; both are supported.
        @Suppress("DEPRECATION")
        val task = FirebaseMessaging.getInstance().token
        task.addOnCompleteListener { done ->
            val token = if (done.isSuccessful) done.result else null
            if (token.isNullOrBlank()) Log.w(TAG, "No push token: ${done.exception?.javaClass?.simpleName}") else onToken(app, token)
        }
    }

    /** A token from Firebase (asked, or rotated): kept, and reported while signed in. */
    fun onToken(context: Context, token: String) {
        if (!enabled(context)) return
        prefs(context).edit { putString(KEY_TOKEN, token) }
        val shell = Shell.of(context)
        val path = shell.config.pushDevicesPath ?: return
        if (!shell.session.signedIn) return
        scope.launch {
            val body = Bodies.report(token, Build.MODEL.orEmpty(), shell.config.appSlug, Locale.getDefault().toLanguageTag())
            val answer = shell.session.caller().putJson(path, body)
            if (answer !is ApiOutcome.Ok) Log.w(TAG, "Push token not reported: ${answer.javaClass.simpleName}")
        }
    }

    /** Signing out: the token stops reaching this person. Made with the caller's (still open) session. */
    suspend fun forget(context: Context, caller: AppCaller) {
        val path = Shell.of(context).config.pushDevicesPath ?: return
        val token = prefs(context).getString(KEY_TOKEN, null) ?: return
        caller.postJson("${path.trimEnd('/')}/forget", Bodies.forget(token))
    }

    private fun prefs(context: Context) = context.getSharedPreferences(prefsName(Shell.of(context).config.storeName), Context.MODE_PRIVATE)

    /** The phone's push token, kept across updates under this name. */
    fun prefsName(storeName: String) = storeName + "_push"

    /** Pure: the bodies, tested on the JVM. */
    object Bodies {
        fun report(token: String, model: String, app: String, locale: String): JSONObject = JSONObject()
            .put("kind", "fcm").put("token", token).put("label", model.trim().take(80))
            .put("app", app).put("platform", "android").put("locale", locale)

        fun forget(token: String): JSONObject = JSONObject().put("token", token)
    }
}

/** Receives the hub's pushes and the token's rotations; inert without the app's Firebase file. */
class ShellMessagingService : FirebaseMessagingService() {

    @Deprecated("FCM registration tokens; see Push")
    override fun onNewToken(token: String) = Push.onToken(applicationContext, token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (!Push.enabled(this)) return
        val data = message.data
        val body = data["body"]?.trim().orEmpty()
        if (body.isEmpty()) return
        val title = data["title"]?.trim().orEmpty().ifEmpty { applicationInfo.loadLabel(packageManager).toString() }
        val key = data["id"]?.takeIf { it.isNotBlank() } ?: message.messageId ?: System.nanoTime().toString()
        PushNotifications.show(this, title, body, PushNotifications.open(this, PushRoute.of(data, Shell.of(this).config.appSlug, Shell.of(this).session.origin)), key)
    }
}

object PushNotifications {
    const val CHANNEL = "notifications"
    private const val ID_BASE = 1_000
    private const val ID_SPAN = 1_000_000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.shell_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The intent a tap starts: the screen an extension owns, the page, another app or the browser, or home. */
    fun open(context: Context, route: PushRoute): Intent {
        val shell = Shell.of(context)
        return when (route) {
            is PushRoute.Screen -> shell.extensions.firstNotNullOfOrNull { it.notificationScreen(context, route.screen, route.url) }
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: shell.openPage(PushRoute.pathOf(route.url))
            is PushRoute.Page -> shell.openPage(PushRoute.pathOf(route.url))
            is PushRoute.Outside -> Intent(Intent.ACTION_VIEW, route.url.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            PushRoute.Home -> shell.launcher()
        }
    }

    @SuppressLint("MissingPermission") // checked just below
    fun show(context: Context, title: String, body: String, open: Intent, key: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val id = ID_BASE + Math.floorMod(key.hashCode(), ID_SPAN)
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.shell_notification_icon)
            .setColor(ContextCompat.getColor(context, R.color.shell_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

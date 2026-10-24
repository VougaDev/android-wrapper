package com.vougadev.shell

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.appcompat.app.AppCompatActivity
import com.vougadev.shell.net.AppCaller
import com.vougadev.shell.web.NativeBridge

/**
 * ONE NATIVE FEATURE THE APP TURNS ON: a shared one (`tracking`, `offline-map`) or the app's own. The core is the same
 * for every app; an extension adds what its pages may call, the screens it owns, and what it keeps of the person.
 * An absent extension has no member on `window.VougaDevNative` and no name in `features()`.
 *
 * One instance per process, in the app's [Shell]. What lives with one screen (permission dialogs, a start in progress)
 * is its [Attachment], made by [attach] when the shell's activity is created.
 */
interface Extension {

    /** Its name in `VougaDevNative.features()`: lowercase letters, digits and dashes, e.g. `offline-map`. */
    val feature: String

    /** Called in the shell activity's `onCreate`, before it starts: activity result launchers may be registered here. */
    fun attach(host: ExtensionHost): Attachment? = null

    /** The native screen a same-origin [path] (no query) opens instead of the page; null loads the page. */
    fun nativeScreen(context: Context, path: String): Intent? = null

    /** A notification for this app names [screen], a route key this extension owns; [url] is its page on the origin. */
    fun notificationScreen(context: Context, screen: String, url: String): Intent? = null

    /**
     * A request of the app's own pages, on a WebView thread: an answer from the phone (an offline tile), or null for the
     * network. Only same-origin GET requests reach it.
     */
    fun interceptRequest(context: Context, url: Uri): WebResourceResponse? = null

    /** Sign-out, while the site's session is still open; [caller] never renews it. */
    suspend fun beforeSessionEnds(context: Context, caller: AppCaller) = Unit

    /** What the extension keeps of the person goes: signed out, or the account service refused the session. */
    suspend fun forget(context: Context) = Unit

    /** Its rows in the native Settings screen, if any (an offline map's size). */
    fun settingsEntries(context: Context): List<SettingsEntry> = emptyList()

    /** What it offers with no network (Ship: the courier's missions), listed when a page is not available offline. */
    fun offlineEntries(context: Context): List<OfflineEntry> = emptyList()
}

/** What an extension does inside one shell activity. Main thread. */
interface Attachment {
    /** Its members of `window.VougaDevNative`, in the order its contract lists them. */
    fun bridgeMethods(): Map<String, NativeBridge.Method> = emptyMap()

    /** A page of the site finished loading with the session open. */
    fun onSignedInPage() = Unit

    fun onResume() = Unit

    /** The person is signing out: stop what works for them (a service). */
    fun beforeSignOut() = Unit

    fun onDestroy() = Unit
}

/** What the shell activity lends an extension. */
interface ExtensionHost {
    val activity: AppCompatActivity
    val shell: Shell
    val location: LocationAsk

    /** The system dialog for [permissions] (one request at a time); [then] runs once it is answered. */
    fun requestPermissions(permissions: Array<String>, then: () -> Unit)

    /** The notification permission (Android 13+), with no explanation: for an extension whose start already explained. */
    fun askNotifications(then: () -> Unit)
}

/** Something the app opens with no network: a title, and what a tap opens. */
data class OfflineEntry(val title: String, val open: (Activity) -> Unit)

/** One row of the Settings screen: a title, an optional summary, and what a tap does (null: not tappable). */
data class SettingsEntry(val title: String, val summary: String? = null, val onClick: ((Activity) -> Unit)? = null)

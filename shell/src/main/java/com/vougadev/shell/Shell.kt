package com.vougadev.shell

import android.content.Context
import android.content.Intent
import com.vougadev.shell.auth.AppSession
import com.vougadev.shell.push.Push

/** Implemented by the app's `Application`: the one [Shell] of the process (services and notifications reach it). */
interface ShellApplication {
    val shell: Shell
}

/**
 * THE APP AS THE SHELL SEES IT, one per process: its [config], its [session], and the [extensions] it turns on, in the
 * order their members appear. Made by the app's `Application`.
 */
class Shell(context: Context, val config: ShellConfig, val extensions: List<Extension>) {

    private val app = context.applicationContext

    init {
        val names = extensions.map { it.feature }
        require(names.all { FEATURE.matches(it) } && names.distinct() == names) { "Extension names must be distinct, lowercase: $names" }
        val purposes = config.consent.map { it.code }
        require(purposes.all { PURPOSE.matches(it) } && purposes.distinct() == purposes) { "Consent purposes: $purposes" }
    }

    val session: AppSession = AppSession(
        app,
        config,
        beforeSessionEnds = { caller ->
            Push.forget(app, caller)
            extensions.forEach { it.beforeSessionEnds(app, caller) }
        },
        afterSignOut = { forget() },
    )

    /** `VougaDevNative.features()`. */
    val features: List<String> get() = extensions.map { it.feature }

    inline fun <reified T : Extension> extension(): T? = extensions.filterIsInstance<T>().firstOrNull()

    /** What every extension keeps of the person goes. */
    suspend fun forget() = extensions.forEach { it.forget(app) }

    /** The app's launcher activity (its [ShellActivity]), for a notification with nothing more precise to open. */
    fun launcher(): Intent = app.packageManager.getLaunchIntentForPackage(app.packageName)
        ?.setPackage(null)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ?: Intent(Intent.ACTION_MAIN).setPackage(app.packageName)

    /** The shell activity at [path] on the app's origin (a notification's page). */
    fun openPage(path: String?): Intent = ShellActivity.withPage(launcher(), path)

    /** The shell activity's sign-in, then [nextPath] (a native screen lost the session). */
    fun signIn(nextPath: String): Intent = ShellActivity.withSignIn(launcher(), nextPath)

    companion object {
        private val FEATURE = Regex("^[a-z][a-z0-9-]{0,39}$")
        private val PURPOSE = Regex("^[a-z][a-z0-9_]{0,39}$")

        fun of(context: Context): Shell = (context.applicationContext as ShellApplication).shell
    }
}

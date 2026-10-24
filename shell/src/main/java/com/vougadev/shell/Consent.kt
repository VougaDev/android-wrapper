package com.vougadev.shell

import android.app.Activity
import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * THE SITE'S COOKIE OR DATA CONSENT, ASKED NATIVELY: a dialog asked once, never the website's banner over the WebView.
 * The purposes are the app's ([ShellConfig.consent]); each is off until the person turns it on. The choice is kept on
 * the phone, answered by `VougaDevNative.consent()`, and set as the first-party cookie [COOKIE] on the site's origin
 * (`analytics:1|ads:0`), from which the site hides its own banner and knows what it may do. Settings asks again.
 */
object Consent {

    const val COOKIE = "vougadev_consent"
    private const val KEY = "choices"

    fun needed(context: Context): Boolean {
        val config = Shell.of(context).config
        return config.consent.isNotEmpty() && prefs(context).getString(KEY, null) == null
    }

    /** `{code: bool}` for every purpose; all false before the person answered. */
    fun choices(context: Context): Map<String, Boolean> {
        val saved = parse(prefs(context).getString(KEY, null))
        return Shell.of(context).config.consent.associate { it.code to (saved[it.code] == true) }
    }

    /** The dialog; [then] runs once the person saved. Not dismissable: one tap answers it. */
    fun ask(activity: Activity, then: () -> Unit) {
        val purposes = Shell.of(activity).config.consent
        if (purposes.isEmpty()) return then()
        val current = choices(activity)
        val checked = BooleanArray(purposes.size) { current[purposes[it].code] == true }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.shell_consent_title)
            .setMultiChoiceItems(purposes.map { activity.getString(it.label) }.toTypedArray(), checked) { _, which, on -> checked[which] = on }
            .setPositiveButton(R.string.shell_consent_save) { _, _ ->
                val chosen = purposes.indices.associate { purposes[it].code to checked[it] }
                prefs(activity).edit { putString(KEY, JSONObject(chosen).toString()) }
                setCookie(activity)
                then()
            }
            .setCancelable(false)
            .show()
    }

    /** The cookie, again after anything cleared the jar (a sign-out); nothing when the app declares no purpose. */
    fun setCookie(context: Context) {
        val shell = Shell.of(context)
        if (shell.config.consent.isEmpty()) return
        val manager = CookieManager.getInstance()
        manager.setCookie(shell.session.origin.baseUrl, "$COOKIE=${cookieValue(choices(context))}; Path=/; Max-Age=31536000; Secure; SameSite=Lax")
        manager.flush()
    }

    /** Pure: `analytics:1|ads:0`, in the purposes' order. */
    fun cookieValue(choices: Map<String, Boolean>): String = choices.entries.joinToString("|") { "${it.key}:${if (it.value) 1 else 0}" }

    /** Pure: what [ask] saved; nothing readable is no choice at all. */
    fun parse(saved: String?): Map<String, Boolean> = try {
        val json = JSONObject(saved ?: return emptyMap())
        json.keys().asSequence().associateWith { json.optBoolean(it, false) }
    } catch (_: org.json.JSONException) {
        emptyMap()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Shell.of(context).config.storeName + "_consent", Context.MODE_PRIVATE)
}

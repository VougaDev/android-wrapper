package com.vougadev.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vougadev.shell.push.Push
import com.vougadev.shell.web.LinkPolicy
import com.vougadev.shell.web.PageTheme

/**
 * THE APP'S NATIVE SETTINGS, reached from the account menu (`VougaDevNative.openSettings()`), never from the main menu:
 * notifications (on or off for this phone, and what the person receives, at the hub), the extensions' own rows,
 * privacy and data (the privacy policy, consent choices, deleting the account), signing out, and the app's version.
 * Signing out answers [RESULT_SIGN_OUT]: the shell activity signs out in its own order.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Section titles in the theme's primary colour: its accent may be too light to read on the background.
        theme.applyStyle(R.style.ShellSettingsOverlay, true)
        delegate.localNightMode = PageTheme.night  // the site's light or dark, never the phone's against it
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.shell_settings)
        val root = findViewById<View>(R.id.shell_settings_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.shell_settings_toolbar).apply {
            setNavigationOnClickListener { finish() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.shell_settings_list, Rows()).commit()
        }
    }

    class Rows : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = requireContext()
            val shell = Shell.of(context)
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            // What the person receives (e-mail, SMS, Telegram, push) is chosen at the hub; this phone's own switch only
            // when the app receives push.
            val push = Push.enabled(context)
            val choose = shell.config.notificationsPageUrl
            if (push || choose != null) {
                val group = category(R.string.shell_settings_notifications)
                if (push) {
                    group.addPreference(row(getString(R.string.shell_settings_notifications_phone), notificationsSummary()) { openSystemNotifications() }.apply { key = KEY_PHONE })
                }
                choose?.let { url -> group.addPreference(row(getString(R.string.shell_settings_notifications_choose), null) { openOutside(url) }) }
            }

            shell.extensions.flatMap { it.settingsEntries(context) }.takeIf { it.isNotEmpty() }?.let { entries ->
                val group = category(R.string.shell_settings_features)
                entries.forEach { entry -> group.addPreference(row(entry.title, entry.summary, entry.onClick?.let { click -> { click(requireActivity()) } })) }
            }

            val privacy = category(R.string.shell_settings_privacy)
            privacy.addPreference(row(getString(R.string.shell_settings_privacy_policy), null) { openOutside(shell.config.privacyUrl) })
            if (shell.config.consent.isNotEmpty()) {
                privacy.addPreference(row(getString(R.string.shell_settings_consent), null) { Consent.ask(requireActivity()) {} })
            }
            privacy.addPreference(row(getString(R.string.shell_settings_delete_account), null) { openOutside(shell.config.accountDeletionUrl) })

            val account = category(R.string.shell_settings_account)
            account.addPreference(row(getString(R.string.shell_settings_sign_out), null) { confirmSignOut() })

            val about = category(R.string.shell_settings_about)
            about.addPreference(row(getString(R.string.shell_settings_version), shell.config.versionName, null))
        }

        override fun onResume() {
            super.onResume()
            // Back from the system's notification settings: say what is true now.
            findPreference<Preference>(KEY_PHONE)?.summary = notificationsSummary()
        }

        private fun category(title: Int): PreferenceCategory = PreferenceCategory(requireContext()).also {
            it.title = getString(title)
            it.isIconSpaceReserved = false
            preferenceScreen.addPreference(it)
        }

        private fun row(title: String, summary: String?, onClick: (() -> Unit)?): Preference = Preference(requireContext()).apply {
            this.title = title
            this.summary = summary
            isIconSpaceReserved = false
            isSelectable = onClick != null
            onClick?.let { click -> setOnPreferenceClickListener { click(); true } }
        }

        private fun notificationsSummary(): String = getString(
            if (NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) R.string.shell_settings_on else R.string.shell_settings_off,
        )

        /** Turning them on again, or off, is the system's own screen for this app: one place, the person's rules. */
        private fun openSystemNotifications() {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${requireContext().packageName}".toUri())
            }
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // no settings screen to open: nothing changes
            }
        }

        /**
         * The privacy policy, the account's screens: a company address opens in the app's own WebView (one app, one
         * WebView); anything else in an in-app browser sheet.
         */
        private fun openOutside(url: String) {
            val context = requireContext()
            if (LinkPolicy.isCompany(url.toUri().host, Shell.of(context).config.companyDomains) ||
                Shell.of(context).session.origin.matches(url)
            ) {
                requireActivity().apply {
                    setResult(RESULT_OPEN, Intent().putExtra(EXTRA_URL, url))
                    finish()
                }
                return
            }
            val colors = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(context, R.color.shell_signin_background)).build()
            try {
                CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build().launchUrl(context, url.toUri())
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    // no browser at all: nothing to open
                }
            }
        }

        private fun confirmSignOut() {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.shell_settings_sign_out_text, getString(R.string.app_name)))
                .setPositiveButton(R.string.shell_settings_sign_out) { _, _ ->
                    requireActivity().apply {
                        setResult(RESULT_SIGN_OUT)
                        finish()
                    }
                }
                .setNegativeButton(R.string.shell_cancel, null)
                .show()
        }
    }

    companion object {
        const val RESULT_SIGN_OUT = Activity.RESULT_FIRST_USER + 1
        const val RESULT_OPEN = Activity.RESULT_FIRST_USER + 2
        const val EXTRA_URL = "com.vougadev.shell.extra.URL"
        private const val KEY_PHONE = "notifications_phone"
    }
}

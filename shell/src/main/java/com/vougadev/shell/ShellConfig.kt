package com.vougadev.shell

/**
 * What makes the shell one app's: written by the app from its BuildConfig (README.md). Nothing here has a default that
 * belongs to one app.
 *
 * - [appSlug]: the app's name at the notification hub; a push whose `app` is another opens outside this app;
 * - [baseUrl]: the web app the WebView shows, the only origin granted anything, e.g. `https://app.example.org`;
 * - [authBaseUrl], [authClientId]: the account service and the app's own PUBLIC client (PKCE, no secret), never the web's;
 * - [redirectUri]: `<applicationId>:/oauth2redirect`, the scheme AppAuth's receiver answers;
 * - [userAgentToken]: ` <token>App/<version> (Android)` ends the user agent, which the site reads (`ShipApp` for Ship);
 * - [storeName]: the prefix of what the app keeps on the phone; never changed once shipped, or people are signed out;
 * - [companyDomains]: the company's domains (`vougadev.com`): they and their subdomains load inside the app's WebView,
 *   the account service's pages included, never in the browser;
 * - [googleServerClientId]: the account service's Google client, for `nativeSignIn("google")`; null: not offered;
 * - [trustedOrigins]: other company origins whose frames get `platform()`, `version()`, `features()` and
 *   `openOutside()` (Ship: the payment service's `https://pay.vougadev.com`, embedded in the site too);
 * - [outsideReturnPrefix]: the https address under which an outside step (a provider that refuses WebViews) comes
 *   back, closing its sheet and continuing in the WebView (Ship: `https://pay.vougadev.com/app-return/`);
 * - [outsideHosts]: providers that refuse WebViews (Ship: `paypal.com`): a navigation to them from a company page
 *   becomes such an outside step;
 * - [privacyUrl], [accountDeletionUrl]: what Settings > Privacy and data opens (the stores ask both inside the app);
 * - [notificationsPageUrl]: the hub's page where the person chooses what they receive, opened from Settings;
 * - [pushDevicesPath]: the site's address that hands this phone's push token to the hub (`PUT`, and `POST …/forget` on
 *   sign-out); null: the app receives no push;
 * - [consent]: the purposes the native consent dialog asks about; empty: no dialog, `consent()` answers `{}`;
 * - [offlineCacheBytes]: how much of the site's pages and files the phone keeps for offline;
 * - [scopes]: what the app asks the account service for;
 * - [signInFirst]: true, the app opens on the sign-in until the person is signed in (Jobs, Ship: nothing works
 *   without an account); false, a person without an account uses the site's public pages and signs in from the
 *   site's own "Sign in" (Care: finding care needs no account, Apple 5.1.1(v)); sign-out then returns to the home;
 * - [notificationsAskedBySite]: false, notifications are asked right after sign-in, once; true, the shell never asks on
 *   its own and the site asks through `askNotifications()` when it chooses (Lexi: after a lawyer's guided steps, then
 *   on each new device).
 */
data class ShellConfig(
    val appSlug: String,
    val baseUrl: String,
    val authBaseUrl: String,
    val authClientId: String,
    val redirectUri: String,
    val userAgentToken: String,
    val versionName: String,
    val storeName: String,
    val companyDomains: List<String>,
    val privacyUrl: String,
    val accountDeletionUrl: String,
    val notificationsPageUrl: String? = null,
    val pushDevicesPath: String? = null,
    val consent: List<ConsentPurpose> = emptyList(),
    val googleServerClientId: String? = null,
    val trustedOrigins: List<String> = emptyList(),
    val outsideReturnPrefix: String? = null,
    val outsideHosts: List<String> = emptyList(),
    val offlineCacheBytes: Long = com.vougadev.shell.offline.PageCache.DEFAULT_CAP_BYTES,
    val debug: Boolean = false,
    val scopes: String = DEFAULT_SCOPES,
    val signInFirst: Boolean = true,
    val notificationsAskedBySite: Boolean = false,
) {
    companion object {
        /** `permissions` carries the app's grants; no `resource`: the token is traded at the site for a session. */
        const val DEFAULT_SCOPES = "openid profile email phone permissions"
    }
}

/** One purpose of the consent dialog: its [code] as the site reads it, and the label the person reads. */
data class ConsentPurpose(val code: String, @param:androidx.annotation.StringRes val label: Int)

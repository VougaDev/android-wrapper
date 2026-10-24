package com.vougadev.shell

/**
 * What makes the shell one app's: written by the app from its BuildConfig (README.md).
 *
 * - [baseUrl]: the web app the WebView shows, the only origin granted anything, e.g. `https://app.example.org`;
 * - [authBaseUrl], [authClientId]: the account service and the app's own PUBLIC client (PKCE, no secret), never the web's;
 * - [redirectUri]: `<applicationId>:/oauth2redirect`, the scheme AppAuth's receiver answers;
 * - [userAgentToken]: ` <token>App/<version> (Android)` ends the user agent, which the site reads (`ShipApp` for Ship);
 * - [storeName]: the prefix of what the app keeps on the phone; never changed once shipped, or people are signed out;
 * - [scopes]: what the app asks the account service for.
 */
data class ShellConfig(
    val baseUrl: String,
    val authBaseUrl: String,
    val authClientId: String,
    val redirectUri: String,
    val userAgentToken: String,
    val versionName: String,
    val storeName: String,
    val debug: Boolean = false,
    val scopes: String = DEFAULT_SCOPES,
) {
    companion object {
        /** `permissions` carries the app's grants; no `resource`: the token is traded at the site for a session. */
        const val DEFAULT_SCOPES = "openid profile email phone permissions"
    }
}

package com.vougadev.shell.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import androidx.core.content.edit
import com.vougadev.shell.ShellConfig
import com.vougadev.shell.net.AppCaller
import com.vougadev.shell.net.AppHttp
import com.vougadev.shell.web.AppOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The app's sign-in, one per process (the app keeps the instance): the sealed refresh token, the account service's endpoints, the
 * trade for the site's session and the one [SessionKeeper] every caller shares (the WebView, the native screens, push).
 * Made by the app's [com.vougadev.shell.Shell], which passes what sign-out also clears: [beforeSessionEnds] is called
 * while the site's session is still open (its caller never renews it), [afterSignOut] once tokens and cookies are gone.
 */
class AppSession(
    context: Context,
    val config: ShellConfig,
    private val beforeSessionEnds: suspend (AppCaller) -> Unit = {},
    private val afterSignOut: suspend () -> Unit = {},
) {

    private val app: Context = context.applicationContext
    private val prefs = app.getSharedPreferences(config.storeName + "_auth", Context.MODE_PRIVATE)

    val origin = AppOrigin(config.baseUrl)
    val client = AuthClient(config.authBaseUrl, config.authClientId, config.redirectUri)
    val store = TokenStore(PrefsValues(prefs), KeystoreSealer(config.storeName + "_tokens"))
    val cookies: CookieJarPort = WebCookies(origin)

    /** The WebView's own user agent plus ` <Token>App/<version> (Android)`, as every native call sends it. */
    val userAgent: String by lazy {
        val base = runCatching { WebSettings.getDefaultUserAgent(app) }.getOrNull() ?: System.getProperty("http.agent").orEmpty()
        base + AppHttp.userAgentSuffix(config.userAgentToken, config.versionName)
    }

    val endpoints = AuthEndpoints(client, AppHttp.client, ::discovery)
    private val exchange = SessionExchange(origin, { userAgent }, ::acceptLanguage, AppHttp.client)
    val keeper = SessionKeeper(store, endpoints, exchange::open, cookies)

    fun acceptLanguage(): String = AppHttp.acceptLanguage(AppHttp.phoneLanguages())

    /** A caller of the site's API as the WebView's session, renewing it when lost. */
    fun caller(): AppCaller = AppCaller(origin, cookies::jar, { userAgent }, ::acceptLanguage, keeper::renew)

    /** True when the person signed in on this phone (a refresh token is kept); the site's session may still need opening. */
    val signedIn: Boolean get() = store.signedIn

    /**
     * the account service's discovery document, kept on the phone for a day; the account service out of reach, the phone's copy serves. Null only
     * before a first successful read.
     */
    suspend fun discovery(): Discovery? {
        val kept = Discovery.parse(prefs.getString(KEY_DISCOVERY, null))
        if (kept != null && System.currentTimeMillis() - prefs.getLong(KEY_DISCOVERY_AT, 0) < DISCOVERY_KEPT_MS) return kept
        val fresh = withContext(Dispatchers.IO) {
            try {
                AppHttp.client.newCall(Request.Builder().url(Discovery.path(client.issuer)).build()).execute().use {
                    if (it.isSuccessful) it.body.string() else null
                }
            } catch (_: IOException) {
                null
            }
        }
        val parsed = Discovery.parse(fresh) ?: return kept
        prefs.edit { putString(KEY_DISCOVERY, fresh).putLong(KEY_DISCOVERY_AT, System.currentTimeMillis()) }
        return parsed
    }

    /**
     * SIGNING OUT OF THIS APP ON THIS PHONE, never out of VougaDev (no end_session: the person's other applications stay
     * signed in). In this order, while the site's session still exists: [beforeSessionEnds] (the push token
     * forgotten), the site's session ended; then the refresh token is revoked at the account service, and the tokens, the cookies
     * and what the app keeps ([afterSignOut]) go.
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        val jar = cookies.jar()
        if (!jar.isNullOrBlank()) {
            beforeSessionEnds(AppCaller(origin, { jar }, { userAgent }, ::acceptLanguage, { Renewal.SIGN_IN }))
            endSiteSession(jar)
        }
        store.refreshToken()?.let { endpoints.revoke(it) }
        store.clear()
        cookies.clear()
        afterSignOut()
    }

    /** The web's own sign-out (`/auth/logout/`, POST with CSRF); its answer changes nothing on the phone. */
    private suspend fun endSiteSession(jar: String) = withContext(Dispatchers.IO) {
        val headers = AppHttp.headers(origin, jar, userAgent, acceptLanguage(), unsafe = true) ?: return@withContext
        val request = Request.Builder().url(origin.baseUrl + SIGN_OUT_PATH).headers(headers).post(ByteArray(0).toRequestBody(null)).build()
        try {
            AppHttp.client.newCall(request).execute().close()
        } catch (_: IOException) {
            // offline: the cookies go anyway, and the server's session ends with its token
        }
    }

    companion object {
        const val SIGN_OUT_PATH = "/auth/logout/"
        private const val KEY_DISCOVERY = "discovery"
        private const val KEY_DISCOVERY_AT = "discovery_at"
        private const val DISCOVERY_KEPT_MS = 24L * 3600 * 1000
    }
}

/** The WebView's cookie jar for the app's origin. */
private class WebCookies(private val origin: AppOrigin) : CookieJarPort {
    override fun jar(): String? = CookieManager.getInstance().getCookie(origin.baseUrl)

    override fun put(setCookies: List<String>) {
        val manager = CookieManager.getInstance()
        setCookies.forEach { manager.setCookie(origin.baseUrl, it) }
        manager.flush()
    }

    override fun clear() {
        val manager = CookieManager.getInstance()
        manager.removeAllCookies(null)
        manager.flush()
    }
}

package com.vougadev.shell.net

import com.vougadev.shell.web.AppOrigin
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Calls the app makes to its site outside the WebView, AS THE WEBVIEW'S SESSION (PIVO's `net/PivoHttp.kt` rules). One
 * client and one header set, so every such call looks the same to the site:
 *
 * - `Cookie`: the WebView's cookie jar for the site's origin;
 * - `Referer`: the site's own origin - without it the site's CSRF check refuses an HTTPS POST (403 HTML);
 * - `Accept-Language`: the phone's languages - without it the server answers in its default, not the screen's;
 * - `X-CSRFToken`: the `csrftoken` cookie, on every unsafe method;
 * - `Accept: application/json` and the WebView's user agent plus ` <Token>App/<version> (Android)`.
 *
 * Pure apart from [client]: [headers] is tested on the JVM.
 */
object AppHttp {

    /**
     * No redirects of any kind: a 302 to the sign-in page IS the answer ("session lost"), and following it would turn
     * it into a 200 HTML page that looks like success.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // A handover uploads one photo per package: up to twenty on a slow road.
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** ` ShipApp/1.0.12 (Android)` for [token] `Ship`: the site tells the app, and its platform, from it. */
    fun userAgentSuffix(token: String, version: String): String = " ${token}App/$version (Android)"

    /**
     * The headers of one call. [unsafe] is a POST (or any method that changes something): it carries `X-CSRFToken`,
     * and null is returned when the cookie jar holds no `csrftoken` - the call cannot pass the site's CSRF check, so it is not
     * made (the session is gone).
     */
    fun headers(origin: AppOrigin, cookies: String, userAgent: String, acceptLanguage: String, unsafe: Boolean): Headers? {
        val builder = Headers.Builder()
            .add("Cookie", cookies)
            .add("Referer", origin.baseUrl + "/")
            .add("Accept", "application/json")
            .add("Accept-Language", acceptLanguage)
            .add("User-Agent", userAgent)
        if (unsafe) {
            val csrf = SessionCookies.valueOf(cookies, SessionCookies.CSRF_COOKIE) ?: return null
            builder.add("X-CSRFToken", csrf)
        }
        return builder.build()
    }

    /** `fr-FR,fr;q=0.9,en;q=0.8` from the phone's locale list, as language tags, first preferred. */
    fun acceptLanguage(tags: List<String>): String {
        val list = tags.filter { it.isNotBlank() }.ifEmpty { listOf("en") }
        return list.mapIndexed { i, tag ->
            if (i == 0) tag else "$tag;q=${String.format(Locale.ROOT, "%.1f", maxOf(0.1, 1.0 - i * 0.1))}"
        }.joinToString(",")
    }

    /** The phone's own locale list (Android only). */
    fun phoneLanguages(): List<String> {
        val locales = android.os.LocaleList.getDefault()
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }
}

/** Reading the WebView's `Cookie` header text: `a=1; csrftoken=xyz; sessionid=...`. */
object SessionCookies {
    const val CSRF_COOKIE = "csrftoken"
    const val SESSION_COOKIE = "sessionid"

    fun valueOf(cookies: String?, name: String): String? = cookies.orEmpty().split(';')
        .map { it.trim() }
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotEmpty() }

    fun isSignedIn(cookies: String?): Boolean = valueOf(cookies, SESSION_COOKIE) != null
}

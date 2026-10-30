package com.vougadev.shell.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vougadev.shell.R
import com.vougadev.shell.web.PageTheme
import com.vougadev.shell.web.SignInRoute
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.AuthorizationServiceDiscovery
import net.openid.appauth.ResponseTypeValues
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * THE APP'S SIGN-IN with the account service: AppAuth's authorization code flow with PKCE S256 for the app's public
 * client, then its code exchange (which checks the ID token); the tokens go to [SessionKeeper]. It runs INSIDE THE APP,
 * in its WebView ([request], [finish] with the account service's answer); a Custom Tab ([intent]) only when another
 * company's login refuses a WebView, until the account service hands those logins to the app.
 *
 * One per activity: AppAuth's service binds the Custom Tabs service and is [dispose]d with the activity.
 */
class BrowserSignIn(private val context: Context, private val session: AppSession) {

    private val service = AuthorizationService(context)

    /** What the sign-in came to. */
    sealed interface Outcome {
        data class Done(val renewal: Renewal) : Outcome

        /** The person closed the browser tab: nothing happened. */
        data object Cancelled : Outcome

        data object Failed : Outcome
    }

    /**
     * THE SIGN-IN INSIDE THE APP: the account service's authorization request (code + PKCE S256, the app's public client),
     * whose address the WebView loads. Null when the account service's discovery cannot be read (offline).
     */
    /** The sign-in request, loaded in the WebView; [asked] is a site's sign-up (`prompt=create`) or re-authentication. */
    suspend fun request(asked: SignInRoute.Asked? = null): AuthorizationRequest? {
        val discovery = session.discovery() ?: return null
        val config = try {
            AuthorizationServiceConfiguration(AuthorizationServiceDiscovery(discovery.json))
        } catch (_: AuthorizationServiceDiscovery.MissingArgumentException) {
            return null
        }
        return AuthorizationRequest.Builder(config, session.client.clientId, ResponseTypeValues.CODE, session.client.redirectUri.toUri())
            .setScope(session.config.scopes)
            // the account service's screens in the phone's language, as the web's own sign-in asks
            .setUiLocales(Locale.getDefault().language)
            .setPrompt(asked?.prompt)
            // and in the site's light or dark, as the web's own sign-in asks
            .setAdditionalParameters(mapOf("theme" to PageTheme.signInTheme()) + (asked?.maxAge?.let { mapOf("max_age" to it.toString()) } ?: emptyMap()))
            .build()
    }

    /**
     * The account service answered the sign-in inside the app: its redirect to the app's address, caught in the WebView,
     * as [answer] (`<redirect>?code=…&state=…`). The state must be [request]'s; the code
     * is exchanged (PKCE) and the session opened.
     */
    suspend fun finish(request: AuthorizationRequest, answer: Uri): Outcome {
        if (answer.getQueryParameter("error") != null) return Outcome.Cancelled
        if (answer.getQueryParameter("state") != request.state || answer.getQueryParameter("code").isNullOrBlank()) return Outcome.Failed
        val response = try {
            AuthorizationResponse.Builder(request).fromUri(answer).build()
        } catch (_: IllegalArgumentException) {
            return Outcome.Failed
        }
        return exchange(response)
    }

    /** The FALLBACK while another company's login refuses the WebView: the sign-in in a Custom Tab, in the app's colours. */
    suspend fun intent(): Intent? {
        val request = request() ?: return null
        val tab = service.createCustomTabsIntentBuilder(request.toUri())
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(ContextCompat.getColor(context, R.color.shell_signin_background)).build())
            .setShowTitle(true)
            .build()
        return service.getAuthorizationRequestIntent(request, tab)
    }

    /** The browser came back ([data] is the activity result's intent): the code is exchanged, the session opened. */
    suspend fun finish(data: Intent?): Outcome {
        val response = data?.let(AuthorizationResponse::fromIntent)
        val error = data?.let(AuthorizationException::fromIntent)
        if (response == null) {
            val cancelled = error == null || error.code in CANCELLED
            return if (cancelled) Outcome.Cancelled else Outcome.Failed
        }
        return exchange(response)
    }

    private suspend fun exchange(response: AuthorizationResponse): Outcome {
        val tokens = suspendCoroutine { done ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { answer, _ -> done.resume(answer) }
        }
        val access = tokens?.accessToken ?: return Outcome.Failed
        val renewal = session.keeper.signedIn(access, tokens.refreshToken)
        return Outcome.Done(renewal)
    }

    fun dispose() = service.dispose()

    private companion object {
        val CANCELLED = setOf(
            AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code,
            AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW.code,
        )
    }
}

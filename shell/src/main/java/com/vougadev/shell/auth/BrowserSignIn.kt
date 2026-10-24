package com.vougadev.shell.auth

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.vougadev.shell.R
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
 * SIGN-IN IN THE SYSTEM BROWSER (a Custom Tab), never in the WebView: AppAuth's authorization code flow with PKCE S256
 * for the app's public client, then its code exchange (which checks the ID token). The tokens go to [SessionKeeper].
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

    /** The intent that opens the account service's sign-in in a Custom Tab; null when the account service's discovery cannot be read (offline). */
    suspend fun intent(): Intent? {
        val discovery = session.discovery() ?: return null
        val config = try {
            AuthorizationServiceConfiguration(AuthorizationServiceDiscovery(discovery.json))
        } catch (_: AuthorizationServiceDiscovery.MissingArgumentException) {
            return null
        }
        val request = AuthorizationRequest.Builder(config, session.client.clientId, ResponseTypeValues.CODE, session.client.redirectUri.toUri())
            .setScope(session.config.scopes)
            // the account service's pages in the phone's language, as the web's own sign-in asks
            .setUiLocales(Locale.getDefault().language)
            .build()
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

package com.vougadev.shell.auth

import com.vougadev.shell.ShellConfig
import com.vougadev.shell.net.SessionCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a renewal came to. */
enum class Renewal {
    /** The site's session is open again (cookies in the jar): retry what failed. */
    RENEWED,

    /** the account service refused the refresh, or there was no token: the native sign-in screen. */
    SIGN_IN,

    /** the account service or The site out of reach: nothing was forgotten, try again later. */
    OFFLINE,
}

/** The WebView's cookie jar for the site's origin, as the keeper needs it. */
interface CookieJarPort {
    /** The `Cookie` header text the WebView sends the site (`a=1; sessionid=...`), or null. */
    fun jar(): String?

    fun put(setCookies: List<String>)

    fun clear()
}

/**
 * A LOST SESSION IS RENEWED SILENTLY: the refresh token (rotated, stored at once), then a new site session for the
 * fresh access token. Only a refused refresh asks the person to sign in again.
 *
 * One renewal at a time for the whole app (the WebView and every native call): the account service spends a refresh token on first
 * use, so two concurrent refreshes would sign the person out. A caller that waited while another renewed sees the
 * session cookie changed and does not refresh again. The keystore and the disk are used off the main thread.
 */
class SessionKeeper(
    private val store: TokenStore,
    private val refresher: Refresher,
    private val exchange: suspend (String) -> Exchange,
    private val cookies: CookieJarPort,
    private val scopes: String = ShellConfig.DEFAULT_SCOPES,
) {
    private val lock = Mutex()

    /** [lostJar] is the cookie text the failed call was made with (null: none was). */
    suspend fun renew(lostJar: String?): Renewal = withContext(Dispatchers.IO) { lock.withLock { renewLocked(lostJar) } }

    private suspend fun renewLocked(lostJar: String?): Renewal {
        val lost = SessionCookies.valueOf(lostJar, SessionCookies.SESSION_COOKIE)
        val now = SessionCookies.valueOf(cookies.jar(), SessionCookies.SESSION_COOKIE)
        if (now != null && now != lost) return Renewal.RENEWED
        val refreshToken = store.refreshToken() ?: return Renewal.SIGN_IN
        // A REFRESH KEEPS THE OLD GRANT'S SCOPES: an update asking for more signs in again (silent while AUTH's session lives)
        if (!covers(store.grantedScopes(), scopes)) return Renewal.SIGN_IN
        // NOT CANCELLED HALF-WAY: once the account service rotated the token, the new one must reach the store or the person is out.
        val answer = withContext(NonCancellable) {
            refresher.refresh(refreshToken).also { if (it is TokenAnswer.Tokens) store.save(it.refreshToken) }
        }
        return when (answer) {
            TokenAnswer.Refused -> {
                store.clear()
                Renewal.SIGN_IN
            }
            TokenAnswer.Unreachable -> Renewal.OFFLINE
            is TokenAnswer.Tokens -> open(answer.accessToken)
        }
    }

    /** The browser sign-in's tokens (AppAuth's code exchange): kept, then traded for the site's session. */
    suspend fun signedIn(accessToken: String, refreshToken: String?): Renewal = withContext(Dispatchers.IO) {
        lock.withLock {
            store.save(refreshToken)
            store.saveGrantedScopes(scopes)
            open(accessToken)
        }
    }

    private suspend fun open(accessToken: String): Renewal = when (val answer = exchange(accessToken)) {
        is Exchange.Opened -> {
            cookies.put(answer.setCookies)
            Renewal.RENEWED
        }
        // The site out of reach or failing (an edge's 502): later, nothing forgotten
        Exchange.Offline -> Renewal.OFFLINE
        is Exchange.Failed -> if (answer.status >= 500) Renewal.OFFLINE else Renewal.SIGN_IN
        // The site refused a token the account service just gave: the browser sign-in starts over (it replaces the tokens)
        Exchange.Refused -> Renewal.SIGN_IN
    }
}

/** True when every scope [asked] is among those [granted] (space-separated, OAuth's form). */
fun covers(granted: String, asked: String): Boolean = granted.split(' ').toSet().containsAll(asked.split(' ').filter { it.isNotBlank() })

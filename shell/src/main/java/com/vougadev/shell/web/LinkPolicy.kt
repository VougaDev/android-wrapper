package com.vougadev.shell.web

import java.util.Locale

/**
 * Where a top-level navigation goes. The WebView shows the site and nothing else: SIGN-IN NEVER HAPPENS IN IT (the account service's rule:
 * Google and passkeys refuse a WebView), so the site's `/auth/login/` and the account service's sign-in pages run the app's own sign-in
 * instead ([SignInRoute]); the account service's other pages (the account) open in the browser, where the VougaDev session lives. A
 * person who taps an address, a phone number or a map link gets the app that handles it.
 */
object LinkPolicy {

    enum class Decision {
        /** A page of the site: load it in the WebView. */
        LOAD_INSIDE,

        /** A path the app shows natively (its `ShellActivity.nativeScreen`): that screen opens instead. */
        OPEN_NATIVE,

        /** The site's or the account service's sign-in: the session is renewed, or the native sign-in screen shown. */
        SIGN_IN,

        /** Another web host (the account service's account pages too), `tel:`, `geo:`, `mailto:`, a maps or chat app: ACTION_VIEW. */
        OPEN_OUTSIDE,

        /** An `intent:` URL: parsed, stripped of any explicit component, then started. */
        OPEN_INTENT,

        /** Schemes that would read local data or run script: never followed. */
        BLOCK,
    }

    private val BLOCKED = setOf("file", "content", "javascript", "data", "blob", "about", "chrome", "android-app")

    /** [path] is the URL's path (no query); it decides between the site's pages and sign-in. */
    fun decide(scheme: String?, isSite: Boolean, isAuth: Boolean, isNativeRoute: Boolean, path: String? = null): Decision {
        val s = scheme?.lowercase(Locale.ROOT) ?: return Decision.BLOCK
        return when {
            isSite && SignInRoute.isSiteSignIn(path) -> Decision.SIGN_IN
            isAuth && SignInRoute.isAuthSignIn(path) -> Decision.SIGN_IN
            isSite && isNativeRoute -> Decision.OPEN_NATIVE
            isSite -> Decision.LOAD_INSIDE
            s == "intent" -> Decision.OPEN_INTENT
            s in BLOCKED -> Decision.BLOCK
            else -> Decision.OPEN_OUTSIDE
        }
    }
}

/**
 * The site's and the account service's sign-in and sign-out addresses, as the shell meets them in the WebView. Pure, tested on the JVM.
 *
 * - the site's `/auth/login/?next=<path>`, where a lost session is sent;
 * - the account service's authorization endpoint (`/identity/o/authorize`) and its sign-in pages (`/accounts/login/`, `/accounts/signup/`);
 * - the site's sign-out: the web's form POSTs `/auth/logout/`.
 */
object SignInRoute {
    const val SITE_SIGN_IN = "/auth/login/"
    const val SITE_SIGN_OUT = "/auth/logout/"
    private val AUTH_SIGN_IN = listOf("/identity/o/authorize", "/accounts/login", "/accounts/signup")

    fun isSiteSignIn(path: String?): Boolean = path == SITE_SIGN_IN || path == SITE_SIGN_IN.trimEnd('/')

    fun isAuthSignIn(path: String?): Boolean = path != null && AUTH_SIGN_IN.any { path.startsWith(it) }

    /** The web's sign-out form: a top-level POST to the site's `/auth/logout/`. */
    fun isSignOut(method: String?, isSite: Boolean, path: String?): Boolean =
        isSite && method.equals("POST", ignoreCase = true) && (path == SITE_SIGN_OUT || path == SITE_SIGN_OUT.trimEnd('/'))

    /**
     * Where to go once signed in: the sign-in's `next` when it is a page of the site's own (never the sign-in or sign-out
     * again), else the site's home. [next] is the decoded query value.
     */
    fun after(origin: AppOrigin, next: String?): String {
        val url = origin.pageUrl(next?.trim())
        val path = next?.substringBefore('?')
        if (url == null || path == null || path.startsWith("/auth/")) return origin.baseUrl + "/"
        return url
    }
}

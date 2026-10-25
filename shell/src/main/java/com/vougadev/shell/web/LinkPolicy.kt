package com.vougadev.shell.web

import java.util.Locale

/**
 * Where a top-level navigation goes. ONE APP, ONE WEBVIEW: the app's site, the company's other domains and subdomains,
 * and the account service's pages (its sign-in included) all load in the same WebView, with one navigation and the
 * native back - never the browser, never a separate screen. The site's own sign-in address renews the session instead
 * ([SignInRoute]). Payment stays inside too: the company's checkout is a company domain, and a step of an outside company
 * met on the way (a bank's 3-D Secure, a mobile-money operator's confirmation) stays in the WebView while it works
 * there. A provider known to refuse WebViews ([outside hosts][com.vougadev.shell.ShellConfig.outsideHosts]) becomes an
 * outside step in an in-app sheet that returns by itself; another company's login finishes in the system's sign-in
 * surface. Another company's site the person chose to open goes to an in-app browser sheet; a phone number, an
 * e-mail, a map or a chat opens the phone's own app. Pure, tested on the JVM.
 */
object LinkPolicy {

    enum class Decision {
        /** The app's site or a company domain: load it in the WebView. */
        LOAD_INSIDE,

        /** A path the app shows natively (an extension's `nativeScreen`): that screen opens instead. */
        OPEN_NATIVE,

        /** The site's own sign-in address: the session is renewed, or the sign-in screen shown. */
        SIGN_IN,

        /** Another company's login (Google, Apple), which refuses a WebView: the sign-in in the system's surface. */
        SIGN_IN_ELSEWHERE,

        /** Another company's site the person chose to open: an in-app browser sheet (Custom Tab) over the app. */
        OPEN_THIRD_PARTY,

        /** A provider that refuses WebViews, met on the way (PayPal): an outside step that returns to the WebView. */
        OUTSIDE_STEP,

        /** `tel:`, `mailto:`, `geo:`, a maps or chat app's scheme: the phone's own app. */
        OPEN_APP,

        /** A chat app's own web address (Telegram's bot links): that app, else an in-app browser sheet - never inside. */
        OPEN_IN_ITS_APP,

        /** An `intent:` URL: parsed, stripped of any explicit component, then started. */
        OPEN_INTENT,

        /** Schemes that would read local data or run script: never followed. */
        BLOCK,
    }

    private val BLOCKED = setOf("file", "content", "javascript", "data", "blob", "about", "chrome", "android-app")

    /**
     * Web addresses that belong to a phone app, tapped or reached by a redirect (the account service's "Connect
     * Telegram" answers with `t.me/<bot>?start=…`): never loaded in the WebView. Telegram's sign-in step
     * (`oauth.telegram.org`) is not one of them: it stays inside.
     */
    val IN_ITS_APP_HOSTS = listOf("t.me", "telegram.me")

    /** Logins of other companies that refuse an embedded WebView. */
    val SIGN_IN_ELSEWHERE_HOSTS = setOf("accounts.google.com", "appleid.apple.com")

    /**
     * [path] is the URL's path (no query); [companyDomains] and [outsideHosts] the app's ([com.vougadev.shell.ShellConfig]);
     * [userTapped]: the person tapped it (not a redirect within a flow).
     */
    fun decide(
        scheme: String?,
        host: String?,
        isSite: Boolean,
        isNativeRoute: Boolean,
        path: String? = null,
        companyDomains: List<String> = emptyList(),
        outsideHosts: List<String> = emptyList(),
        userTapped: Boolean = true,
    ): Decision {
        val s = scheme?.lowercase(Locale.ROOT) ?: return Decision.BLOCK
        val web = s == "https" || s == "http"
        val h = host?.lowercase(Locale.ROOT)
        return when {
            isSite && SignInRoute.isSiteSignIn(path) -> Decision.SIGN_IN
            isSite && isNativeRoute -> Decision.OPEN_NATIVE
            isSite -> Decision.LOAD_INSIDE
            s == "https" && isCompany(h, companyDomains) -> Decision.LOAD_INSIDE
            web && h in SIGN_IN_ELSEWHERE_HOSTS -> Decision.SIGN_IN_ELSEWHERE
            web && isCompany(h, IN_ITS_APP_HOSTS) -> Decision.OPEN_IN_ITS_APP
            web && isCompany(h, outsideHosts) -> Decision.OUTSIDE_STEP
            // A step of a flow (a redirect, nobody tapped): it stays in the WebView while it works there.
            web && !userTapped -> Decision.LOAD_INSIDE
            web -> Decision.OPEN_THIRD_PARTY
            s == "intent" -> Decision.OPEN_INTENT
            s in BLOCKED -> Decision.BLOCK
            else -> Decision.OPEN_APP
        }
    }

    /** A company domain or one of its subdomains: `vougadev.com`, `<sign-in domain>` - never `vougadev.com.evil.io`. */
    fun isCompany(host: String?, companyDomains: List<String>): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return companyDomains.any { domain ->
            val d = domain.lowercase(Locale.ROOT).trim('.')
            d.isNotEmpty() && (h == d || h.endsWith(".$d"))
        }
    }
}

/**
 * The site's sign-in and sign-out addresses, as the shell meets them in the WebView. Pure, tested on the JVM.
 *
 * - the site's `/auth/login/?next=<path>`, where a lost session is sent;
 * - the site's sign-out: the web's form POSTs `/auth/logout/`.
 */
object SignInRoute {
    const val SITE_SIGN_IN = "/auth/login/"
    const val SITE_SIGN_OUT = "/auth/logout/"

    fun isSiteSignIn(path: String?): Boolean = path == SITE_SIGN_IN || path == SITE_SIGN_IN.trimEnd('/')

    /**
     * What a site's sign-in link asks of the account service's own sign-in: `prompt=create` (the sign-up form first),
     * `login` or `select_account`, and `max_age` (re-authentication). Null when it asks nothing: the session is renewed.
     */
    data class Asked(val prompt: String?, val maxAge: Int?) {
        companion object {
            private val PROMPTS = setOf("create", "login", "select_account")

            fun of(prompt: String?, maxAge: String?): Asked? {
                val asked = Asked(prompt?.trim()?.takeIf { it in PROMPTS }, maxAge?.trim()?.toIntOrNull()?.takeIf { it >= 0 })
                return if (asked.prompt == null && asked.maxAge == null) null else asked
            }
        }
    }

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

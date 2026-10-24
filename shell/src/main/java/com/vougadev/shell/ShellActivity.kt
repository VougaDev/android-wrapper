package com.vougadev.shell

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vougadev.shell.auth.AppSession
import com.vougadev.shell.auth.BrowserSignIn
import com.vougadev.shell.auth.Renewal
import com.vougadev.shell.net.AppHttp
import com.vougadev.shell.net.SessionCookies
import com.vougadev.shell.web.AppOrigin
import com.vougadev.shell.web.LinkPolicy
import com.vougadev.shell.web.ModalBack
import com.vougadev.shell.web.NativeBridge
import com.vougadev.shell.web.NativeShim
import com.vougadev.shell.web.PullGuard
import com.vougadev.shell.web.SignInRoute
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vougadev.shell.push.Push
import com.vougadev.shell.push.PushNotifications
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * THE SHELL'S ONE SCREEN (VougaDev Native, README.md): the app's site in a WebView, and the few things a page cannot do
 * on its own - Android's location prompts and the "Turn on location" dialog, the camera app for a photo, maps and phone
 * links handed to their apps, the back press over an open modal, pull-to-refresh, a native offline screen, a crashed
 * renderer rebuilt. What else the app does comes from its extensions ([Extension]): their members of
 * `window.VougaDevNative`, the native screens a path opens, the pages they answer offline.
 *
 * SIGN-IN IS NATIVE, IN THE SYSTEM BROWSER (the account service's rule: never a WebView). The app signs in with the
 * account service in a Custom Tab ([BrowserSignIn]), trades the access token for the site's session
 * (`/auth/app-session/`) and puts its cookies in the WebView's jar. When the WebView heads for a sign-in (`/auth/login/`,
 * the account service's pages) the session is renewed silently ([com.vougadev.shell.auth.SessionKeeper]); only a
 * refused refresh shows the sign-in screen, one button. Right after a new sign-in, notifications are asked with the
 * app's own sentence, once. The web's sign-out form is taken over: the extensions stop, the site's session ends, the
 * refresh token is revoked, everything of the person is cleared on the phone - never the VougaDev session.
 *
 * Only the app's origin is ever loaded or granted anything. See [AppOrigin].
 *
 * THE BRIDGE IS `window.VougaDevNative`, the one name every VougaDev app uses: `platform()`, `version()`, `features()`,
 * `consent()`, `openSettings()` and the extensions' members ([NativeShim], [NativeBridge]), injected with
 * `addWebMessageListener` and `addDocumentStartJavaScript` restricted to the app's origin, never
 * `addJavascriptInterface`.
 *
 * An app declares a subclass as its launcher (its component name stays the app's own) and adds nothing to it.
 */
open class ShellActivity : AppCompatActivity(), ExtensionHost {

    final override val shell: Shell by lazy { Shell.of(this) }
    private val session get() = shell.session

    private val origin: AppOrigin by lazy { session.origin }
    private val auth: AppOrigin by lazy { AppOrigin(session.config.authBaseUrl) }
    final override val location = LocationAsk(this)
    final override val activity: AppCompatActivity get() = this
    private lateinit var webView: WebView
    private lateinit var pull: SwipeRefreshLayout
    private lateinit var errorView: View

    /** What each extension does inside this activity. */
    private var attachments: List<Attachment> = emptyList()

    // --- Sign-in -----------------------------------------------------------------------------------------------------

    private lateinit var browser: BrowserSignIn
    private lateinit var signInView: View
    private lateinit var signInText: TextView
    private lateinit var signInProgress: ProgressBar
    private lateinit var signInButton: Button

    /** What the sign-in screen shows; null when it is hidden. */
    private var signInScreen: SignInScreen? = null

    /** The page to open once the session is open (the sign-in's `next`). */
    private var afterSignIn: String? = null
    private var signingOut = false

    private val browserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lifecycleScope.launch { finishBrowserSignIn(result.data) }
    }

    /** This start's first page, run once the activity is in front and the WebView attached. */
    private var firstLoad: (() -> Unit)? = null

    /** False once the renderer died and this WebView was destroyed; the activity is recreating. */
    private var webViewAlive = true

    private var backCallback: OnBackPressedCallback? = null
    private var backCheckPending = false

    /** The page's answer for the last touch: a pull there is the page's own scroll, not a refresh. */
    private var pullBlocked = false

    /** The URL whose main-frame load failed, reloaded by Retry. */
    private var failedUrl: String? = null

    // --- Permissions an extension asks, notifications, Settings -----------------------------------------------------

    private var afterPermissions: (() -> Unit)? = null

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.also { afterPermissions = null }?.invoke()
    }

    private var afterNotifications: (() -> Unit)? = null

    private val notificationsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotifications?.also { afterNotifications = null }?.invoke()
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == SettingsActivity.RESULT_SIGN_OUT) signOut()
    }

    // --- File chooser (photos) ------------------------------------------------------------------------------

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutput: Uri? = null
    private var cameraFile: File? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        callback.onReceiveValue(if (result.resultCode == RESULT_OK) pickedUris(result.data) else null)
        cameraOutput = null
        cameraFile = null
    }

    // --- Lifecycle --------------------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.shell_activity)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        webView = findViewById(R.id.web)
        pull = findViewById(R.id.pull)
        errorView = findViewById(R.id.error)
        findViewById<Button>(R.id.retry).setOnClickListener { retry() }
        browser = BrowserSignIn(this, session)
        signInView = findViewById(R.id.signin)
        signInText = findViewById(R.id.signin_text)
        signInProgress = findViewById(R.id.signin_progress)
        signInButton = findViewById(R.id.signin_button)
        signInButton.setOnClickListener { onSignInButton() }

        WebView.setWebContentsDebuggingEnabled(session.config.debug)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString + AppHttp.userAgentSuffix(session.config.userAgentToken, session.config.versionName)
        }
        webView.webViewClient = ShellClient()
        webView.webChromeClient = ShellChrome()
        // The extensions' parts of this screen, before it starts (they may register activity result launchers).
        attachments = shell.extensions.mapNotNull { it.attach(this) }
        // Before the first load: a document-start script only reaches documents created after it.
        installNativeBridge()

        // PULL-TO-REFRESH ONLY AT THE PAGE'S TOP: the WebView's own scroll, and the page's answer for the touched point.
        pull.setColorSchemeColors(ContextCompat.getColor(this, R.color.shell_pull))
        pull.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || pullBlocked }
        pull.setOnRefreshListener {
            if (webViewAlive) webView.reload()
        }
        webView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && webViewAlive) {
                pullBlocked = false
                val density = view.resources.displayMetrics.density
                webView.evaluateJavascript(PullGuard.script(event.x / density, event.y / density)) { pullBlocked = PullGuard.blocks(it) }
            }
            false
        }

        // Back: an open modal or panel first (the site's pages keep no history entry for one), then history, then leave.
        backCallback = onBackPressedDispatcher.addCallback(this) {
            if (!webViewAlive || errorView.isVisible || signInView.isVisible) {
                leaveOnBack()
            } else if (!backCheckPending) {
                backCheckPending = true
                webView.evaluateJavascript(ModalBack.SCRIPT) { result ->
                    backCheckPending = false
                    when {
                        ModalBack.closedAModal(result) -> Unit
                        webView.canGoBack() -> webView.goBack()
                        else -> leaveOnBack()
                    }
                }
            }
        }

        clearOldCaptures()
        PushNotifications.ensureChannel(this)
        val asked = if (savedInstanceState == null) pageFrom(intent) else null
        // THE FIRST LOAD WAITS FOR THE WINDOW (measured on a low-end phone): a renderer requested before the WebView is
        // attached was refused by the system, and the page stayed black until a reboot.
        firstLoad = { withConsent { boot(asked, savedInstanceState) } }
    }

    /** The native consent dialog before the first page, once ([Consent]); then its cookie for the site. */
    private fun withConsent(then: () -> Unit) {
        if (Consent.needed(this)) Consent.ask(this, then) else then()
    }

    /**
     * This start's first screen. Never signed in on this phone: the sign-in screen (cookies left by an earlier web
     * sign-in are not the app's, they go). Signed in but the site's session gone: renewed first. Else the page.
     */
    private fun boot(asked: Ask?, savedInstanceState: Bundle?) {
        val page = (asked as? Ask.Page)?.url
        when {
            asked is Ask.SignIn -> askSignIn(asked.next)
            !session.signedIn -> {
                forgetOnPhone()
                afterSignIn = page
                show(SignInScreen.SIGN_IN)
            }
            !SessionCookies.isSignedIn(session.cookies.jar()) -> renewThen(page)
            page != null -> open(page)
            savedInstanceState == null || webView.restoreState(savedInstanceState) == null -> webView.loadUrl(home())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (val asked = pageFrom(intent)) {
            null -> Unit
            is Ask.SignIn -> askSignIn(asked.next)
            is Ask.Page -> if (webViewAlive && signInScreen == null) open(asked.url) else afterSignIn = asked.url
        }
    }

    override fun onResume() {
        super.onResume()
        // `post` runs after the view is attached to this window, not merely created.
        firstLoad?.let { load ->
            firstLoad = null
            webView.post { if (webViewAlive) load() }
        }
        if (webViewAlive) webView.onResume()
        attachments.forEach { it.onResume() }
    }

    override fun onPause() {
        if (webViewAlive) webView.onPause()
        // The session cookie is what native calls are made with; get it to disk now.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (webViewAlive) webView.saveState(outState)
    }

    override fun onDestroy() {
        attachments.forEach { it.onDestroy() }
        browser.dispose()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (webViewAlive) webView.destroy()
        super.onDestroy()
    }

    /** What an intent asks for. */
    private sealed interface Ask {
        data class Page(val url: String) : Ask

        /** The native screens could not renew the session: the sign-in, then [next]. */
        data class SignIn(val next: String) : Ask
    }

    /**
     * A tapped notification's path, or the sign-in after the native screens lost the session (then that path). Null
     * for every other intent (the launcher, a restore). Checked again here: this activity is exported.
     */
    private fun pageFrom(intent: Intent?): Ask? {
        val action = intent?.action
        val path = intent?.getStringExtra(EXTRA_PAGE_PATH)
        intent?.action = null
        intent?.removeExtra(EXTRA_PAGE_PATH)
        return when (action) {
            ACTION_OPEN_PAGE -> Ask.Page(origin.pageUrl(path) ?: home())
            ACTION_SIGN_IN -> Ask.SignIn(origin.pageUrl(path) ?: home())
            else -> null
        }
    }

    private fun home() = origin.baseUrl + "/"

    // --- Sign-in, renewal and sign-out ------------------------------------------------------------------------------

    private enum class SignInScreen { WORKING, SIGNING_OUT, SIGN_IN, FAILED, NO_BROWSER, OFFLINE }

    private fun show(screen: SignInScreen) {
        signInScreen = screen
        signInView.visibility = View.VISIBLE
        val busy = screen == SignInScreen.WORKING || screen == SignInScreen.SIGNING_OUT
        signInProgress.isVisible = busy
        signInButton.isVisible = !busy
        signInText.setText(
            when (screen) {
                SignInScreen.WORKING -> R.string.shell_signin_working
                SignInScreen.SIGNING_OUT -> R.string.shell_signing_out
                SignInScreen.SIGN_IN -> R.string.shell_signin_text
                SignInScreen.FAILED -> R.string.shell_signin_failed
                SignInScreen.NO_BROWSER -> R.string.shell_signin_no_browser
                SignInScreen.OFFLINE -> R.string.shell_signin_offline
            },
        )
        signInButton.setText(if (screen == SignInScreen.OFFLINE) R.string.shell_load_error_retry else R.string.shell_signin_button)
    }

    private fun hideSignIn() {
        signInScreen = null
        signInView.visibility = View.GONE
    }

    private fun onSignInButton() = when (signInScreen) {
        SignInScreen.OFFLINE -> renewThen(afterSignIn)
        SignInScreen.SIGN_IN, SignInScreen.FAILED, SignInScreen.NO_BROWSER -> startBrowserSignIn()
        else -> Unit
    }

    /** The native screens' lost session: renewed again if a token is kept, else the sign-in screen. */
    private fun askSignIn(next: String) {
        if (session.signedIn) {
            renewThen(next)
        } else {
            afterSignIn = next
            show(SignInScreen.SIGN_IN)
        }
    }

    /**
     * THE SESSION IS LOST (the WebView headed for a sign-in, or this start found no session of the site): renewed silently
     * behind the progress, then [next]; the sign-in screen only when the account service refused the refresh.
     */
    private fun renewThen(next: String?) {
        afterSignIn = next ?: afterSignIn
        if (signInScreen == SignInScreen.WORKING || signingOut) return
        show(SignInScreen.WORKING)
        lifecycleScope.launch {
            when (session.keeper.renew(session.cookies.jar())) {
                Renewal.RENEWED -> sessionOpen(newSignIn = false)
                Renewal.SIGN_IN -> {
                    forgetLocalData()
                    show(SignInScreen.SIGN_IN)
                }
                Renewal.OFFLINE -> show(SignInScreen.OFFLINE)
            }
        }
    }

    /** One button: the account service's sign-in in a Custom Tab. */
    private fun startBrowserSignIn() {
        show(SignInScreen.WORKING)
        lifecycleScope.launch {
            val intent = try {
                browser.intent()
            } catch (_: ActivityNotFoundException) {
                return@launch show(SignInScreen.NO_BROWSER)
            } ?: return@launch show(SignInScreen.OFFLINE)
            try {
                browserLauncher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                show(SignInScreen.NO_BROWSER)
            }
        }
    }

    private suspend fun finishBrowserSignIn(data: Intent?) {
        show(SignInScreen.WORKING)
        when (val outcome = browser.finish(data)) {
            BrowserSignIn.Outcome.Cancelled -> show(SignInScreen.SIGN_IN)
            BrowserSignIn.Outcome.Failed -> show(SignInScreen.FAILED)
            is BrowserSignIn.Outcome.Done -> when (outcome.renewal) {
                Renewal.RENEWED -> sessionOpen(newSignIn = true)
                Renewal.OFFLINE -> show(SignInScreen.OFFLINE)
                Renewal.SIGN_IN -> show(SignInScreen.FAILED)
            }
        }
    }

    /** The site's session is in the jar: the page asked for (or home); after a new sign-in, the push token and its ask. */
    private fun sessionOpen(newSignIn: Boolean) {
        hideSignIn()
        val target = afterSignIn ?: home()
        afterSignIn = null
        if (newSignIn) {
            // another person may have used the phone before: no way back to their pages
            webView.clearHistory()
            Push.afterSignIn(this)
            askNotificationsOnce()
        }
        if (webViewAlive) open(target)
    }

    /**
     * NOTIFICATIONS ARE ASKED RIGHT AFTER SIGN-IN, ONCE: the app's own sentence (what it sends and why), then the
     * system's dialog (Android 13+). A refusal blocks nothing; Settings is where they are turned on later.
     */
    private fun askNotificationsOnce() {
        if (!Push.enabled(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(session.config.storeName + "_notifications", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit { putBoolean(KEY_ASKED, true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shell_notifications_title)
            .setMessage(R.string.shell_notifications_reason)
            .setPositiveButton(R.string.shell_notifications_continue) { _, _ -> askNotifications {} }
            .setNegativeButton(R.string.shell_notifications_later, null)
            .show()
    }

    override fun askNotifications(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return then()
        afterNotifications = then
        notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun requestPermissions(permissions: Array<String>, then: () -> Unit) {
        afterPermissions = then
        permissionsLauncher.launch(permissions)
    }

    private fun openSettings() = settingsLauncher.launch(Intent(this, SettingsActivity::class.java))

    /** Every extension's part of the person on this phone goes (off the main thread, never cancelled half-way). */
    private fun forgetLocalData() {
        lifecycleScope.launch { withContext(NonCancellable) { shell.forget() } }
    }

    /** A signed-in page: the push token once per start, and each extension's follow-up. */
    private fun onSignedInPage() {
        Push.startOncePerProcess(this)
        attachments.forEach { it.onSignedInPage() }
    }

    /** The native screen an extension owns for a same-origin [path], if any. */
    private fun nativeScreen(path: String?): Intent? =
        path?.let { p -> shell.extensions.firstNotNullOfOrNull { it.nativeScreen(this, p) } }

    /**
     * THE WEB'S SIGN-OUT, TAKEN OVER (and Settings' Sign out): the extensions stop, then [AppSession.signOut] (the
     * site's session ended while it still exists, the refresh token revoked at the account service, the tokens, cookies
     * and what the extensions keep cleared), and the web storage. The VougaDev session is not ended (no end_session):
     * the person's other applications stay signed in.
     */
    private fun signOut() {
        if (signingOut) return
        signingOut = true
        show(SignInScreen.SIGNING_OUT)
        attachments.forEach { it.beforeSignOut() }
        lifecycleScope.launch {
            try {
                withContext(NonCancellable) { session.signOut() }
            } finally {
                signingOut = false
            }
            WebStorage.getInstance().deleteAllData()
            if (webViewAlive) {
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.clearCache(false)
            }
            Consent.setCookie(this@ShellActivity)
            afterSignIn = null
            show(SignInScreen.SIGN_IN)
        }
    }

    /** Never signed in with the app: an earlier web session's cookies and data are not this app's. */
    private fun forgetOnPhone() {
        session.cookies.clear()
        Consent.setCookie(this)
        forgetLocalData()
    }

    /** A page by URL: a native screen opens over the home page; anything else loads here. */
    private fun open(url: String) {
        val uri = url.toUri()
        val screen = if (origin.matches(uri)) nativeScreen(uri.path) else null
        if (screen == null) {
            webView.loadUrl(url)
            return
        }
        if (webView.url == null) webView.loadUrl(origin.baseUrl + "/")
        startActivity(screen)
    }

    /** What back does with nothing left to close or go back to: the system's default (home, or finish). */
    private fun leaveOnBack() {
        val callback = backCallback ?: return
        callback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        callback.isEnabled = true
    }

    private fun retry() {
        errorView.visibility = View.GONE
        val url = failedUrl ?: (origin.baseUrl + "/")
        failedUrl = null
        webView.loadUrl(url)
    }

    // --- Page loading -----------------------------------------------------------------------------------------------

    // androidx.webkit's RenderProcessGoneDetector reports every `WebViewClient()` constructor call, and Kotlin's
    // supertype call is one; onRenderProcessGone IS implemented below.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class ShellClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Frames inside a page navigate within themselves; only the top level is policed.
            if (!request.isForMainFrame) return false
            val uri = request.url
            val isSite = origin.matches(uri)
            val screen = if (isSite) nativeScreen(uri.path) else null
            return when (LinkPolicy.decide(uri.scheme, isSite, auth.matches(uri), screen != null, uri.path, session.config.authSignInPaths)) {
                LinkPolicy.Decision.LOAD_INSIDE -> false
                LinkPolicy.Decision.SIGN_IN -> {
                    // NEVER SIGN IN IN THE WEBVIEW: the session is renewed, then the sign-in's `next` opens
                    renewThen(SignInRoute.after(origin, if (isSite) uri.getQueryParameter("next") else null))
                    true
                }
                LinkPolicy.Decision.OPEN_NATIVE -> {
                    // Back from the native screen lands on a page of the site: never on the account service's callback that redirected here.
                    if (request.isRedirect || !origin.matches(view.url)) view.loadUrl(origin.baseUrl + "/")
                    startActivity(screen!!)
                    true
                }
                LinkPolicy.Decision.OPEN_OUTSIDE -> {
                    openOutside(uri)
                    true
                }
                LinkPolicy.Decision.OPEN_INTENT -> {
                    openIntentUrl(uri.toString())
                    true
                }
                LinkPolicy.Decision.BLOCK -> true
            }
        }

        /**
         * The web's sign-out form (a top-level POST to `/auth/logout/`) never reaches the site from the WebView: the app
         * signs out itself, in its own order ([signOut]). A same-origin GET an extension can answer from the phone (an
         * offline map tile) is answered there. Called on a WebView thread.
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            val isSite = origin.matches(url)
            if (request.isForMainFrame && SignInRoute.isSignOut(request.method, isSite, url.path)) {
                runOnUiThread { signOut() }
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            if (!isSite || !request.method.equals("GET", ignoreCase = true)) return null
            return shell.extensions.firstNotNullOfOrNull { it.interceptRequest(applicationContext, url) }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (failedUrl == null) errorView.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pull.isRefreshing = false
            if (!origin.matches(url)) return
            if (SessionCookies.isSignedIn(CookieManager.getInstance().getCookie(origin.baseUrl))) onSignedInPage()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            pull.isRefreshing = false
            failedUrl = request.url.toString()
            errorView.visibility = View.VISIBLE
        }

        /**
         * The renderer crashed or Android reclaimed it for memory (low-end phones do). Answering false kills the whole
         * app; instead the dead WebView is dropped and the screen rebuilt, which reloads the site.
         */
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "WebView renderer gone, crashed=${detail.didCrash()}")
            webViewAlive = false
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.shell_no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * `intent:` URLs are how maps and chat links ask for a specific app. The page does not get to name a component of
     * this app or any other: only what a browser would allow is started.
     */
    private fun openIntentUrl(url: String) {
        val intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse)
            when {
                fallback == null -> Toast.makeText(this, R.string.shell_no_app_for_link, Toast.LENGTH_SHORT).show()
                origin.matches(fallback) -> webView.loadUrl(fallback.toString())
                fallback.scheme == "https" || fallback.scheme == "http" -> openOutside(fallback)
            }
        }
    }

    // --- What the page asks of the phone ----------------------------------------------------------------------------

    private inner class ShellChrome : WebChromeClient() {

        /** The site's pages only; the app raises the permission and the "Turn on location" dialogs itself. */
        override fun onGeolocationPermissionsShowPrompt(requestOrigin: String?, callback: GeolocationPermissions.Callback) {
            if (!origin.matches(requestOrigin)) {
                callback.invoke(requestOrigin, false, false)
                return
            }
            // Not retained: the next page asks again, and is answered at once while Android's permission stands.
            location.ask { callback.invoke(requestOrigin, location.allowed(), false) }
        }

        /** The site's pages do not stream the camera or the microphone. */
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            // A form of the site, or nothing: a foreign page has no business with the person's photos.
            if (!origin.matches(view.url)) return false
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val acceptsImages = params.acceptTypes.all { it.isBlank() } || params.acceptTypes.any { it.startsWith("image") }
            launchChooser(params, withCamera = acceptsImages)
            return true
        }
    }

    /** `capture` on the input: the camera at once; otherwise the gallery, with the camera offered beside it. */
    private fun launchChooser(params: WebChromeClient.FileChooserParams, withCamera: Boolean) {
        val gallery = params.createIntent()
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val camera = if (withCamera) cameraIntent() else null
        val intent = when {
            camera != null && params.isCaptureEnabled -> camera
            camera != null -> Intent.createChooser(gallery, getString(R.string.shell_file_chooser_title)).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            else -> gallery
        }
        try {
            fileLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }

    /** The camera app, writing into the app's cache (no CAMERA permission is declared, so none is needed). */
    private fun cameraIntent(): Intent? {
        val dir = File(cacheDir, CAPTURES_DIR).apply { mkdirs() }
        val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (_: IllegalArgumentException) {
            return null
        }
        cameraFile = file
        cameraOutput = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // The grant travels with the ClipData, which is what a chooser forwards.
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** What the picker or the camera returned; null tells the page the person cancelled. */
    private fun pickedUris(data: Intent?): Array<Uri>? {
        val clip = data?.clipData
        if (clip != null && clip.itemCount > 0) {
            val fromPicker = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.filter { it != cameraOutput }
            if (fromPicker.isNotEmpty()) return fromPicker.toTypedArray()
        }
        data?.data?.let { if (it != cameraOutput) return arrayOf(it) }
        val shot = cameraFile
        if (shot != null && shot.length() > 0) return cameraOutput?.let { arrayOf(it) }
        return null
    }

    /** Photos already handed to a page or a handover were sent or abandoned; the cache is not an album. */
    private fun clearOldCaptures() {
        File(cacheDir, CAPTURES_DIR).listFiles()?.forEach { it.delete() }
    }

    // --- window.VougaDevNative ---------------------------------------------------------------------------------------

    /** The core's Promise members, in every app: the consent choices, and the native Settings screen. */
    private fun coreMethods(): Map<String, NativeBridge.Method> = linkedMapOf(
        "consent" to NativeBridge.Method { _, answer -> answer(JSONObject(Consent.choices(this).toMap())) },
        "openSettings" to NativeBridge.Method { _, answer ->
            openSettings()
            answer(NativeBridge.ok())
        },
    )

    /**
     * `window.VougaDevNative` in frames of the app's origin only: the web message listener (the shim's private channel)
     * and the document-start script that defines the object, both restricted to exactly that origin. A WebView without
     * either feature gets neither, and the page behaves as the plain web app.
     */
    private fun installNativeBridge() {
        // Nested positive checks: the RequiresFeature lint reads one condition per `if`.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val rules = NativeShim.originRules(origin)
                val methods = coreMethods() + attachments.flatMap { it.bridgeMethods().entries }.associate { it.key to it.value }
                val bridge = NativeBridge(origin, methods)
                WebViewCompat.addWebMessageListener(webView, NativeShim.CHANNEL, rules) { _, message, sourceOrigin, _, replyProxy ->
                    bridge.onMessage(message.data, sourceOrigin.toString()) { reply -> replyProxy.postMessage(reply) }
                }
                WebViewCompat.addDocumentStartJavaScript(webView, NativeShim.script(session.config.versionName, shell.features, methods.keys.toList()), rules)
                return
            }
        }
        Log.w(
            TAG,
            "VougaDevNative not injected: DOCUMENT_START_SCRIPT=" +
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) +
                " WEB_MESSAGE_LISTENER=" + WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
        )
    }

    companion object {
        /** A tapped notification: [EXTRA_PAGE_PATH] is the page's same-origin path. */
        private const val ACTION_OPEN_PAGE = "com.vougadev.shell.action.OPEN_PAGE"

        /** The native screens could not renew the session: the native sign-in, then back to [EXTRA_PAGE_PATH]. */
        private const val ACTION_SIGN_IN = "com.vougadev.shell.action.SIGN_IN"
        private const val EXTRA_PAGE_PATH = "com.vougadev.shell.extra.PAGE_PATH"
        private const val TAG = "VougaDevShell"
        private const val CAPTURES_DIR = "captures"
        private const val KEY_ASKED = "asked"

        /** [launcher] (the app's shell activity) opening the same-origin [path]. */
        fun withPage(launcher: Intent, path: String?): Intent = launcher
            .setAction(ACTION_OPEN_PAGE)
            .putExtra(EXTRA_PAGE_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** [launcher] (the app's shell activity) asking for the sign-in, then the same-origin [nextPath]. */
        fun withSignIn(launcher: Intent, nextPath: String): Intent = launcher
            .setAction(ACTION_SIGN_IN)
            .putExtra(EXTRA_PAGE_PATH, nextPath)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** [AppOrigin.matches] for an Android URI, without a round trip through a string. */
private fun AppOrigin.matches(uri: Uri?): Boolean = uri != null && matchesParts(uri.scheme, uri.host, uri.port)

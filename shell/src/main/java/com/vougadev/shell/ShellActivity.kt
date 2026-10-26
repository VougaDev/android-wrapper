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
import android.os.SystemClock
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
import android.widget.LinearLayout
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.auth.AuthTabIntent
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import net.openid.appauth.AuthorizationRequest
import androidx.webkit.WebViewFeature
import com.vougadev.shell.auth.AppSession
import com.vougadev.shell.auth.BrowserSignIn
import com.vougadev.shell.auth.Renewal
import com.vougadev.shell.auth.GoogleSignIn
import com.vougadev.shell.auth.SessionWatch
import com.vougadev.shell.offline.Connectivity
import com.vougadev.shell.offline.OfflineCapture
import com.vougadev.shell.offline.Outage
import com.vougadev.shell.offline.PageCache
import com.vougadev.shell.offline.PageFetch
import com.vougadev.shell.push.PushRoute
import com.vougadev.shell.net.AppHttp
import com.vougadev.shell.net.SessionCookies
import com.vougadev.shell.web.AppOrigin
import com.vougadev.shell.web.LinkPolicy
import com.vougadev.shell.web.MediaGrant
import com.vougadev.shell.web.ModalBack
import com.vougadev.shell.web.NativeBridge
import com.vougadev.shell.web.NativeShim
import com.vougadev.shell.web.PullGuard
import com.vougadev.shell.web.SignInRoute
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vougadev.shell.push.Push
import com.vougadev.shell.push.PushNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

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
    private val trusted: List<AppOrigin> by lazy { session.config.trustedOrigins.map(::AppOrigin) }
    final override val location = LocationAsk(this)
    final override val activity: AppCompatActivity get() = this
    private lateinit var webView: WebView
    private lateinit var pull: SwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var errorBack: Button
    private lateinit var offlineList: LinearLayout
    private lateinit var offlineBar: TextView

    // --- Offline: the pages kept on the phone ([PageCache]) -----------------------------------------------------------

    private lateinit var connectivity: Connectivity
    private val capture by lazy { OfflineCapture(session, shell.pages, getString(R.string.app_name)) }
    private val pageFetch = PageFetch(AppHttp.client.newBuilder().callTimeout(PageFetch.PAGE_SECONDS, java.util.concurrent.TimeUnit.SECONDS).build())

    /** The same-origin files the page on screen asked for (written on WebView threads). */
    private val pageFiles: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Pages answered from the phone, with when they were kept: the bar says so once the page is up. */
    private val servedFromPhone = ConcurrentHashMap<String, Long>()

    /**
     * When a load last failed for want of network while Android still said online (a VPN can keep a network "validated"
     * with no way out): pages come from the phone for [FORCED_OFFLINE_MS] after it, then the network is tried again.
     */
    @Volatile
    private var forcedOfflineAt = 0L

    private var forcedOffline: Boolean
        get() = forcedOfflineAt != 0L && SystemClock.elapsedRealtime() - forcedOfflineAt < FORCED_OFFLINE_MS
        set(value) {
            forcedOfflineAt = if (value) SystemClock.elapsedRealtime() else 0L
        }

    /**
     * The site's edge answered instead of the site (a release restarts it) on a working network: pages come from the
     * phone, the person reads that the service is restarting - never "offline" - and [watchRestart] tries again.
     */
    @Volatile
    private var restarting = false
    private var restartWatch: Job? = null

    /** The outside step in Chrome's Auth Tab (or a Custom Tab), and the page's answer waiting for its return. */
    private var outsideAnswer: ((JSONObject) -> Unit)? = null

    private val outsideLauncher = AuthTabIntent.registerActivityResultLauncher(this) { result -> onOutsideReturn(result) }

    /** When the page on screen came from the phone (for `network()`); null when it came from the network. */
    private var shownKeptAt: Long? = null

    /** The sign-in running inside the app's WebView (its PKCE request), until the account service answers. */
    private var signInRequest: AuthorizationRequest? = null

    /** The main frame's HTTP status, per address, until the page has finished (WebView threads write). */
    private val httpStatus = ConcurrentHashMap<String, Int>()

    /** No page of this start has loaded yet: a first load that fails for want of network opens the last page kept. */
    @Volatile
    private var firstPageOfStart = true

    private val offline: Boolean get() = forcedOffline || !connectivity.online

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
    private val sessionWatch = SessionWatch()

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
        Push.afterSignIn(this) // the token is reported only once notifications are allowed
        afterNotifications?.also { afterNotifications = null }?.invoke()
    }

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            SettingsActivity.RESULT_SIGN_OUT -> signOut()
            // The privacy policy, the account's screens: the company's own, in this same WebView.
            SettingsActivity.RESULT_OPEN -> result.data?.getStringExtra(SettingsActivity.EXTRA_URL)?.let { url ->
                errorView.visibility = View.GONE
                failedUrl = null
                if (webViewAlive) webView.loadUrl(url)
            }
        }
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
        errorTitle = findViewById(R.id.error_title)
        errorText = findViewById(R.id.error_text)
        errorBack = findViewById(R.id.error_back)
        errorBack.setOnClickListener {
            errorView.visibility = View.GONE
            failedUrl = null
            if (webViewAlive && webView.canGoBack()) webView.goBack()
        }
        offlineList = findViewById(R.id.offline_list)
        offlineBar = findViewById(R.id.offline_bar)
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
        // PASSKEYS in the WebView, for the account domain that declares this app (its Digital Asset Links).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(webView.settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
        }
        signInRequest = savedInstanceState?.getString(KEY_SIGN_IN_REQUEST)?.let {
            try {
                AuthorizationRequest.jsonDeserialize(it)
            } catch (_: org.json.JSONException) {
                null
            }
        }
        webView.webViewClient = ShellClient()
        webView.webChromeClient = ShellChrome()
        // The extensions' parts of this screen, before it starts (they may register activity result launchers).
        attachments = shell.extensions.mapNotNull { it.attach(this) }
        // Before the first load: a document-start script only reaches documents created after it.
        installNativeBridge()
        // The page knows when the network goes (navigator.onLine, `online`/`offline` events): its actions wait for it.
        connectivity = Connectivity(this) { online -> onNetwork(online) }
        connectivity.start()
        webView.setNetworkAvailable(connectivity.online)

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
                        // Back out of the account service's first screen: the app is left, as from any first screen.
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
     * This start's first screen. Never signed in on this phone: the account service's sign-in, straight in the WebView
     * (cookies left by an earlier web sign-in are not the app's, they go). Signed in but the site's session gone:
     * renewed first. Else the page.
     */
    private fun boot(asked: Ask?, savedInstanceState: Bundle?) {
        val page = (asked as? Ask.Page)?.url
        when {
            asked is Ask.SignIn -> askSignIn(asked.next)
            !session.signedIn && session.config.signInFirst -> {
                forgetOnPhone()
                afterSignIn = page
                startInAppSignIn()
            }
            // NO NETWORK: what the person had, from the phone - never a renewal that cannot happen, never an error.
            !connectivity.online -> openOffline(page)
            // Not signed in, the site's public pages ([ShellConfig.signInFirst] false); a web session left on the phone is not the app's.
            !session.signedIn -> {
                if (SessionCookies.isSignedIn(session.cookies.jar())) forgetOnPhone()
                webView.loadUrl(page ?: home())
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
        // Not under our own location dialog: a paused WebView forgets its answer and, resumed, asks again - for ever.
        if (webViewAlive && !location.asking) webView.onPause()
        // The session cookie is what native calls are made with; get it to disk now.
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (webViewAlive) webView.saveState(outState)
        signInRequest?.let { outState.putString(KEY_SIGN_IN_REQUEST, it.jsonSerializeString()) }
    }

    override fun onDestroy() {
        connectivity.stop()
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
        // An outside step came back through the app's own https link (a browser without Auth Tab): into the WebView.
        val back = intent?.dataString
        val prefix = session.config.outsideReturnPrefix
        if (action == Intent.ACTION_VIEW && back != null && prefix != null && back.startsWith(prefix)) {
            intent?.data = null
            outsideAnswer?.invoke(NativeBridge.ok())
            outsideAnswer = null
            return Ask.Page(back)
        }
        return when (action) {
            ACTION_OPEN_PAGE -> Ask.Page(origin.pageUrl(path) ?: home())
            ACTION_SIGN_IN -> Ask.SignIn(origin.pageUrl(path) ?: home())
            else -> null
        }
    }

    private fun home() = origin.baseUrl + "/"

    // --- Sign-in, renewal and sign-out ------------------------------------------------------------------------------

    /** No screen of its own before the sign-in: only while a session opens or closes, and when that fails. */
    private enum class SignInScreen { WORKING, SIGNING_OUT, FAILED, NO_BROWSER, OFFLINE }

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
                SignInScreen.FAILED -> R.string.shell_signin_failed
                SignInScreen.NO_BROWSER -> R.string.shell_signin_no_browser
                SignInScreen.OFFLINE -> R.string.shell_signin_offline
            },
        )
        signInButton.setText(R.string.shell_load_error_retry)
    }

    private fun hideSignIn() {
        signInScreen = null
        signInView.visibility = View.GONE
    }

    private fun onSignInButton() = when (signInScreen) {
        SignInScreen.OFFLINE -> renewThen(afterSignIn)
        SignInScreen.FAILED, SignInScreen.NO_BROWSER -> startInAppSignIn()
        else -> Unit
    }

    /** The native screens' lost session: renewed again if a token is kept, else the sign-in screen. */
    private fun askSignIn(next: String) {
        if (session.signedIn) {
            renewThen(next)
        } else {
            afterSignIn = next
            startInAppSignIn()
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
                    startInAppSignIn()
                }
                // No network to renew with: the pages kept on the phone meanwhile; the network's return renews.
                Renewal.OFFLINE -> {
                    hideSignIn()
                    openOffline(afterSignIn.also { afterSignIn = null })
                }
            }
        }
    }

    /**
     * SIGN-IN INSIDE THE APP: the account service's sign-in (or, [asked], its sign-up) loads in this WebView (one app,
     * one WebView). It ends with the account service's redirect to the app's address, caught in the WebView and traded
     * for the app's tokens ([finishInAppSignIn]).
     */
    private fun startInAppSignIn(asked: SignInRoute.Asked? = null) {
        show(SignInScreen.WORKING)
        lifecycleScope.launch {
            val request = browser.request(asked) ?: return@launch show(SignInScreen.OFFLINE)
            signInRequest = request
            hideSignIn()
            errorView.visibility = View.GONE
            if (webViewAlive) webView.loadUrl(request.toUri().toString())
        }
    }

    private fun finishInAppSignIn(answer: Uri) {
        val request = signInRequest ?: return show(SignInScreen.FAILED)
        signInRequest = null
        show(SignInScreen.WORKING)
        lifecycleScope.launch { signInDone(browser.finish(request, answer)) }
    }

    /** The FALLBACK: the account service's sign-in in a Custom Tab, while another company's login refuses the WebView. */
    private fun startBrowserSignIn() {
        signInRequest = null
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
        signInDone(browser.finish(data))
    }

    private fun signInDone(outcome: BrowserSignIn.Outcome) {
        when (outcome) {
            BrowserSignIn.Outcome.Cancelled -> show(SignInScreen.FAILED)
            BrowserSignIn.Outcome.Failed -> show(SignInScreen.FAILED)
            is BrowserSignIn.Outcome.Done -> when (outcome.renewal) {
                Renewal.RENEWED -> sessionOpen(newSignIn = true)
                Renewal.OFFLINE -> show(SignInScreen.OFFLINE)
                Renewal.SIGN_IN -> show(SignInScreen.FAILED)
            }
        }
    }

    /** The site's session is in the jar: the page asked for (or home); after a new sign-in, the push token. */
    private fun sessionOpen(newSignIn: Boolean) {
        hideSignIn()
        val target = afterSignIn ?: home()
        afterSignIn = null
        if (newSignIn) {
            // another person may have used the phone before: no way back to their pages
            webView.clearHistory()
            Push.afterSignIn(this)
        }
        if (webViewAlive) open(target)
    }

    /**
     * NOTIFICATIONS ARE ASKED RIGHT AFTER SIGN-IN (its first signed-in page), ONCE: the app's own sentence (what it
     * sends and why), then the system's dialog (Android 13+). A refusal blocks nothing; Settings turns them on later.
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

    /** A signed-in page: the push token once per start, notifications asked once, and each extension's follow-up. */
    private fun onSignedInPage() {
        Push.startOncePerProcess(this)
        askNotificationsOnce() // right after a sign-in, or on the first signed-in page after the update that brought push
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
            if (session.config.signInFirst) startInAppSignIn() else { hideSignIn(); webView.loadUrl(home()) }
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
        // What was asked replaces any error screen up.
        errorView.visibility = View.GONE
        failedUrl = null
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
        restarting = false
        errorView.visibility = View.GONE
        val url = failedUrl ?: (origin.baseUrl + "/")
        failedUrl = null
        webView.loadUrl(url)
    }

    /** A page of the site, as the site served it (or the phone kept it): kept for offline, and the session watched. */
    private fun siteFinished(url: String, keptAt: Long?) {
        if (failedUrl == null) firstPageOfStart = false
        if (keptAt == null) restarting = false // the site answered with its own page
        val siteSession = SessionCookies.isSignedIn(CookieManager.getInstance().getCookie(origin.baseUrl))
        // Loaded from the network: kept for offline, with the files it used - only as the person sees it (a page the
        // site sent while dropping the session is renewed below, never kept).
        val page = pageFetch.take(url)
        if (page != null && keptAt == null && !offline && failedUrl == null && (siteSession || !session.signedIn)) {
            val files = pageFiles.toList()
            lifecycleScope.launch { capture.capture(page, files) }
        }
        when {
            siteSession -> onSignedInPage()
            // The site dropped the session (its access token expired) while this phone is signed in: renewed
            // silently, then this page again - never an anonymous page for a signed-in person ([SessionWatch]).
            sessionWatch.shouldRenew(session.signedIn, false, signInScreen != null || signingOut || offline || restarting, SystemClock.elapsedRealtime()) -> {
                Log.i(TAG, "The site dropped the session: renewed, then the page again")
                renewThen(url)
            }
        }
    }

    /**
     * NEVER A RAW ERROR PAGE: an answer that is not the site's own (a 502, 503 or 504 from the edge, a CDN's or a
     * proxy's page, a page of the app's or a trusted origin without the company-page meta [PAGE_META]). The edge answered,
     * so the network works: the service is restarting ([Outage]), retried by itself. The copy kept on the phone - or, as
     * the app starts, the last one kept - with the native bar; else the native screen with what is available.
     */
    private fun foreignPage(url: String, status: Int?) {
        val outage = Outage.of(connectivity.online, status)
        val serverDown = outage != Outage.UNAVAILABLE
        if (outage == Outage.OFFLINE) forcedOffline = true
        if (outage == Outage.RESTARTING) {
            restarting = true
            watchRestart(url)
        }
        val kept = when {
            !serverDown -> null
            shell.pages.savedAt(url) != null -> url
            firstPageOfStart -> shell.pages.pages().firstOrNull()?.url
            else -> null
        }
        if (kept != null) {
            failedUrl = null
            webView.loadUrl(kept)
            return
        }
        failedUrl = url
        showUnavailable(url)
    }

    /** Every [RESTART_RETRY_MS] while the app is in front, [url] is asked of the site: once it answers, the page. */
    private fun watchRestart(url: String) {
        if (restartWatch?.isActive == true) return
        restartWatch = lifecycleScope.launch {
            while (restarting) {
                delay(RESTART_RETRY_MS)
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || offline) continue
                val back = withContext(Dispatchers.IO) {
                    try {
                        // null: a redirect or a non-HTML answer, the site's own
                        pageFetch.fetch(url, emptyMap(), CookieManager.getInstance().getCookie(url), session.userAgent, session.acceptLanguage())
                            ?.let { Outage.back(it.code, String(it.body), PAGE_META) } ?: true
                    } catch (_: java.io.IOException) {
                        false
                    }
                }
                if (back && restarting && webViewAlive) {
                    restarting = false
                    when {
                        errorView.isVisible && signInScreen == null -> retry()
                        offlineBar.isVisible -> webView.reload()
                    }
                }
            }
        }
    }

    // --- Offline ------------------------------------------------------------------------------------------------------

    /** With no network: [page], else the last page kept, else home - from the phone, or the offline screen. */
    private fun openOffline(page: String?) {
        if (!webViewAlive) return
        webView.loadUrl(page ?: shell.pages.pages().firstOrNull()?.url ?: home())
    }

    /** The network came or went: the page is told; back online, a page from the phone is loaded fresh. */
    private fun onNetwork(online: Boolean) {
        if (!webViewAlive) return
        webView.setNetworkAvailable(online)
        if (origin.matches(webView.url)) tellNetwork()
        if (!online) return
        forcedOffline = false
        restarting = false
        when {
            errorView.isVisible && signInScreen == null -> retry()
            offlineBar.isVisible -> webView.reload()
        }
    }

    /** `network()`: `{online, offlinePage, savedAt}` - offline, and whether what is on screen came from the phone. */
    private fun networkState(): JSONObject = JSONObject()
        .put("online", !offline)
        .put("offlinePage", shownKeptAt != null)
        .put("savedAt", shownKeptAt ?: JSONObject.NULL)

    /** The page hears it too: the window event `vougadev:network`, with what `network()` answers. */
    private fun tellNetwork() {
        if (!webViewAlive) return
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent(\"vougadev:network\", {detail: ${networkState()}}));", null)
    }

    /** A same-origin GET answered from the phone, when offline: the page or file as it was kept, or null. */
    private fun fromPhone(request: WebResourceRequest): WebResourceResponse? {
        val hit = shell.pages.get(request.url.toString()) ?: return null
        val mime = hit.entry.contentType.substringBefore(';').trim()
        val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(hit.entry.contentType)?.groupValues?.get(1)?.trim()
            ?: if (mime.startsWith("text/") || mime.endsWith("javascript") || mime == "application/json") "utf-8" else null
        if (request.isForMainFrame) servedFromPhone[PageCache.keyOf(request.url.toString())] = hit.entry.savedAt
        return WebResourceResponse(mime, charset, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(hit.body))
    }

    private fun time(atMs: Long): String = android.text.format.DateFormat.getTimeFormat(this).format(Date(atMs))

    /**
     * A page that cannot be shown. With no network: not available offline, and what IS (the pages kept, the
     * extensions' native screens), each one tap away; with a network: it could not be loaded. Retry comes last.
     */
    private fun showUnavailable(url: String) {
        offlineBar.visibility = View.GONE
        offlineList.removeAllViews()
        errorBack.isVisible = webViewAlive && webView.canGoBack()
        val key = PageCache.keyOf(url)
        val pages = shell.pages.pages().filter { it.url != key }.take(MAX_OFFLINE_ROWS)
        val entries = shell.extensions.flatMap { it.offlineEntries(this) }
        if (!offline && restarting) {
            errorTitle.setText(R.string.shell_restarting_title)
            errorText.text = if (pages.isEmpty() && entries.isEmpty()) getString(R.string.shell_restarting_text)
            else getString(R.string.shell_restarting_text) + "\n" + getString(R.string.shell_load_error_list)
        } else if (!offline) {
            errorTitle.setText(R.string.shell_load_error_title)
            errorText.text = if (pages.isEmpty() && entries.isEmpty()) getString(R.string.shell_load_error_text)
            else getString(R.string.shell_load_error_text) + "\n" + getString(R.string.shell_load_error_list)
        } else {
            errorTitle.setText(R.string.shell_offline_title)
            errorText.setText(if (pages.isEmpty() && entries.isEmpty()) R.string.shell_offline_nothing else R.string.shell_offline_text)
        }
        run {
            entries.forEach { entry -> offlineList.addView(offlineRow(entry.title, null) { entry.open(this) }) }
            pages.forEach { page ->
                val title = page.title ?: (PushRoute.pathOf(page.url) ?: page.url)
                offlineList.addView(offlineRow(title, getString(R.string.shell_offline_saved_at, time(page.savedAt))) {
                    errorView.visibility = View.GONE
                    failedUrl = null
                    webView.loadUrl(page.url)
                })
            }
        }
        errorView.visibility = View.VISIBLE
    }

    /** One row of the offline list: a title, a quiet line under it, the whole row tappable. */
    private fun offlineRow(title: String, detail: String?, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = (56 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            isClickable = true
            isFocusable = true
            val ripple = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
        })
        detail?.let {
            row.addView(TextView(this).apply {
                text = it
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
                alpha = 0.7f
            })
        }
        return row
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
            // The account service's answer to the sign-in inside the app: its redirect to the app's own address.
            if (uri.toString().startsWith(session.config.redirectUri)) {
                finishInAppSignIn(uri)
                return true
            }
            val isSite = origin.matches(uri)
            val screen = if (isSite) nativeScreen(uri.path) else null
            val tapped = request.hasGesture() && !request.isRedirect
            return when (LinkPolicy.decide(uri.scheme, uri.host, isSite, screen != null, uri.path, session.config.companyDomains, session.config.outsideHosts, tapped)) {
                LinkPolicy.Decision.LOAD_INSIDE -> false
                LinkPolicy.Decision.SIGN_IN -> {
                    // NEVER THE SITE'S OWN WEB SIGN-IN IN THE APP: the session is renewed, then the sign-in's `next` opens -
                    // or, for a sign-up (`prompt=create`) or a re-authentication (`max_age`), the account service's own.
                    val next = SignInRoute.after(origin, uri.getQueryParameter("next"))
                    val asked = SignInRoute.Asked.of(uri.getQueryParameter("prompt"), uri.getQueryParameter("max_age"))
                    if (asked == null) {
                        renewThen(next)
                    } else {
                        afterSignIn = next
                        startInAppSignIn(asked)
                    }
                    true
                }
                LinkPolicy.Decision.OPEN_NATIVE -> {
                    // Back from the native screen lands on a page of the site: never on the account service's callback that redirected here.
                    if (request.isRedirect || !origin.matches(view.url)) view.loadUrl(origin.baseUrl + "/")
                    startActivity(screen!!)
                    true
                }
                // Another company's login refuses a WebView: the sign-in in the system's surface instead.
                LinkPolicy.Decision.SIGN_IN_ELSEWHERE -> {
                    if (signInRequest != null || !session.signedIn) startBrowserSignIn() else openThirdParty(uri)
                    true
                }
                LinkPolicy.Decision.OPEN_THIRD_PARTY -> {
                    // A step of the account service's sign-in on another site (Telegram's login): inside, with it.
                    if (signInRequest != null && auth.matches(view.url)) return false
                    openThirdParty(uri)
                    true
                }
                LinkPolicy.Decision.OUTSIDE_STEP -> {
                    openOutsideStep(uri, null)
                    true
                }
                LinkPolicy.Decision.OPEN_APP -> {
                    openOutside(uri)
                    true
                }
                // The WebView stays where it was: the page that sent the person to the app redraws itself on return.
                LinkPolicy.Decision.OPEN_IN_ITS_APP -> {
                    openInItsApp(uri)
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
            shell.extensions.firstNotNullOfOrNull { it.interceptRequest(applicationContext, url) }?.let { return it }
            // NO NETWORK: the page or file as the phone kept it; with the network, the files a page uses are noted.
            if (offline || restarting) return fromPhone(request)
            if (!request.isForMainFrame) {
                pageFiles += url.toString()
                return null
            }
            // EACH PAGE FETCHED ONCE: the WebView's answer and the copy kept for offline are the same read.
            val answer = try {
                pageFetch.fetch(url.toString(), request.requestHeaders, CookieManager.getInstance().getCookie(url.toString()), session.userAgent, session.acceptLanguage())
            } catch (_: java.io.IOException) {
                // Android said online, the site never answered: offline now - the page as the phone kept it, else the
                // offline screen with what is kept - never a blank screen while the WebView tries again for minutes.
                forcedOffline = true
                return fromPhone(request) ?: run {
                    val failed = url.toString()
                    runOnUiThread { failedUrl = failed; showUnavailable(failed) }
                    WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            } ?: return null
            val cookies = CookieManager.getInstance()
            answer.setCookies.forEach { cookies.setCookie(url.toString(), it) }
            if (answer.code >= 400) httpStatus[PageCache.keyOf(url.toString())] = answer.code
            return WebResourceResponse(answer.mimeType, answer.charset, answer.code, answer.reason, answer.headers, ByteArrayInputStream(answer.body))
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageFiles.clear()
            // Only the page now starting may still say it came from the phone (a page reports "finished" more than once).
            val key = url?.let(PageCache::keyOf)
            servedFromPhone.keys.retainAll { it == key }
            if (failedUrl == null) errorView.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pull.isRefreshing = false
            if (url == null) return
            // the sign-out's blank answer (shouldInterceptRequest): the app is signing out, never offline
            if (SignInRoute.isSignOutPage(origin.matches(url), url.toUri().path)) return
            val key = PageCache.keyOf(url)
            val keptAt = servedFromPhone[key]
            if (keptAt != null) {
                offlineBar.text = getString(if (!offline && restarting) R.string.shell_restarting_bar else R.string.shell_offline_bar, time(keptAt))
                offlineBar.visibility = View.VISIBLE
            } else {
                offlineBar.visibility = View.GONE
            }
            shownKeptAt = keptAt
            if (origin.matches(url)) tellNetwork()
            val status = httpStatus.remove(key)
            val gateway = status != null && status >= 502
            val own = origin.matches(url)
            when {
                keptAt != null -> if (own) siteFinished(url, keptAt)
                // NEVER A RAW ERROR PAGE: a company page without the company-page meta is an edge's, a proxy's or a CDN's.
                own || trusted.any { it.matches(url) } -> view.evaluateJavascript(PAGE_META_CHECK) { found ->
                    if (found == "false" && failedUrl == null) foreignPage(url, status) else if (own) siteFinished(url, null)
                }
                // Another company page (the account service's) the edge could not serve: never the raw error.
                gateway && failedUrl == null -> foreignPage(url, status)
                own -> siteFinished(url, null)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) httpStatus[PageCache.keyOf(request.url.toString())] = errorResponse.statusCode
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            pull.isRefreshing = false
            val url = request.url.toString()
            failedUrl = url
            val network = error.errorCode in NETWORK_ERRORS
            // Android said online, the network said no: the page as the phone kept it - or, starting, the last one kept.
            val kept = when {
                !network -> null
                shell.pages.savedAt(url) != null -> url
                firstPageOfStart -> shell.pages.pages().firstOrNull()?.url
                else -> null
            }
            if (network) forcedOffline = true
            if (kept != null && !(kept == url && servedFromPhone.containsKey(PageCache.keyOf(url)))) {
                failedUrl = null
                view.loadUrl(kept)
                return
            }
            showUnavailable(url)
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

    /**
     * AN OUTSIDE STEP that refuses WebViews (PayPal, a bank page that will not run inside): Chrome's Auth Tab over the
     * app, watching for the return under [ShellConfig.outsideReturnPrefix] - reached, the tab closes and the return
     * address loads in the WebView. A browser without Auth Tab opens a Custom Tab, and the return comes back as the
     * app's own https link ([pageFrom]). [answer] is a page's `openOutside()` waiting, if any.
     */
    private fun openOutsideStep(url: Uri, answer: ((JSONObject) -> Unit)?) {
        val back = session.config.outsideReturnPrefix?.toUri()
        // No way back into the app: a page asking says so and goes there itself; a link opens in the sheet.
        if (back == null || back.host == null) return answer?.invoke(NativeBridge.refused("failed")) ?: openThirdParty(url)
        outsideAnswer?.invoke(NativeBridge.refused("cancelled"))
        outsideAnswer = answer
        try {
            AuthTabIntent.Builder().build().launch(outsideLauncher, url, back.host!!, back.path ?: "/")
        } catch (_: ActivityNotFoundException) {
            outsideAnswer = null
            answer?.invoke(NativeBridge.refused("failed")) ?: openThirdParty(url)
        }
    }

    private fun onOutsideReturn(result: AuthTabIntent.AuthResult) {
        val answer = outsideAnswer
        outsideAnswer = null
        val prefix = session.config.outsideReturnPrefix
        val back = result.resultUri?.toString()
        if (result.resultCode == AuthTabIntent.RESULT_OK && back != null && prefix != null && back.startsWith(prefix)) {
            answer?.invoke(NativeBridge.ok())
            errorView.visibility = View.GONE
            failedUrl = null
            if (webViewAlive) webView.loadUrl(back)
        } else {
            // RESULT_UNKNOWN_CODE: a Custom Tab stood in for the Auth Tab and was closed before its return came back
            // through the app's own link (which answers ok in [pageFrom]): the person left the step.
            val closed = result.resultCode == AuthTabIntent.RESULT_CANCELED || result.resultCode == AuthTabIntent.RESULT_UNKNOWN_CODE
            answer?.invoke(NativeBridge.refused(if (closed) "cancelled" else "failed"))
        }
    }

    /** A third party's site (a checkout, a map): an in-app browser sheet over the app, in its colours. */
    private fun openThirdParty(uri: Uri) {
        val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(ContextCompat.getColor(this, R.color.shell_signin_background)).build()
        try {
            CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build().launchUrl(this, uri)
        } catch (_: ActivityNotFoundException) {
            openOutside(uri)
        }
    }

    /** A chat app's web address: that app when installed (never a browser for it), else the in-app browser sheet. */
    private fun openInItsApp(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openThirdParty(uri)
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

        /**
         * The camera and the microphone, for a video call on the site's own pages: only when the app declares them in its
         * manifest, after the system's dialog, and only what the person allowed. Anything else, or any other origin, is
         * refused.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = MediaGrant.permissionsFor(request.resources)
            if (wanted == null || !origin.matches(request.origin.toString()) || !wanted.values.all(::declares)) return request.deny()
            requestPermissions(wanted.values.distinct().toTypedArray()) {
                val allowed = wanted.filterValues(::granted).keys
                if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
            }
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            // A form of the site, or nothing: a foreign page has no business with the person's photos.
            if (!origin.matches(view.url)) return false
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val acceptsImages = params.acceptTypes.all { it.isBlank() } || params.acceptTypes.any { it.startsWith("image") }
            // An app that declares CAMERA (for calls) may start the camera app only once it holds the permission.
            val camera = Manifest.permission.CAMERA
            if (acceptsImages && declares(camera) && !granted(camera)) {
                requestPermissions(arrayOf(camera)) { launchChooser(params, withCamera = granted(camera)) }
            } else {
                launchChooser(params, withCamera = acceptsImages)
            }
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

    /** Whether the app's manifest asks for [permission]: the shell never asks what the app did not declare. */
    private fun declares(permission: String): Boolean = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().contains(permission)
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** The camera app, writing into the app's cache (no permission needed unless the app declares CAMERA). */
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

    /**
     * THE ACCOUNT SERVICE'S MEMBER, on its origin only: `nativeSignIn({provider: "google", nonce})` signs in with
     * Google, which refuses a WebView, through Android's Credential Manager: an ID token for the account service's
     * Google client, carrying the page's nonce. Apple is not offered on Android. Without
     * [ShellConfig.googleServerClientId] the member is absent: the page's Google link then leads to the sign-in in a
     * Custom Tab.
     */
    private fun signInMethods(): Map<String, NativeBridge.Method> {
        val clientId = session.config.googleServerClientId ?: return emptyMap()
        return linkedMapOf(
            "nativeSignIn" to NativeBridge.Method { args, answer ->
                // `nativeSignIn("google")` or `nativeSignIn({provider: "google", nonce})`
                when (args?.optString("provider")?.ifEmpty { null } ?: args?.optString("value")) {
                    "google" -> lifecycleScope.launch {
                        val nonce = args?.optString("nonce")?.ifEmpty { null }
                        answer(
                            when (val signed = GoogleSignIn.signIn(this@ShellActivity, clientId, nonce)) {
                                is GoogleSignIn.Result.Signed -> NativeBridge.ok().put("provider", "google").put("idToken", signed.idToken)
                                    .put("email", signed.email ?: JSONObject.NULL).put("name", signed.name ?: JSONObject.NULL)
                                GoogleSignIn.Result.Cancelled -> NativeBridge.refused("cancelled")
                                GoogleSignIn.Result.Failed -> NativeBridge.refused("failed")
                            },
                        )
                    }
                    // Sign in with Apple is native on iOS only.
                    else -> answer(NativeBridge.refused("unavailable"))
                }
            },
        )
    }

    /**
     * `openOutside({url, returnUrl})`, on the payment service's origin: an outside step that refuses WebViews, in an
     * in-app sheet; it answers once the step came back under [ShellConfig.outsideReturnPrefix] (the return then loads
     * in the WebView), or `{ok: false, error: "cancelled" | "failed" | "invalid_url"}`.
     */
    private fun outsideMethods(): Map<String, NativeBridge.Method> = linkedMapOf(
        "openOutside" to NativeBridge.Method { args, answer ->
            val url = args?.optString("url").orEmpty().toUri()
            val back = args?.optString("returnUrl").orEmpty()
            val prefix = session.config.outsideReturnPrefix
            if (url.scheme != "https" || url.host.isNullOrBlank() || prefix == null || !back.startsWith(prefix)) {
                return@Method answer(NativeBridge.refused("invalid_url"))
            }
            openOutsideStep(url, answer)
        },
    )

    /** The core's Promise members, in every app: the consent choices, and the native Settings screen. */
    private fun coreMethods(): Map<String, NativeBridge.Method> = linkedMapOf(
        "consent" to NativeBridge.Method { _, answer -> answer(JSONObject(Consent.choices(this).toMap())) },
        "openSettings" to NativeBridge.Method { _, answer ->
            openSettings()
            answer(NativeBridge.ok())
        },
        "network" to NativeBridge.Method { _, answer -> answer(networkState()) },
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
                // The app's own origin gets the whole bridge; the account service's, only what its sign-in needs.
                val rules = NativeShim.originRules(origin)
                val authRules = NativeShim.originRules(auth)
                val methods = coreMethods() + attachments.flatMap { it.bridgeMethods().entries }.associate { it.key to it.value }
                val bridge = NativeBridge(origin, methods)
                val authMethods = signInMethods()
                val authBridge = NativeBridge(auth, authMethods)
                // Other trusted company origins (the payment service, in its own frames too): the constants and openOutside.
                val trustedRules = trusted.flatMap { NativeShim.originRules(it) }.toSet()
                val trustedBridges = trusted.map { it to NativeBridge(it, outsideMethods()) }
                WebViewCompat.addWebMessageListener(webView, NativeShim.CHANNEL, rules + authRules + trustedRules) { _, message, sourceOrigin, _, replyProxy ->
                    val source = sourceOrigin.toString()
                    val target = when {
                        auth.matches(source) -> authBridge
                        else -> trustedBridges.firstOrNull { it.first.matches(source) }?.second ?: bridge
                    }
                    target.onMessage(message.data, source) { reply -> replyProxy.postMessage(reply) }
                }
                if (trustedRules.isNotEmpty()) {
                    WebViewCompat.addDocumentStartJavaScript(webView, NativeShim.script(session.config.versionName, shell.features, outsideMethods().keys.toList()), trustedRules)
                }
                WebViewCompat.addDocumentStartJavaScript(webView, NativeShim.script(session.config.versionName, shell.features, methods.keys.toList()), rules)
                WebViewCompat.addDocumentStartJavaScript(webView, NativeShim.script(session.config.versionName, emptyList(), authMethods.keys.toList()), authRules)
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
        private const val MAX_OFFLINE_ROWS = 8
        private const val KEY_SIGN_IN_REQUEST = "shell_sign_in_request"
        private const val FORCED_OFFLINE_MS = 30_000L
        private const val RESTART_RETRY_MS = 5_000L

        /** Every company page carries `<meta name="vougadev-page" content="<service>">`; an answer without it is not one. */
        const val PAGE_META = "vougadev-page"
        private const val PAGE_META_CHECK = "document.querySelector('meta[name=\"$PAGE_META\"]') !== null"
        private val NETWORK_ERRORS = setOf(
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO, WebViewClient.ERROR_UNKNOWN,
        )

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

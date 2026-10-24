package com.vougadev.shell

import android.annotation.SuppressLint
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * THE SHELL'S ONE SCREEN (VougaDev Native, README.md): the app's site in a WebView, and the
 * few things a page cannot do on its own - Android's location prompts and the "Turn on location" dialog, the camera
 * app for a photo, maps and phone links handed to their apps, the back press over an open modal, pull-to-refresh, a
 * native offline screen, a crashed renderer rebuilt. An app extends it with its own features: the members of
 * `window.VougaDevNative` ([bridgeMethods]), the native screens a path opens ([nativeScreen]) and the hooks below.
 *
 * SIGN-IN IS NATIVE, IN THE SYSTEM BROWSER (the account service's rule: never a WebView). The app signs in with the account service in a Custom Tab
 * ([BrowserSignIn]), trades the access token for the site's session (`/auth/app-session/`) and puts its cookies in the
 * WebView's jar. When the WebView heads for a sign-in (`/auth/login/`, the account service's pages) the session is renewed silently
 * ([com.vougadev.shell.auth.SessionKeeper]); only a refused refresh shows the sign-in screen, one button. The web's
 * sign-out form is taken over: the app's own [beforeSignOut], the site's session ended, refresh token revoked,
 * everything of the person cleared on the phone - never the VougaDev session.
 *
 * Only the app's origin is ever loaded or granted anything. See [AppOrigin].
 *
 * THE BRIDGE IS `window.VougaDevNative`, the one name every VougaDev app uses (Echo, 2026-10-23): `platform()`,
 * `version()` and the app's [bridgeMethods] ([NativeShim], [NativeBridge]), injected with `addWebMessageListener` and
 * `addDocumentStartJavaScript` restricted to the app's origin, never `addJavascriptInterface`.
 */
abstract class ShellActivity : AppCompatActivity() {

    /** The app's session, one per process: the app keeps the instance. */
    protected abstract val session: AppSession

    protected val origin: AppOrigin by lazy { session.origin }
    private val auth: AppOrigin by lazy { AppOrigin(session.config.authBaseUrl) }
    protected val location = LocationAsk(this)
    private lateinit var webView: WebView
    private lateinit var pull: SwipeRefreshLayout
    private lateinit var errorView: View

    // --- What the app adds -----------------------------------------------------------------------------------------

    /** The members of `window.VougaDevNative` beyond `platform()` and `version()`, in the order the contract lists them. */
    protected open fun bridgeMethods(): Map<String, NativeBridge.Method> = emptyMap()

    /**
     * The native screen a same-origin [path] (no query) opens instead of loading it, or null to load it in the WebView.
     * Fixed in the binary (Apple 2.5.2): the server never chooses what is native.
     */
    protected open fun nativeScreen(path: String?): Intent? = null

    /** A page of the site finished loading with the session open: what the app does once signed in (push, follow-ups). */
    protected open fun onSignedInPage() = Unit

    /** A person just signed in with the browser (not a silent renewal). */
    protected open fun afterNewSignIn() = Unit

    /** The web's sign-out is starting: stop what works for this person (a service, a share). */
    protected open fun beforeSignOut() = Unit

    /** What the app keeps of a person goes: never signed in with the app, or the account service refused the refresh. */
    protected open fun forgetLocalData() = Unit

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

    // --- File chooser (package photos) ------------------------------------------------------------------------------

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
        val asked = if (savedInstanceState == null) pageFrom(intent) else null
        // THE FIRST LOAD WAITS FOR THE WINDOW (PIVO, measured on WP35): a renderer requested before the WebView is
        // attached was refused by the system, and the page stayed black until a reboot.
        firstLoad = { boot(asked, savedInstanceState) }
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

    /** The site's session is in the jar: the page asked for (or home), and the app's [afterNewSignIn]. */
    private fun sessionOpen(newSignIn: Boolean) {
        hideSignIn()
        val target = afterSignIn ?: home()
        afterSignIn = null
        if (newSignIn) {
            // another person may have used the phone before: no way back to their pages
            webView.clearHistory()
            afterNewSignIn()
        }
        if (webViewAlive) open(target)
    }

    /**
     * THE WEB'S SIGN-OUT, TAKEN OVER: the app's [beforeSignOut], then [AppSession.signOut] (the site's session ended
     * while it still exists, the refresh token revoked at the account service, the tokens and cookies cleared), and the web storage. The VougaDev
     * session is not ended (no end_session): the person's other applications stay signed in.
     */
    private fun signOut() {
        if (signingOut) return
        signingOut = true
        show(SignInScreen.SIGNING_OUT)
        beforeSignOut()
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
            afterSignIn = null
            show(SignInScreen.SIGN_IN)
        }
    }

    /** Never signed in with the app: an earlier web session's cookies and data are not this app's. */
    private fun forgetOnPhone() {
        session.cookies.clear()
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
            return when (LinkPolicy.decide(uri.scheme, isSite, auth.matches(uri), screen != null, uri.path)) {
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
         * signs out itself, in its own order ([signOut]). Called on a WebView thread.
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame || !SignInRoute.isSignOut(request.method, origin.matches(request.url), request.url.path)) return null
            runOnUiThread { signOut() }
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))
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
                val methods = bridgeMethods()
                val bridge = NativeBridge(origin, methods)
                WebViewCompat.addWebMessageListener(webView, NativeShim.CHANNEL, rules) { _, message, sourceOrigin, _, replyProxy ->
                    bridge.onMessage(message.data, sourceOrigin.toString()) { reply -> replyProxy.postMessage(reply) }
                }
                WebViewCompat.addDocumentStartJavaScript(webView, NativeShim.script(session.config.versionName, methods.keys.toList()), rules)
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

        /** [activity] is the app's own subclass. */
        fun openPage(context: Context, activity: Class<out ShellActivity>, path: String?): Intent = Intent(context, activity)
            .setAction(ACTION_OPEN_PAGE)
            .putExtra(EXTRA_PAGE_PATH, path)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** The sign-in, then the same-origin [nextPath]. */
        fun signIn(context: Context, activity: Class<out ShellActivity>, nextPath: String): Intent = Intent(context, activity)
            .setAction(ACTION_SIGN_IN)
            .putExtra(EXTRA_PAGE_PATH, nextPath)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** [AppOrigin.matches] for an Android URI, without a round trip through a string. */
private fun AppOrigin.matches(uri: Uri?): Boolean = uri != null && matchesParts(uri.scheme, uri.host, uri.port)

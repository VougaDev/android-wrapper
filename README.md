# VougaDev Native for Android

The native shell every VougaDev web app (Ship, PIVO, Jobs, Civis, Care…) ships as its own store app: the product's site
in a WebView, a **core** every app shares, and the **extensions** each app turns on - the native features its site
cannot have. Each app gives its origin, name, icon, colours, account-service client and extensions.

| Module | What it is |
|---|---|
| `shell` | the core: one WebView for the company's domains, sign-in inside the app, the link policy, the offline screen, notifications, the native Settings screen, native consent, the bridge base and the extension API |
| `tracking` | an extension: the position shared while a task is under way, from the background too, after a disclosure |
| `offline-map` | an extension: map regions kept on the phone, answering the site's map pages and native maps with no network |

## The contract: `window.VougaDevNative`

The same on Android and iOS; a page branches on the platform only through `platform()`.

**The core**, in every app:

| Member | Result |
|---|---|
| `platform()` | `"android"` or `"ios"`, synchronous |
| `version()` | the app's version name, synchronous |
| `features()` | the extensions this app has, e.g. `["tracking", "offline-map"]`, synchronous |
| `consent()` | `Promise<{<purpose>: bool}>`: the choices made in the native consent dialog; `{}` when the app asks none |
| `openSettings()` | `Promise<{ok: true}>`: the app's native Settings screen |

**The `tracking` extension:** `startTracking({missionId, url?, intervalSeconds?, target?, title?, text?})`,
`stopTracking()`, `trackingState()`. **`offline-map`** adds no member: the page's own tile requests are answered from
the phone. An app's own extensions add theirs. An extension the app does not have adds no member and no name.

- A page tests for a feature: `window.VougaDevNative && VougaDevNative.features().includes("tracking")`.
- Injected into the app's own origin with every member; into the account service's with `nativeSignIn` only
  (when the app has the account service's Google client); into each `trustedOrigins` origin (frames included) with `platform`, `version`, `features` and
  `openOutside` only; never into any other origin, never through `addJavascriptInterface`
  (`addWebMessageListener` + `addDocumentStartJavaScript`).
- Every Promise resolves, never rejects. The object is frozen.
- Generic error codes: `bridge_unavailable`, `invalid_request`, `origin_refused`, `unknown_method`.
- The user agent ends with ` <App>App/<version> (Android)`.

## What the core does

- **One app, one WebView.** The app's site, the company's other domains and subdomains (`companyDomains`) and the
  account service's screens load in the same WebView, with the native back; never the browser. Only third parties
  leave: their site in an in-app browser sheet, phone, e-mail, maps and chat in the phone's apps.
- **Sign-in inside the app.** No screen of the app's own before it: signed out, the app loads AppAuth's request (code,
  PKCE S256, `ui_locales`) straight into the WebView - the account service's sign-in, sign-up, account chooser and
  first-sign-in steps, and a third party's step of it (Telegram's), all inside; its redirect to `<applicationId>:/oauth2redirect` is caught there, the state checked and the code
  exchanged. The site's own sign-in (`/auth/login/`) never loads in the app: a link to it renews the session, or, when
  it carries `prompt=create` (sign-up), `prompt=login|select_account` or `max_age`, starts the account service's with
  them, keeping `next`. Google signs in natively (`nativeSignIn({provider: "google", nonce})`, Credential Manager, an
  ID token for the account service's Google client `googleServerClientId`); passkeys through the WebView's WebAuthn.
  Without `googleServerClientId`, Google's sign-in falls back to a Custom Tab. With `signInFirst = false`, a person
  without an account uses the site's public pages and signs in from the site's own link (sign-out returns to the home).
  The phone's password manager (autofill) works on the account service's pages only: on the app's own pages it offered
  saved logins under any lone text field, whatever its `autocomplete` (address and phone autofill are off there too).
- **Payment stays in the WebView.** The company's checkout is a company domain; a bank's or operator's step met on
  the way stays inside while it works there. Only a step that refuses WebViews (`outsideHosts`, or the checkout's
  `openOutside({url, returnUrl})` on a `trustedOrigins` origin) goes to Chrome's Auth Tab and comes back by itself
  under `outsideReturnPrefix`, loading in the WebView.
- **Never a raw error page.** Every company page carries `<meta name="vougadev-page" content="<service>">` in its head.
  An answer of the app's or a `trustedOrigins` origin is the edge's only when it is a 5xx without that meta (a 502, 503
  or 504 from the edge): never shown. A 2xx, 3xx or 4xx is the site's own answer - a CV, an invoice, a ticket, a PDF, a
  printable sheet - shown as it is, meta or not. Offline only when the phone has no validated network; else the edge answered, so the
  service is restarting (a release): the copy kept on the phone under "Service restarting", or the native screen saying
  so, and the shell asks the site again every 5 s and loads the page as soon as it answers.
- **An app's words**: no page, site, web, browser, link, URL, reload or tab in any native text (en, fr, es).

- **Notifications.** Asked right after sign-in, once, with the app's own sentence (`shell_notifications_reason`), then
  the system's dialog; a refusal blocks nothing, and Settings turns them on later. On only when the app is built with
  its Firebase file (never in this repository) and gives the site's push-devices address: the token goes to the site,
  which hands it to the notification hub.
- **One hub sends every app's notifications.** A push says which app it belongs to and what it opens:
  `{app, url, screen?, topic, id, title, body}`. This app's page opens in the app, or the native screen an extension
  owns for `screen`; another app's notification or another site's address opens outside (that app if installed, else
  the browser); anything unusable opens the app's home. Never a blank screen.
- **Settings**, reached from the page's "Privacy and data" (`openSettings()`), never from the main menu: notifications
  (this phone, and what the person receives), the extensions' rows, the privacy policy, consent choices, account
  deletion, sign-out (confirmed), the version.
- **Consent**, when the app declares purposes: a native dialog asked once, never the website's banner; the choice is
  answered by `consent()` and set as the first-party cookie `vougadev_consent` (`analytics:1|ads:0`), from which the
  site hides its own banner.
- **Camera and microphone** (a video call over WebRTC on the site's pages): granted to the app's own origin only when
  the app declares `CAMERA` / `RECORD_AUDIO` in its manifest, after the system's dialog; refused otherwise.
- **A page answered `Cache-Control: no-store`** (a medical file, a prescription) is shown but never kept for offline.
- **No install prompt** anywhere; a site hides its own when `VougaDevNative` is present.
- **Offline, never a dead end.** The pages the person visited and their same-origin files are kept on the phone (a size
  cap, cleared on sign-out) and served with no network under a quiet "Offline · updated at HH:MM" bar. The app starts
  offline on the last page kept; a page never visited shows what IS available (the pages kept - each named by its
  `<meta name="vougadev-screen" content="…">`, else by its title without " · <App>" - the extensions'
  offline screens), never a lone "retry"; a first start offline shows the app's welcome. `navigator.onLine` and the
  `online`/`offline` events follow the network, so the site's actions wait for it.

## Using it in an app

1. Add this repository as a git submodule (pinned to a commit) and include its build, with the same Android Gradle
   plugin version as `gradle/libs.versions.toml` here:
   ```kotlin
   // settings.gradle.kts
   includeBuild("wrapper")
   // app/build.gradle.kts
   plugins {
       alias(libs.plugins.android.application)
       alias(libs.plugins.google.services) apply false   // same entry as this repository's catalog
   }
   // Push: on as soon as the app's Firebase file is there (git-ignored, never in this repository).
   if (file("google-services.json").isFile) apply(plugin = "com.google.gms.google-services")
   dependencies {
       implementation("com.vougadev:shell")
       implementation("com.vougadev:tracking")      // the extensions the app turns on
   }
   ```
2. Its `Application` implements `ShellApplication` and builds the `Shell`: the app's `ShellConfig` (from its
   BuildConfig: its slug at the hub, site, the account service and its own public client id, redirect
   `<applicationId>:/oauth2redirect`, user-agent token, a `storeName` never changed once shipped, the account service's
   sign-in paths, the privacy and account-deletion pages, the hub's notifications page, the push-devices address,
   consent purposes) and its extensions, e.g. `TrackingExtension(TrackingConfig(...))`. Nothing app-specific lives in
   this repository.
3. Its launcher is an empty subclass of `ShellActivity` (its component name stays its own), with
   `manifestPlaceholders["appAuthRedirectScheme"] = applicationId`.
4. Its own features are extensions too (`Extension`: its feature name, its bridge members, the native screen a path or
   a notification opens, what it keeps of the person, its Settings rows).
5. Brand it with resources of the same names: `app_name`, the colours `shell_signin_background`, `shell_signin_title`,
   `shell_signin_muted`, `shell_accent`, `shell_on_accent`, `shell_pull`, the drawables `shell_signin_mark` and
   `shell_notification_icon`, the string `shell_notifications_reason` and the extensions' texts
   (each language), and `xml/shell_file_paths` if the app writes photos elsewhere too.
6. **Push notifications (required in every app):** add the app's package to VougaDev's Firebase project (Android
   app, the release and debug SHA-1), put its `google-services.json` in `app/` (git-ignored) and set `pushDevicesPath`.
   The shell does the rest: the permission after sign-in, the FCM token reported to the site
   (`PUT <pushDevicesPath>` `{kind: "fcm", token, label, app, platform: "android", locale}`, `POST …/forget` on sign-out
   or when notifications are turned off), and a data-only message `{app, url, screen?, topic, id, title, body}` drawn
   as a notification that opens its screen inside the app. VougaDev's notification service sends them.
7. The site answers `POST /auth/app-session/` (bearer token in, session cookie out), takes the push token, reads the
   user agent, calls `openSettings()` from "Privacy and data", and shows no install prompt and no cookie banner in the
   app.

## Testing on a phone

- **Close the app the way a person or the system does, never with `adb shell am force-stop`.** Press Home, then
  `adb shell am kill <applicationId>` (what Android does to a background app, and what swiping it away does). On some
  phones (the WP35, MediaTek, proved 2026-10-26) a force-stop while the WebView runs makes Android refuse that app's
  renderer from then on: logcat says `Unable to launch app … SandboxedProcessService…: process is bad` and
  `cr_ChildProcessConn: Failed to establish the service connection`, and the app shows a black screen, online or
  offline, with any build, until the phone reboots. That black screen is the test's doing, not the shell's.
- **Offline cold start:** after a signed-in visit, Home, `am kill`, then airplane mode with Wi-Fi and data off
  (`adb shell cmd connectivity airplane-mode enable`, `svc wifi disable`, `svc data disable`), then launch: the pages
  kept open under the offline bar. Put the network back as it was before handing a shared phone on.
- **The WebView's own tools:** `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` (debug builds), then
  the DevTools protocol on `localhost:9222`.
- adb lists no device while `lsusb` shows the phone: restart adb's server (`adb kill-server; adb start-server`).

## Rules every app built on it keeps

- **At least one real native extension** that matters to its users' job, never a plain website loader (App Store
  guideline 4.2): background location for a task, offline maps or data, a scanner, native screens for the core flow.
- **Sign-in inside the app:** the account service's screens in the WebView, ended by its redirect to the app (PKCE);
  Google and Apple natively (`nativeSignIn`); a Custom Tab (`ASWebAuthenticationSession` on iOS) only as the fallback;
  never the site's own web sign-in.
- **No other platform named inside the app.** No self-update outside the store. Privacy policy and account deletion
  in Settings. A disclosure before background location. Notifications asked after sign-in, with a reason.

## Secrets

Nothing secret is ever committed here: `google-services.json`, signing keys, service accounts and `.env` files are
ignored, and each app keeps its own outside git. The CI needs none.

## License

MIT, see [LICENSE](LICENSE).

# VougaDev Native for Android

The native shell every VougaDev web app (Ship, PIVO, Jobs, Civis, Care…) ships as its own store app: the product's site
in a WebView, a **core** every app shares, and the **extensions** each app turns on - the native features its site
cannot have. Each app gives its origin, name, icon, colours, account-service client and extensions.

| Module | What it is |
|---|---|
| `shell` | the core: the WebView on one origin, sign-in in the system's sign-in surface, the link policy, the offline screen, notifications, the native Settings screen, native consent, the bridge base and the extension API |
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
- Injected only into the app's own origin (`addWebMessageListener` + `addDocumentStartJavaScript`), never into another
  origin's frame, never through `addJavascriptInterface`.
- Every Promise resolves, never rejects. The object is frozen.
- Generic error codes: `bridge_unavailable`, `invalid_request`, `origin_refused`, `unknown_method`.
- The user agent ends with ` <App>App/<version> (Android)`.

## What the core does

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
- **No install prompt** anywhere; a site hides its own when `VougaDevNative` is present.
- **Offline, never a dead end.** The pages the person visited and their same-origin files are kept on the phone (a size
  cap, cleared on sign-out) and served with no network under a quiet "Offline · updated at HH:MM" bar. The app starts
  offline on the last page kept; a page never visited shows what IS available (the pages kept, the extensions'
  offline screens), never a lone "retry"; a first start offline shows the app's welcome. `navigator.onLine` and the
  `online`/`offline` events follow the network, so the site's actions wait for it.

## Using it in an app

1. Add this repository as a git submodule (pinned to a commit) and include its build, with the same Android Gradle
   plugin version as `gradle/libs.versions.toml` here:
   ```kotlin
   // settings.gradle.kts
   includeBuild("wrapper")
   // app/build.gradle.kts
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
   `shell_notification_icon`, the strings `shell_signin_text` and `shell_notifications_reason` and the extensions' texts
   (each language), and `xml/shell_file_paths` if the app writes photos elsewhere too.
6. The site answers `POST /auth/app-session/` (bearer token in, session cookie out), takes the push token, reads the
   user agent, calls `openSettings()` from "Privacy and data", and shows no install prompt and no cookie banner in the
   app.

## Rules every app built on it keeps

- **At least one real native extension** that matters to its users' job, never a plain website loader (App Store
  guideline 4.2): background location for a task, offline maps or data, a scanner, native screens for the core flow.
- **Sign-in in the system's sign-in surface, never inside the WebView:** AppAuth with a Custom Tab on Android,
  `ASWebAuthenticationSession` on iOS.
- **No other platform named inside the app.** No self-update outside the store. Privacy policy and account deletion
  in Settings. A disclosure before background location. Notifications asked after sign-in, with a reason.

## Secrets

Nothing secret is ever committed here: `google-services.json`, signing keys, service accounts and `.env` files are
ignored, and each app keeps its own outside git. The CI needs none.

## License

MIT, see [LICENSE](LICENSE).

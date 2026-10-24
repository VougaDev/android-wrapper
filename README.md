# VougaDev Native for Android

The native shell every VougaDev web app (Ship, PIVO, Jobs, Civis, Care…) ships as its own store app: the product's site
in a WebView, plus native features the site cannot have. Each app configures its origin, name, icon, colours, VougaDev
account (the account service) client and the native features it enables.

## The contract: `window.VougaDevNative`

The same on Android and iOS; a page branches on the platform only through `platform()`. Every app has the shell's two
members; the others are the members of the features that app turns on (`ShellActivity.bridgeMethods()`), and a page
tests for one with `typeof window.VougaDevNative.<name> === "function"`.

| Member | Result |
|---|---|
| `platform()` | `"android"` or `"ios"`, synchronous |
| `version()` | the app's version name, synchronous |
| `<feature member>(args)` | `Promise<{ok: true, ...} \| {ok: false, error}>` |

Ship's members, for example: `startTracking(…)`, `stopTracking()`, `trackingState()`, `openMissions({url?})`.

- Injected only into the app's own origin (`addWebMessageListener` + `addDocumentStartJavaScript`), never into another
  origin's frame, never through `addJavascriptInterface`.
- Every Promise resolves, never rejects. The object is frozen.
- Generic error codes: `bridge_unavailable`, `invalid_request`, `origin_refused`, `unknown_method`.
- The user agent ends with ` <App>App/<version> (Android)`.

## Using it in an app

1. Add this repository as a git submodule (pinned to a commit) and include its build, with the same Android Gradle
   plugin version as `gradle/libs.versions.toml` here:
   ```kotlin
   // settings.gradle.kts
   includeBuild("wrapper")
   // app/build.gradle.kts
   dependencies { implementation("com.vougadev:shell") }
   ```
2. Give the app's configuration (`ShellConfig`: site, the account service, its own public client id, redirect
   `<applicationId>:/oauth2redirect`, user-agent token, a `storeName` never changed once shipped, the account service's sign-in paths
   `authSignInPaths` that the WebView hands to the app's own sign-in), from the app's
   BuildConfig. Nothing app-specific lives in this repository.
3. Subclass `AppSession` (one per process) and `ShellActivity` (`bridgeMethods()`, `nativeScreen(path)`, the hooks), and
   declare that activity as the launcher, with `manifestPlaceholders["appAuthRedirectScheme"] = applicationId`.
4. Brand it with resources of the same names: `app_name`, the colours `shell_signin_background`, `shell_signin_title`,
   `shell_signin_muted`, `shell_accent`, `shell_on_accent`, `shell_pull`, the drawable `shell_signin_mark`, the string
   `shell_signin_text` (each language), and `xml/shell_file_paths` if the app writes photos elsewhere too.
5. The site itself answers `POST /auth/app-session/` (bearer token in, session cookie out) and reads the user agent.

## Rules every app built on it keeps

- **Sign-in in the system's sign-in surface, never inside the WebView:** AppAuth with a Custom Tab on Android,
  `ASWebAuthenticationSession` on iOS.
- **Real native features**, never a plain website loader (App Store guideline 4.2). Examples: background location for a
  mission, offline data, the camera scanner, push notifications.
- **No other platform named inside the app.** No self-update outside the store. Privacy policy and account deletion
  reachable inside the app. A disclosure before background location.

## Secrets

Nothing secret is ever committed here: `google-services.json`, signing keys, service accounts and `.env` files are
ignored, and each app keeps its own outside git. The CI needs none.

## License

MIT, see [LICENSE](LICENSE).

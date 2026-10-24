package com.vougadev.shell.web

import org.json.JSONObject

/**
 * `window.VougaDevNative`, the one native interface a page sees - the name and shape every VougaDev app keeps, and an
 * iOS app injects too, which is why every call that reaches the phone answers with a Promise (a WKWebView message
 * handler is asynchronous). The core's members, in every app:
 *
 * ```
 * VougaDevNative.platform()            -> "android"                  (sync, a constant)
 * VougaDevNative.version()             -> "1.0.0"                    (sync, the app's versionName)
 * VougaDevNative.features()            -> ["tracking", ...]          (sync, the extensions this app has)
 * VougaDevNative.consent()             -> Promise<{<purpose>: bool}>
 * VougaDevNative.openSettings()        -> Promise<{ok: true}>
 * ```
 *
 * The others are the [methods] of the app's extensions (the `tracking` extension: `startTracking`, `stopTracking`,
 * `trackingState`; README.md), each `name(args) -> Promise<{ok: true, ...} | {ok: false, error}>`. A page tests for a
 * feature with `VougaDevNative.features().includes("tracking")`; outside the app `window.VougaDevNative` is absent. A
 * Promise always resolves, never rejects. The object is frozen and cannot be replaced.
 *
 * Two WebView features carry it, BOTH restricted to [originRules]: a document-start script (this [script]) that defines
 * `window.VougaDevNative`, and a web message listener that injects the private [CHANNEL] object the script talks
 * through. A cross-origin frame inside a page of the site - or any page that is not the site's - gets neither. Each call
 * is one JSON message `{"id": n, "method": "...", "args": {...} | null}`; Kotlin answers `{"id": n, "result": {...}}` on
 * the same frame ([NativeBridge]).
 *
 * Pure Kotlin: the text is generated here and unit-tested on the JVM.
 */
object NativeShim {

    /** The page-facing object. */
    const val NAME = "VougaDevNative"

    /** The web message listener's object; only the shim uses it. */
    const val CHANNEL = "__vougadevNativeChannel"

    const val PLATFORM = "android"

    /** What the Promise resolves with when the channel is missing or the argument cannot be sent. */
    const val ERROR_UNAVAILABLE = "bridge_unavailable"
    const val ERROR_INVALID_REQUEST = "invalid_request"

    /** The synchronous members every app has, before the Promise ones. */
    val BUILT_IN = listOf("platform", "version", "features")

    private val METHOD_NAME = Regex("^[a-z][A-Za-z0-9]*$")
    private val FEATURE_NAME = Regex("^[a-z][a-z0-9-]*$")

    /**
     * The allowed-origin rule for both the script and the listener: exactly the site's origin, `scheme://host[:port]` with
     * the port only when it is not the scheme's default. A rule matches scheme, host and port, never a path, and this
     * one has no wildcard.
     */
    fun originRules(origin: AppOrigin): Set<String> = setOf(origin.originString)

    /**
     * The document-start script. [version] is the app's versionName, written as a JSON string literal so it cannot break
     * out of it; [features] are the extensions' names; [methods] are the Promise members (the core's, then the
     * extensions'), plain identifiers only.
     */
    fun script(version: String, features: List<String>, methods: List<String>): String {
        require(methods.all { METHOD_NAME.matches(it) } && methods.none { it in BUILT_IN } && methods.distinct() == methods) {
            "Bridge members must be distinct identifiers other than ${BUILT_IN}: $methods"
        }
        require(features.all { FEATURE_NAME.matches(it) }) { "Feature names: $features" }
        val featureList = org.json.JSONArray(features).toString()
        val platform = JSONObject.quote(PLATFORM)
        val versionLiteral = JSONObject.quote(version)
        val unavailable = JSONObject.quote(ERROR_UNAVAILABLE)
        val invalid = JSONObject.quote(ERROR_INVALID_REQUEST)
        val members = methods.joinToString("") { ",\n    $it: function (args) { return call(\"$it\", args); }" }
        return """
(function () {
  "use strict";
  if (window.$NAME) { return; }
  var pending = {};
  var nextId = 1;
  var bound = null;
  function onReply(event) {
    var reply;
    try { reply = JSON.parse(event.data); } catch (e) { return; }
    if (!reply || typeof reply.id !== "number" || !pending[reply.id]) { return; }
    var resolve = pending[reply.id];
    delete pending[reply.id];
    resolve(reply.result);
  }
  function channel() {
    var c = window.$CHANNEL;
    if (!c || typeof c.postMessage !== "function") { return null; }
    if (bound !== c) {
      bound = c;
      if (typeof c.addEventListener === "function") { c.addEventListener("message", onReply); }
      else { c.onmessage = onReply; }
    }
    return c;
  }
  function call(method, args) {
    return new Promise(function (resolve) {
      var c = channel();
      if (!c) { resolve({ ok: false, error: $unavailable }); return; }
      var id = nextId++;
      var text;
      // A bare string (`nativeSignIn("google")`) travels as {value}.
      var sent = args === undefined ? null : (typeof args === "string" ? { value: args } : args);
      try { text = JSON.stringify({ id: id, method: method, args: sent }); }
      catch (e) { resolve({ ok: false, error: $invalid }); return; }
      pending[id] = resolve;
      try { c.postMessage(text); }
      catch (e) { delete pending[id]; resolve({ ok: false, error: $unavailable }); }
    });
  }
  var api = Object.freeze({
    platform: function () { return $platform; },
    version: function () { return $versionLiteral; },
    features: function () { return $featureList; }$members
  });
  Object.defineProperty(window, "$NAME", { value: api, writable: false, enumerable: false, configurable: false });
})();
""".trimStart()
    }
}

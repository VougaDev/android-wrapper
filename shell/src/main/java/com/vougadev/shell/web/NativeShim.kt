package com.vougadev.shell.web

import org.json.JSONObject

/**
 * `window.VougaDevNative`, the one native interface a page sees - the name and shape every VougaDev app keeps, and an
 * iOS app injects too, which is why every call that reaches the phone answers with a Promise (a WKWebView message
 * handler is asynchronous). Two members are the shell's and every app has them:
 *
 * ```
 * VougaDevNative.platform()            -> "android"                  (sync, a constant)
 * VougaDevNative.version()             -> "1.0.0"                    (sync, the app's versionName)
 * ```
 *
 * The others are the [methods] of the app's own features (Ship: `startTracking`, `stopTracking`, `trackingState`,
 * `openMissions`; docs/integration/native-bridge.md), each `name(args) -> Promise<{ok: true, ...} | {ok: false, error}>`.
 * A page tests for a member with `typeof window.VougaDevNative.<name> === "function"`; absent, it runs in a plain
 * browser, or in an app without that feature. A Promise always resolves, never rejects. The object is frozen and cannot
 * be replaced.
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

    /** The members every app has, before its own. */
    val BUILT_IN = listOf("platform", "version")

    private val METHOD_NAME = Regex("^[a-z][A-Za-z0-9]*$")

    /**
     * The allowed-origin rule for both the script and the listener: exactly the site's origin, `scheme://host[:port]` with
     * the port only when it is not the scheme's default. A rule matches scheme, host and port, never a path, and this
     * one has no wildcard.
     */
    fun originRules(origin: AppOrigin): Set<String> = setOf(origin.originString)

    /**
     * The document-start script. [version] is the app's versionName, written as a JSON string literal so it cannot break
     * out of it; [methods] are the app's members, in the order its contract lists them (plain identifiers only).
     */
    fun script(version: String, methods: List<String>): String {
        require(methods.all { METHOD_NAME.matches(it) } && methods.none { it in BUILT_IN } && methods.distinct() == methods) {
            "Bridge members must be distinct identifiers other than ${BUILT_IN}: $methods"
        }
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
      try { text = JSON.stringify({ id: id, method: method, args: args === undefined ? null : args }); }
      catch (e) { resolve({ ok: false, error: $invalid }); return; }
      pending[id] = resolve;
      try { c.postMessage(text); }
      catch (e) { delete pending[id]; resolve({ ok: false, error: $unavailable }); }
    });
  }
  var api = Object.freeze({
    platform: function () { return $platform; },
    version: function () { return $versionLiteral; }$members
  });
  Object.defineProperty(window, "$NAME", { value: api, writable: false, enumerable: false, configurable: false });
})();
""".trimStart()
    }
}

package com.vougadev.shell.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShimTest {

    private val script = NativeShim.script("1.0.0", listOf("startTracking", "openMissions"))

    @Test
    fun `the script defines window VougaDevNative with the built-in members, then the app's, in order`() {
        assertTrue(script.contains("Object.defineProperty(window, \"VougaDevNative\""))
        val declared = Regex("""^\s{4}(\w+): function""", RegexOption.MULTILINE).findAll(script).map { it.groupValues[1] }.toList()
        assertEquals(listOf("platform", "version", "startTracking", "openMissions"), declared)
        val bare = Regex("""^\s{4}(\w+): function""", RegexOption.MULTILINE).findAll(NativeShim.script("1.0.0", emptyList())).map { it.groupValues[1] }.toList()
        assertEquals(NativeShim.BUILT_IN, bare)
    }

    @Test
    fun `platform and version are constants written into the text`() {
        assertTrue(script.contains("""platform: function () { return "android"; }"""))
        assertTrue(script.contains("""version: function () { return "1.0.0"; }"""))
    }

    @Test
    fun `the app's members go through the private channel and answer with a Promise`() {
        assertTrue(script.contains("""startTracking: function (args) { return call("startTracking", args); }"""))
        assertTrue(script.contains("""openMissions: function (args) { return call("openMissions", args); }"""))
        assertTrue(script.contains("args === undefined ? null : args"))
        assertTrue(script.contains("return new Promise("))
        assertTrue(script.contains("window.__vougadevNativeChannel"))
    }

    @Test
    fun `a member name cannot be code, a built-in or twice`() {
        listOf(listOf("a(){}; x"), listOf("platform"), listOf("version"), listOf("Start"), listOf("go", "go"), listOf("")).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeShim.script("1.0.0", it) }
        }
    }

    @Test
    fun `the script names no other bridge`() {
        // "android" appears once, as the platform() value; never as an object the page reaches.
        assertFalse(script.replace("\"android\"", "").contains("android", ignoreCase = true))
        assertFalse(script.contains("addJavascriptInterface"))
    }

    @Test
    fun `a version cannot break out of its string literal`() {
        val hostile = NativeShim.script("1\"; window.x = 1; \"", emptyList())
        assertTrue(hostile.contains("""return "1\"; window.x = 1; \"";"""))
    }

    @Test
    fun `the origin rule is exactly the site's origin`() {
        assertEquals(setOf("https://app.example.org"), NativeShim.originRules(AppOrigin("https://app.example.org/")))
        assertEquals(setOf("http://10.0.2.2:8080"), NativeShim.originRules(AppOrigin("http://10.0.2.2:8080/")))
        assertEquals(setOf("https://app.example.org"), NativeShim.originRules(AppOrigin("https://app.example.org:443")))
        NativeShim.originRules(AppOrigin("https://app.example.org")).forEach {
            assertFalse(it.contains("*"))
            assertFalse(it.endsWith("/"))
        }
    }
}

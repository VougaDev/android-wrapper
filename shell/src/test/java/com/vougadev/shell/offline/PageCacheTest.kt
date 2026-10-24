package com.vougadev.shell.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The pages and files kept for offline: stored, read back, listed, capped, cleared. */
class PageCacheTest {

    private val dir = Files.createTempDirectory("pages").toFile()
    private var now = 1_000L
    private fun cache(cap: Long = 1_000) = PageCache(dir, cap) { now }

    @Test
    fun `a page and its files come back as they were, without the fragment`() {
        val cache = cache()
        cache.put("https://app.example.org/orders/?tab=1", "text/html; charset=utf-8", "<p>x</p>".toByteArray(), isPage = true, title = "Orders")
        cache.put("https://app.example.org/static/a.css", "text/css", "b{}".toByteArray(), isPage = false)
        val hit = cache.get("https://app.example.org/orders/?tab=1#top")!!
        assertArrayEquals("<p>x</p>".toByteArray(), hit.body)
        assertEquals("text/html; charset=utf-8", hit.entry.contentType)
        assertEquals(1_000L, hit.entry.savedAt)
        assertNull(cache.get("https://app.example.org/orders/?tab=2"))
        assertEquals(listOf("Orders"), cache.pages().map { it.title })
    }

    @Test
    fun `what was kept survives the app (the index is on disk)`() {
        cache().put("https://app.example.org/", "text/html", "home".toByteArray(), isPage = true, title = " Home ")
        val again = cache()
        assertEquals("home", String(again.get("https://app.example.org/")!!.body))
        assertEquals("Home", again.pages().single().title)
    }

    @Test
    fun `beyond the cap the least recently used go first`() {
        val cache = cache(cap = 10)
        cache.put("https://a/1", "text/html", ByteArray(4), isPage = true)
        now = 2_000
        cache.put("https://a/2", "text/html", ByteArray(4), isPage = true)
        now = 3_000
        cache.get("https://a/1") // used again: now the newest
        now = 4_000
        cache.put("https://a/3", "text/html", ByteArray(4), isPage = true)
        assertNull(cache.get("https://a/2"))
        assertTrue(cache.get("https://a/1") != null && cache.get("https://a/3") != null)
        assertTrue(cache.totalBytes() <= 10)
    }

    @Test
    fun `the most recent page is listed first, and everything goes on clear`() {
        val cache = cache()
        cache.put("https://a/old", "text/html", ByteArray(1), isPage = true, title = "Old")
        now = 5_000
        cache.put("https://a/new", "text/html", ByteArray(1), isPage = true, title = "New")
        cache.put("https://a/app.js", "text/javascript", ByteArray(1), isPage = false)
        assertEquals(listOf("New", "Old"), cache.pages().map { it.title })
        cache.clear()
        assertTrue(cache.pages().isEmpty())
        assertNull(cache.get("https://a/app.js"))
        assertTrue(cache().pages().isEmpty())
    }

    @Test
    fun `only what a page needs again is kept, never the sign-in`() {
        assertTrue(PageCache.cacheable("https://a/orders/", "text/html; charset=utf-8"))
        assertTrue(PageCache.cacheable("https://a/s/app.js", "application/javascript"))
        assertTrue(PageCache.cacheable("https://a/api/items", "application/json"))
        assertTrue(PageCache.cacheable("https://a/s/f.woff2", "font/woff2"))
        assertTrue(PageCache.cacheable("https://a/s/i.png", "image/png"))
        assertFalse(PageCache.cacheable("https://a/auth/login/", "text/html"))
        assertFalse(PageCache.cacheable("https://a/tiles/x/1/2/3", "application/x-protobuf"))
        assertFalse(PageCache.cacheable("https://a/v.mp4", "video/mp4"))
    }

    @Test
    fun `a saved screen is named by its vougadev-screen, else by its title without the app's name`() {
        val home = "<html><head><meta name=\"vougadev-screen\" content=\"Accueil\"><title>Votre colis ne voyage plus au hasard · Ship</title></head>"
        assertEquals("Accueil", PageCache.nameOf(home, "Ship"))
        assertEquals("Accueil", PageCache.nameOf("<head><meta content='Accueil' name='vougadev-screen'></head>", "Ship"))
        val unnamed = "<head><meta name=\"vougadev-screen\" content=\"\"><title>Mes envois · Ship</title></head>"
        assertEquals("Mes envois", PageCache.nameOf(unnamed, "Ship"))
        assertEquals("Mes envois", PageCache.nameOf("<head><title>Mes envois · Ship</title></head>", "Ship"))
        assertEquals("Détails · Envoi", PageCache.nameOf("<head><title>Détails · Envoi</title></head>", "Ship"))
        assertEquals("Ship", PageCache.nameOf("<head><title>Ship · Ship</title></head>", "Ship"))
        assertNull(PageCache.nameOf("<head><meta name=\"description\" content=\"x\"></head><body><meta name=\"vougadev-screen\" content=\"No\"></body>", "Ship"))
    }

    @Test
    fun `a page's title is read for the list`() {
        assertEquals("Orders & more", PageCache.titleOf("<html><head><title>\n Orders &amp; more </title></head>"))
        assertNull(PageCache.titleOf("<html></html>"))
    }
}

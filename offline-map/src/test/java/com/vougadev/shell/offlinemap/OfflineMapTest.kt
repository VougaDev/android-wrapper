package com.vougadev.shell.offlinemap

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The regions' manifest and the style that reads them from the phone. */
class OfflineMapTest {
    private val config = OfflineMapConfig("/missions/map/offline", "/tiles/{source}/{z}/{x}/{y}")
    private val shared = config.copy(tileOrigins = listOf("https://maps.vougadev.com"))

    @Test
    fun `the manifest keeps only named tilesets with a same-origin url`() {
        val rows = OfflineMap.parse(
            JSONObject(
                """{"available": true, "tilesets": [
                   {"source": "gabon", "name": "gabon", "size": 46137344, "version": "1774-46137344",
                    "url": "/maps/offline/gabon", "token": "kept-nowhere"},
                   {"name": "../etc", "url": "/x"}, {"name": "h3", "url": "https://elsewhere/h3.mbtiles"}, {"name": "h4", "url": "//elsewhere/h4"}]}""",
            ),
            config,
        )
        assertEquals(listOf("gabon"), rows.map { it.name })
        assertEquals(46_137_344L, rows.single().size)
        //: A SAME-ORIGIN FILE goes with the session: no bearer for it.
        assertEquals(null, rows.single().token)
    }

    @Test
    fun `a file on a tile origin is kept with its token for the download, nowhere else`() {
        val body = JSONObject(
            """{"available": true, "tilesets": [
               {"source": "gabon", "name": "gabon.mbtiles", "size": 46137344, "version": "69bfece3-2c00000",
                "url": "https://maps.vougadev.com/offline/gabon.mbtiles", "token": "eyJ.a-b_c"},
               {"name": "h1", "url": "https://maps.vougadev.com.evil.example/offline/h1.mbtiles"},
               {"name": "h2", "url": "http://maps.vougadev.com/offline/h2.mbtiles"},
               {"name": "h3", "url": "https://maps.vougadev.com/offline/h3.mbtiles", "token": "bad token\r\nX: y"}]}""",
        )
        val rows = OfflineMap.parse(body, shared)
        assertEquals(listOf("gabon", "h3"), rows.map { it.name })
        assertEquals(OfflineMap.Tileset("gabon", "gabon", 46_137_344L, "69bfece3-2c00000", "https://maps.vougadev.com/offline/gabon.mbtiles", "eyJ.a-b_c"), rows[0])
        assertEquals(null, rows[1].token)
        //: WITHOUT tileOrigins nothing leaves the site.
        assertTrue(OfflineMap.parse(body, config).isEmpty())
    }

    @Test
    fun `tile origins are exact https origins`() {
        assertEquals("https://maps.vougadev.com", shared.tileOriginOf("https://maps.vougadev.com/tiles/gabon/1/0/0"))
        assertEquals(null, shared.tileOriginOf("https://maps.vougadev.com:8443/tiles/gabon/1/0/0"))
        assertEquals(null, shared.tileOriginOf("https://proxi.vougadev.com/tiles/gabon/1/0/0"))
        listOf("http://maps.vougadev.com", "https://maps.vougadev.com/", "https://maps.vougadev.com/tiles", "maps.vougadev.com").forEach {
            assertTrue(it, runCatching { config.copy(tileOrigins = listOf(it)) }.isFailure)
        }
    }

    @Test
    fun `a download resumes only where the file stopped`() {
        assertTrue(OfflineMap.resumesAt("bytes 1000-46137343/46137344", 1000))
        assertFalse(OfflineMap.resumesAt("bytes 0-46137343/46137344", 1000))
        assertFalse(OfflineMap.resumesAt(null, 1000))
    }

    @Test
    fun `today's answer downloads nothing`() {
        assertTrue(OfflineMap.parse(JSONObject("""{"available": false, "tilesets": []}"""), config).isEmpty())
        assertTrue(OfflineMap.parse(JSONObject("""{"available": false, "tilesets": [{"name": "gabon", "url": "/x"}]}"""), config).isEmpty())
        assertTrue(OfflineMap.parse(null, config).isEmpty())
    }

    @Test
    fun `a downloaded tileset replaces its online source and nothing else`() {
        val style = JSONObject(
            """{"version": 8, "sources": {"gabon": {"type": "vector", "tiles": ["https://ship/tiles/gabon/{z}/{x}/{y}"], "maxzoom": 14},
               "h3": {"type": "vector", "tiles": ["https://ship/tiles/h3/{z}/{x}/{y}"]}}, "layers": []}""",
        )
        val local = OfflineMap.localStyle(style, mapOf("gabon" to File("/data/map/gabon.mbtiles")))
        val gabon = local.getJSONObject("sources").getJSONObject("gabon")
        assertEquals("mbtiles:///data/map/gabon.mbtiles", gabon.getString("url"))
        assertEquals(14, gabon.getInt("maxzoom"))
        assertTrue(local.getJSONObject("sources").getJSONObject("h3").has("tiles"))
        //: THE ORIGINAL IS UNTOUCHED: the stored style stays the server's.
        assertTrue(style.getJSONObject("sources").getJSONObject("gabon").has("tiles"))
    }

    @Test
    fun `a tile origin's answers allow the app's origin, and the preflight what the page asked`() {
        assertEquals("https://proxi.vougadev.com", CrossOrigin.answerHeaders("https://proxi.vougadev.com")["Access-Control-Allow-Origin"])
        val preflight = CrossOrigin.preflightHeaders(mapOf("access-control-request-headers" to "authorization, x-a\r\nb, accept"))
        assertEquals("authorization, accept", preflight["Access-Control-Allow-Headers"])
        assertEquals("Authorization", CrossOrigin.preflightHeaders(emptyMap())["Access-Control-Allow-Headers"])
    }

    @Test
    fun `a kept style or glyph comes back as it came`() {
        val body = byteArrayOf(0x1a, 0x0a, 0x00, 0x7f)
        val kept = KeptAnswers.decode(KeptAnswers.encode("application/x-protobuf", body))!!
        assertEquals("application/x-protobuf", kept.mimeType)
        assertArrayEquals(body, kept.body)
        assertEquals(null, KeptAnswers.decode(ByteArray(0)))
        assertEquals(64, KeptAnswers.keyOf("https://maps.vougadev.com/tiles/font/Noto/0-255.pbf").length)
        assertFalse(KeptAnswers.keyOf("https://maps.vougadev.com/a?x=1") == KeptAnswers.keyOf("https://maps.vougadev.com/a?x=2"))
    }
}

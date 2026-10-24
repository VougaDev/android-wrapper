package com.vougadev.shell.offlinemap

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The regions' manifest and the style that reads them from the phone. */
class OfflineMapTest {
    @Test
    fun `the manifest keeps only named tilesets with a same-origin url`() {
        val rows = OfflineMap.parse(
            JSONObject(
                """{"available": true, "tilesets": [
                   {"source": "gabon", "name": "gabon", "size": 46137344, "version": "1774-46137344",
                    "url": "/maps/offline/gabon"},
                   {"name": "../etc", "url": "/x"}, {"name": "h3", "url": "https://elsewhere/h3.mbtiles"}, {"name": "h4", "url": "//elsewhere/h4"}]}""",
            ),
        )
        assertEquals(listOf("gabon"), rows.map { it.name })
        assertEquals(46_137_344L, rows.single().size)
    }

    @Test
    fun `today's answer downloads nothing`() {
        assertTrue(OfflineMap.parse(JSONObject("""{"available": false, "tilesets": []}""")).isEmpty())
        assertTrue(OfflineMap.parse(JSONObject("""{"available": false, "tilesets": [{"name": "gabon", "url": "/x"}]}""")).isEmpty())
        assertTrue(OfflineMap.parse(null).isEmpty())
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
}

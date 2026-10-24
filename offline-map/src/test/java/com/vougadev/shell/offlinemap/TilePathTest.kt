package com.vougadev.shell.offlinemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which page requests are tiles the phone may answer, and where they sit in an `.mbtiles` file. */
class TilePathTest {

    private val path = TilePath.compile("/tiles/{source}/{z}/{x}/{y}")!!

    @Test
    fun `a tile of a known shape is read, in the web's order`() {
        assertEquals(TileRequest("gabon", 12, 2155, 2041), path.match("/tiles/gabon/12/2155/2041"))
        assertEquals(TileRequest("a_b-c", 0, 0, 0), path.match("/tiles/a_b-c/0/0/0"))
    }

    @Test
    fun `anything else goes to the network`() {
        listOf(
            "/tiles/gabon/12/2155", "/tiles/gabon/12/2155/2041/x", "/tiles/../12/1/1", "/tiles/gabon/a/1/1",
            "/tiles/gabon/2/4/0", "/tiles/gabon/25/0/0", "/tiles/style/light.json", "/other/gabon/1/0/0", null,
        ).forEach { assertNull("$it", path.match(it)) }
    }

    @Test
    fun `MBTiles rows count from the bottom`() {
        assertEquals(0, TileRequest("s", 0, 0, 0).tmsRow)
        assertEquals(4095 - 2041, TileRequest("s", 12, 2155, 2041).tmsRow)
    }

    @Test
    fun `a template must mark the source and the three numbers, in any order or with an extension`() {
        assertNull(TilePath.compile("/tiles/{z}/{x}/{y}"))
        assertEquals(TileRequest("v", 3, 1, 2), TilePath.compile("/{z}/{x}/{y}/{source}.pbf")!!.match("/3/1/2/v.pbf"))
        assertNull(TilePath.compile("/{z}/{x}/{y}/{source}.pbf")!!.match("/3/1/2/vxpbf"))
    }

    @Test
    fun `gzipped tiles are recognised`() {
        assertTrue(TileStore.isGzip(byteArrayOf(0x1f, 0x8b.toByte(), 8)))
        assertFalse(TileStore.isGzip(byteArrayOf(0x1a, 0x00, 0x00)))
    }
}

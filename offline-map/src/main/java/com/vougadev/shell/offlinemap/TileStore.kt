package com.vougadev.shell.offlinemap

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/** One tile a page asked for. */
data class TileRequest(val source: String, val z: Int, val x: Int, val y: Int) {
    /** MBTiles count rows from the bottom (TMS); the web counts from the top (XYZ). */
    val tmsRow: Int get() = (1 shl z) - 1 - y
}

/** The site's tile address ([OfflineMapConfig.tilePath]) as a pattern. Pure, tested on the JVM. */
class TilePath private constructor(private val pattern: Regex, private val order: List<String>) {

    /** The tile a [path] (same-origin, or on a tile origin) names, or null when it is not a tile of a valid zoom and position. */
    fun match(path: String?): TileRequest? {
        val found = pattern.matchEntire(path ?: return null) ?: return null
        val parts = order.zip(found.groupValues.drop(1)).toMap()
        val z = parts["z"]?.toIntOrNull() ?: return null
        val x = parts["x"]?.toIntOrNull() ?: return null
        val y = parts["y"]?.toIntOrNull() ?: return null
        if (z !in 0..24 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        return TileRequest(parts["source"] ?: return null, z, x, y)
    }

    companion object {
        private val MARK = Regex("\\{(source|z|x|y)\\}")

        fun compile(template: String): TilePath? {
            val order = MARK.findAll(template).map { it.groupValues[1] }.toList()
            if (order.sorted() != listOf("source", "x", "y", "z")) return null
            val regex = StringBuilder("^")
            var at = 0
            for (mark in MARK.findAll(template)) {
                regex.append(Regex.escape(template.substring(at, mark.range.first)))
                regex.append(if (mark.groupValues[1] == "source") "([A-Za-z0-9_-]+)" else "([0-9]{1,8})")
                at = mark.range.last + 1
            }
            regex.append(Regex.escape(template.substring(at))).append("$")
            return TilePath(Regex(regex.toString()), order)
        }
    }
}

/**
 * The `.mbtiles` regions on the phone, read for the WebView: a tile the page asks for comes from its region's file, so
 * the site's own map works with no network (and costs no data). A tile not in the file goes to the network as usual.
 * Called on WebView threads; each file is opened once, read-only.
 */
object TileStore {

    private val open = ConcurrentHashMap<String, SQLiteDatabase>()

    /** [headers] go with the answer (a tile origin's CORS headers). */
    fun answer(file: File, tile: TileRequest, headers: Map<String, String> = emptyMap()): WebResourceResponse? {
        val db = database(file) ?: return null
        val bytes = try {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(tile.z.toString(), tile.x.toString(), tile.tmsRow.toString()),
            ).use { if (it.moveToFirst()) it.getBlob(0) else null }
        } catch (_: SQLiteException) {
            null
        } ?: return null
        val body = if (isGzip(bytes)) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        return WebResourceResponse(mimeOf(db), null, 200, "OK", headers + ("Cache-Control" to "no-cache"), ByteArrayInputStream(body))
    }

    /** A region replaced or deleted: its file is opened again next time. */
    fun forget() {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    /** Vector tiles are stored gzipped; the page's map reads them plain. Pure. */
    fun isGzip(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun database(file: File): SQLiteDatabase? = open[file.path] ?: try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { open[file.path] = it }
    } catch (_: SQLiteException) {
        null
    }

    private fun mimeOf(db: SQLiteDatabase): String {
        val format = try {
            db.rawQuery("SELECT value FROM metadata WHERE name = 'format'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: SQLiteException) {
            null
        }
        return when (format?.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "application/x-protobuf"
        }
    }
}

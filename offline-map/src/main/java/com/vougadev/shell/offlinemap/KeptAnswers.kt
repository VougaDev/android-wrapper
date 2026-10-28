package com.vougadev.shell.offlinemap

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * WHAT A MAP NEEDS BESIDES ITS TILES: the style, glyphs and sprite a page took from a tile origin
 * ([OfflineMapConfig.tileOrigins]), kept as they came, one file each under `files/map/kept/`, and answered when the
 * network is gone so the map still draws. Kept only while a region is on the phone; deleted with the regions.
 */
object KeptAnswers {
    /** A style, a glyph range or a sprite is far smaller: anything larger is not kept. */
    const val MAX_BYTES = 2 * 1024 * 1024

    class Kept(val mimeType: String, val body: ByteArray)

    fun dir(context: Context): File = File(OfflineMap.dir(context), "kept")

    fun save(context: Context, url: String, mimeType: String, body: ByteArray) {
        if (body.size > MAX_BYTES) return
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val temp = File.createTempFile("kept", ".tmp", dir)
            temp.writeBytes(encode(mimeType, body))
            if (!temp.renameTo(File(dir, keyOf(url)))) temp.delete()
        }
    }

    fun read(context: Context, url: String): Kept? = runCatching { decode(File(dir(context), keyOf(url)).readBytes()) }.getOrNull()

    /** One file per address, named by its SHA-256 (the query too: a glyph range is in the path, a version may be in it). Pure. */
    fun keyOf(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The type on the first line, then the body as it came. Pure. */
    fun encode(mimeType: String, body: ByteArray): ByteArray = (mimeType.filter { it != '\n' } + "\n").toByteArray() + body

    fun decode(bytes: ByteArray): Kept? {
        val end = bytes.indexOf('\n'.code.toByte()).takeIf { it > 0 } ?: return null
        return Kept(String(bytes, 0, end), bytes.copyOfRange(end + 1, bytes.size))
    }
}

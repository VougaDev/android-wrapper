package com.vougadev.shell.offlinemap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.vougadev.shell.Shell
import com.vougadev.shell.net.AppHttp
import com.vougadev.shell.web.AppOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * THE MAP FOR A ROAD WITH NO NETWORK: the app downloads the `.mbtiles` regions the site lists
 * ([OfflineMapConfig.manifestPath]), keeps them on the phone, and they serve both the app's native map ([localStyle])
 * and the site's own map pages ([TileStore], through the WebView).
 *
 * ```
 * {"available": true, "tilesets": [{"source": "<style source>", "name": "<file name>", "size": <bytes>,
 *   "version": "<changes when the file does: its ETag>", "url": "<same-origin path, or https URL on a tileOrigin>",
 *   "token": "<optional bearer for that download only>"}]}
 * ```
 *
 * - WI-FI ONLY ([canDownload]): tens of megabytes on prepaid data is money, not a convenience.
 * - The style is kept on disk per theme ([saveStyle]) so a native map opens with no network at all, and each downloaded
 *   tileset replaces its online source with `mbtiles://` ([localStyle]).
 * - The version is the server file's own (its ETag): a tileset replaced on the server downloads again; a download cut
 *   off resumes where it stopped (`Range`, `If-Range` on that ETag).
 * - A file on a [OfflineMapConfig.tileOrigins] server goes there with the manifest's `token` as `Authorization: Bearer`
 *   and none of the site's cookies; the token is used for that download only, never kept.
 */
object OfflineMap {
    /** [url] is a same-origin path, or an https URL on one of the tile origins; [token] is never kept. */
    data class Tileset(val source: String, val name: String, val size: Long, val version: String, val url: String, val token: String? = null)

    fun parse(body: JSONObject?, config: OfflineMapConfig): List<Tileset> {
        if (body == null || body.has("available") && !body.optBoolean("available")) return emptyList()
        val rows = body.optJSONArray("tilesets") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val name = row.optString("name").removeSuffix(".mbtiles")
            val url = row.optString("url")
            val sameOrigin = url.startsWith("/") && !url.startsWith("//")
            if (!NAME.matches(name) || !sameOrigin && config.tileOriginOf(url) == null) return@mapNotNull null
            val token = row.optString("token").takeIf { !sameOrigin && it.isNotBlank() && TOKEN.matches(it) }
            Tileset(row.optString("source").ifBlank { name }, name, row.optLong("size"), row.optString("version"), url, token)
        }
    }

    /** The style with each downloaded source read from its file instead of `/tiles/`. Pure. */
    fun localStyle(style: JSONObject, files: Map<String, File>): JSONObject {
        if (files.isEmpty()) return style
        val copy = JSONObject(style.toString())
        val sources = copy.optJSONObject("sources") ?: return copy
        for ((source, file) in files) {
            val current = sources.optJSONObject(source) ?: continue
            sources.put(
                source,
                JSONObject().put("type", "vector").put("url", "mbtiles://" + file.absolutePath)
                    .put("minzoom", current.optInt("minzoom", 0)).put("maxzoom", current.optInt("maxzoom", 14)),
            )
        }
        return copy
    }

    fun dir(context: Context): File = File(context.filesDir, "map").apply { mkdirs() }

    private fun styleFile(context: Context, theme: String) = File(dir(context), "style-$theme.json")

    fun saveStyle(context: Context, theme: String, style: JSONObject) {
        runCatching { styleFile(context, theme).writeText(style.toString()) }
    }

    fun savedStyle(context: Context, theme: String): JSONObject? = runCatching { JSONObject(styleFile(context, theme).readText()) }.getOrNull()

    /** Source id -> downloaded file, for the tilesets already on the phone. */
    fun downloaded(context: Context): Map<String, File> {
        val prefs = prefs(context)
        return prefs.all.keys.filter { it.startsWith("source:") }.mapNotNull { key ->
            val name = prefs.getString(key, null) ?: return@mapNotNull null
            val file = File(dir(context), "$name.mbtiles")
            if (file.isFile) key.removePrefix("source:") to file else null
        }.toMap()
    }

    /** Every region goes (Settings): the native map and the pages read online again until the next download. */
    fun deleteAll(context: Context) {
        TileStore.forget()
        dir(context).listFiles()?.filter { it.name.endsWith(".mbtiles") || it.name.endsWith(".part") }?.forEach { it.delete() }
        KeptAnswers.dir(context).deleteRecursively()
        prefs(context).edit { clear() }
    }

    /** A network the system validated: worth asking the map server before the kept copy. */
    fun online(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun canDownload(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * Downloads each listed tileset whose version the phone does not hold: a same-origin one as the WebView's session
     * ([cookies]), one on a tile origin with its token only. Answers how many files arrived; a failure keeps what came
     * (resumed next time) and leaves the previous file in place.
     */
    suspend fun sync(context: Context, origin: AppOrigin, cookies: String?, userAgent: String, language: String, tilesets: List<Tileset>): Int =
        withContext(Dispatchers.IO) {
            val prefs = prefs(context)
            var arrived = 0
            for (tileset in tilesets) {
                val file = File(dir(context), "${tileset.name}.mbtiles")
                if (file.isFile && prefs.getString("version:${tileset.name}", null) == tileset.version) continue
                val request = downloadRequest(origin, cookies, userAgent, language, tileset) ?: continue
                val part = File(dir(context), "${tileset.name}.mbtiles.part")
                if (part.length() >= tileset.size) part.delete() // too long, or complete yet never kept: start again
                val ok = try {
                    fetchInto(part, tileset, request)
                } catch (_: IOException) {
                    false
                }
                if (ok && part.renameTo(file)) {
                    prefs.edit { putString("version:${tileset.name}", tileset.version).putString("source:${tileset.source}", tileset.name) }
                    arrived++
                } else if (part.length() >= tileset.size) {
                    part.delete()
                }
            }
            arrived
        }

    private fun downloadRequest(origin: AppOrigin, cookies: String?, userAgent: String, language: String, tileset: Tileset): Request.Builder? {
        val builder = if (tileset.url.startsWith("/")) {
            val headers = cookies?.takeIf { it.isNotBlank() }?.let { AppHttp.headers(origin, it, userAgent, language, unsafe = false) } ?: return null
            Request.Builder().url(origin.baseUrl + tileset.url).headers(headers)
        } else {
            // THE SHARED MAP SERVER: its bearer only, never the site's cookies.
            Request.Builder().url(tileset.url).header("User-Agent", userAgent).header("Accept-Language", language)
                .apply { tileset.token?.let { header("Authorization", "Bearer $it") } }
        }
        return builder.header("Accept", "application/octet-stream").get()
    }

    /** Into [part], from where an earlier attempt stopped when the server still holds that version. */
    private fun fetchInto(part: File, tileset: Tileset, request: Request.Builder): Boolean {
        val from = part.length()
        if (from > 0) request.header("Range", "bytes=$from-").header("If-Range", "\"${tileset.version}\"")
        download.newCall(request.build()).execute().use { response ->
            val append = response.code == 206 && resumesAt(response.header("Content-Range"), from)
            if (response.code != 200 && !append) return false
            java.io.FileOutputStream(part, append).use { out -> response.body.byteStream().copyTo(out, 64 * 1024) }
        }
        return part.length() == tileset.size
    }

    /** `bytes 1000-46137343/46137344` continues a file of [from] bytes. Pure. */
    fun resumesAt(contentRange: String?, from: Long): Boolean =
        contentRange?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull() == from

    //: A LARGE FILE: no call timeout, a read timeout per chunk (a stalled link is abandoned).
    private val download: OkHttpClient by lazy {
        AppHttp.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val NAME = Regex("^[A-Za-z0-9_-]+$")
    private val TOKEN = Regex("^[A-Za-z0-9._~+/=-]+$")
    /** `<storeName>_offline_map`: the regions on the phone and their versions. */
    private fun prefs(context: Context) = context.getSharedPreferences(prefsName(Shell.of(context).config.storeName), Context.MODE_PRIVATE)

    /** The regions' versions, kept across updates under this name (the files sit in `files/map/`). */
    fun prefsName(storeName: String) = storeName + "_offline_map"
}

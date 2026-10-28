package com.vougadev.shell.offlinemap

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vougadev.shell.Attachment
import com.vougadev.shell.Extension
import com.vougadev.shell.ExtensionHost
import com.vougadev.shell.SettingsEntry
import com.vougadev.shell.Shell
import com.vougadev.shell.net.ApiOutcome
import com.vougadev.shell.net.AppHttp
import com.vougadev.shell.push.PushNotifications
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * THE `offline-map` EXTENSION: map regions kept on the phone ([OfflineMap]), downloaded on Wi-Fi once signed in, which
 * answer the site's own map pages tile by tile ([TileStore]) so they work with no network, and the app's native map
 * screens too ([OfflineMap.localStyle]). A site whose map comes from a shared map server ([OfflineMapConfig.tileOrigins])
 * is answered the same way, with that server's CORS headers, and its style, glyphs and sprite are kept for offline. No
 * member on `window.VougaDevNative`: a page knows from `features().includes("offline-map")`, and its map simply keeps
 * working offline.
 */
class OfflineMapExtension(val config: OfflineMapConfig) : Extension {

    override val feature = "offline-map"

    private val tiles = TilePath.compile(config.tilePath)!!
    private val synced = AtomicBoolean(false)

    override fun attach(host: ExtensionHost): Attachment = object : Attachment {
        /** Once per app start, signed in, on Wi-Fi. */
        override fun onSignedInPage() {
            if (!OfflineMap.canDownload(host.activity) || !synced.compareAndSet(false, true)) return
            host.activity.lifecycleScope.launch { syncNow(host.activity) }
        }
    }

    override fun interceptRequest(context: Context, url: Uri): WebResourceResponse? {
        val tile = tiles.match(url.path) ?: return null
        val file = OfflineMap.downloaded(context)[tile.source] ?: return null
        return TileStore.answer(file, tile)
    }

    /**
     * A tile origin's request from the app's pages: its CORS preflight answered here (the tile requests carry
     * `Authorization`, and offline the preflight would fail); a tile from its region's file, online too; the style,
     * glyphs and sprite from the network and kept, or as kept when the network fails. Anything else goes to the network.
     */
    override fun interceptCrossOrigin(context: Context, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        config.tileOriginOf(url.toString()) ?: return null
        val cors = CrossOrigin.answerHeaders(Shell.of(context).session.origin.originString)
        if (request.method.equals("OPTIONS", ignoreCase = true)) {
            return WebResourceResponse(null, null, 204, "No Content", cors + CrossOrigin.preflightHeaders(request.requestHeaders), ByteArrayInputStream(ByteArray(0)))
        }
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val regions = OfflineMap.downloaded(context)
        tiles.match(url.path)?.let { tile -> return regions[tile.source]?.let { TileStore.answer(it, tile, cors) } }
        if (regions.isEmpty()) return null
        if (OfflineMap.online(context)) {
            try {
                fetchAndKeep(context, request, cors)?.let { return it }
            } catch (_: IOException) {
                // the map server never answered: the kept copy
            }
        }
        val kept = KeptAnswers.read(context, url.toString()) ?: return null
        return WebResourceResponse(kept.mimeType, null, 200, "OK", cors + ("Cache-Control" to "no-cache"), ByteArrayInputStream(kept.body))
    }

    /** The page's own request (its bearer included, no cookie), its 200 answer kept. A redirect is left to the WebView. */
    private fun fetchAndKeep(context: Context, request: WebResourceRequest, cors: Map<String, String>): WebResourceResponse? {
        val call = Request.Builder().url(request.url.toString()).get()
        request.requestHeaders.forEach { (name, value) -> if (!name.equals("Cookie", ignoreCase = true)) call.header(name, value) }
        AppHttp.client.newCall(call.build()).execute().use { response ->
            if (response.code in 300..399) return null
            val type = response.body.contentType()
            val mime = type?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            val body = response.body.bytes()
            if (response.code == 200) KeptAnswers.save(context, request.url.toString(), mime, body)
            return WebResourceResponse(mime, type?.charset()?.name(), response.code, response.message.ifBlank { "OK" }, cors, ByteArrayInputStream(body))
        }
    }

    override fun settingsEntries(context: Context): List<SettingsEntry> {
        val files = OfflineMap.downloaded(context).values
        val size = files.sumOf { it.length() }
        val summary = if (files.isEmpty()) context.getString(R.string.offline_map_none) else Formatter.formatShortFileSize(context, size)
        return listOf(
            SettingsEntry(context.getString(R.string.offline_map_title), summary, if (files.isEmpty()) null else { activity ->
                MaterialAlertDialogBuilder(activity)
                    .setMessage(R.string.offline_map_delete_text)
                    .setPositiveButton(R.string.offline_map_delete) { _, _ -> OfflineMap.deleteAll(activity) }
                    .setNegativeButton(com.vougadev.shell.R.string.shell_cancel, null)
                    .show()
            }),
        )
    }

    /**
     * The regions the site lists, downloaded when newer (Wi-Fi only); a notification says when a new one arrived. The
     * app's native map screens call it too. Answers how many arrived.
     */
    suspend fun syncNow(context: Context): Int {
        val app = context.applicationContext
        if (!OfflineMap.canDownload(app)) return 0
        val session = Shell.of(app).session
        val answer = session.caller().get(config.manifestPath) as? ApiOutcome.Ok ?: return 0
        val tilesets = OfflineMap.parse(answer.body, config).takeIf { it.isNotEmpty() } ?: return 0
        val jar = session.cookies.jar()
        val arrived = OfflineMap.sync(app, session.origin, jar, session.userAgent, session.acceptLanguage(), tilesets)
        if (arrived > 0) {
            TileStore.forget()
            PushNotifications.show(
                app, app.getString(R.string.offline_map_ready_title), app.getString(R.string.offline_map_ready_body),
                Shell.of(app).launcher(), NOTIFICATION_KEY,
            )
        }
        return arrived
    }

    private companion object {
        const val NOTIFICATION_KEY = "offline-map-ready"
    }
}

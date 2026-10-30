package com.vougadev.shell.web

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import com.vougadev.shell.R
import com.vougadev.shell.net.AppHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * A FILE THE SITE HANDS OVER (a PDF, a DOCX, a spreadsheet: an answer the WebView cannot show, or one marked
 * `Content-Disposition: attachment`): the WebView alone drops it. It is fetched as the WebView's session, kept in the
 * app's own storage (`files/documents/`, cleared on sign-out with the rest of what the app keeps of the person), and
 * opened in the phone's viewer, else offered to the apps that can take it (the share sheet). An app's extension may list
 * what is kept ([list]): Business Builder's documents on the phone, readable offline.
 */
object Downloads {

    private const val DIR = "documents"
    private const val MAX_BYTES = 50L * 1024 * 1024

    fun dir(context: Context) = File(context.filesDir, DIR)

    /** What is kept, newest first. */
    fun list(context: Context): List<File> = dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    /** The file's name from the answer's `Content-Disposition`, else the address; never a path. */
    fun fileName(url: String, disposition: String?, mimeType: String?): String =
        URLUtil.guessFileName(url, disposition, mimeType).replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120)

    /** The file fetched with the WebView's [cookies]; null on any failure (the network, a refusal, too large). */
    suspend fun fetch(context: Context, url: String, name: String, cookies: String?, userAgent: String, acceptLanguage: String): File? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept-Language", acceptLanguage)
                .apply { if (!cookies.isNullOrBlank()) header("Cookie", cookies) }
                .get().build()
            try {
                AppHttp.client.newCall(request).execute().use { answer ->
                    if (!answer.isSuccessful) return@withContext null
                    val length = answer.body.contentLength()
                    if (length > MAX_BYTES) return@withContext null
                    val dir = dir(context).apply { mkdirs() }
                    val part = File(dir, ".$name.part")
                    var total = 0L
                    part.outputStream().use { out ->
                        answer.body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) {
                                    part.delete()
                                    return@withContext null
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    File(dir, name).also { part.renameTo(it.apply { delete() }) }
                }
            } catch (_: IOException) {
                null
            }
        }

    /** The phone's viewer for [file]; with none, the apps that can take it. False when no app can. */
    fun open(activity: Activity, file: File, mimeType: String?): Boolean {
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val type = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(file.name, uri) }
        val chosen = if (view.resolveActivity(activity.packageManager) != null) view
        else Intent.createChooser(send, activity.getString(R.string.shell_download_open_with))
        return try {
            activity.startActivity(chosen)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }

    /** The share sheet for [file]: sending a plan to a banker by WhatsApp or e-mail. */
    fun share(activity: Activity, file: File, mimeType: String?) {
        val uri = try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        } catch (_: IllegalArgumentException) {
            return
        }
        val type = mimeType ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri(file.name, uri) }
        activity.startActivity(Intent.createChooser(send, null))
    }
}

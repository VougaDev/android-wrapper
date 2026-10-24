package com.vougadev.shell.offline

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/**
 * THE SITE'S PAGES AND THEIR FILES, KEPT ON THE PHONE, so the app stays useful with no network (Apple 4.2: offline is
 * never a dead end): each page the person visited, and the same-origin files it used (styles, scripts, images, fonts,
 * the JSON it read), up to [capBytes] - the least recently used go first. They are the person's: cleared on sign-out.
 *
 * Files in [dir], one per address, and an index. Plain Kotlin and files: tested on the JVM. Thread-safe.
 */
class PageCache(private val dir: File, private val capBytes: Long, private val clock: () -> Long = System::currentTimeMillis) {

    data class Entry(
        val url: String,
        val contentType: String,
        val size: Long,
        val savedAt: Long,
        val lastUsed: Long,
        val isPage: Boolean,
        val title: String?,
    )

    class Hit(val entry: Entry, val body: ByteArray)

    private var index: MutableMap<String, Entry>? = null

    @Synchronized
    fun put(url: String, contentType: String, body: ByteArray, isPage: Boolean, title: String? = null) {
        if (body.size > MAX_ITEM_BYTES || body.size > capBytes) return
        val entries = entries()
        val key = keyOf(url)
        val now = clock()
        try {
            dir.mkdirs()
            fileOf(key).writeBytes(body)
        } catch (_: IOException) {
            return
        }
        entries[key] = Entry(key, contentType, body.size.toLong(), now, now, isPage, title?.trim()?.take(MAX_TITLE)?.ifEmpty { null })
        evict(entries)
        save(entries)
    }

    @Synchronized
    fun get(url: String): Hit? {
        val entries = entries()
        val key = keyOf(url)
        val entry = entries[key] ?: return null
        val body = try {
            fileOf(key).readBytes()
        } catch (_: IOException) {
            entries.remove(key)
            save(entries)
            return null
        }
        entries[key] = entry.copy(lastUsed = clock())
        save(entries)
        return Hit(entry, body)
    }

    @Synchronized
    fun savedAt(url: String): Long? = entries()[keyOf(url)]?.savedAt

    /** The pages kept, the most recently used first. */
    @Synchronized
    fun pages(): List<Entry> = entries().values.filter { it.isPage }.sortedByDescending { it.lastUsed }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        index = mutableMapOf()
    }

    @Synchronized
    fun totalBytes(): Long = entries().values.sumOf { it.size }

    private fun evict(entries: MutableMap<String, Entry>) {
        var total = entries.values.sumOf { it.size }
        for (old in entries.values.sortedBy { it.lastUsed }.toList()) {
            if (total <= capBytes) break
            entries.remove(old.url)
            fileOf(old.url).delete()
            total -= old.size
        }
    }

    private fun entries(): MutableMap<String, Entry> = index ?: load().also { index = it }

    private fun load(): MutableMap<String, Entry> {
        val text = try {
            File(dir, INDEX).takeIf { it.isFile }?.readText()
        } catch (_: IOException) {
            null
        } ?: return mutableMapOf()
        return try {
            val rows = JSONArray(text)
            (0 until rows.length()).mapNotNull { i ->
                val o = rows.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    o.getString("url"), o.getString("type"), o.getLong("size"), o.getLong("saved"), o.getLong("used"),
                    o.optBoolean("page"), o.optString("title").ifEmpty { null },
                )
            }.filter { fileOf(it.url).isFile }.associateBy { it.url }.toMutableMap()
        } catch (_: JSONException) {
            mutableMapOf()
        }
    }

    private fun save(entries: Map<String, Entry>) {
        val rows = JSONArray()
        entries.values.forEach {
            rows.put(
                JSONObject().put("url", it.url).put("type", it.contentType).put("size", it.size).put("saved", it.savedAt)
                    .put("used", it.lastUsed).put("page", it.isPage).put("title", it.title ?: ""),
            )
        }
        try {
            dir.mkdirs()
            val tmp = File(dir, "$INDEX.tmp")
            tmp.writeText(rows.toString())
            tmp.renameTo(File(dir, INDEX))
        } catch (_: IOException) {
            // the index is rebuilt empty next time: nothing served is ever wrong, only missing
        }
    }

    private fun fileOf(key: String) = File(dir, sha256(key))

    companion object {
        const val DEFAULT_CAP_BYTES = 50L * 1024 * 1024
        private const val MAX_ITEM_BYTES = 2L * 1024 * 1024
        private const val MAX_TITLE = 120
        private const val INDEX = "index.json"

        /** The address without its fragment: what the cache is keyed by. */
        fun keyOf(url: String): String = url.substringBefore('#')

        /** The page's `<title>`, for the list of what is available offline. */
        fun titleOf(html: String): String? = TITLE.find(html)?.groupValues?.get(1)
            ?.replace(Regex("\\s+"), " ")?.let(::unescape)?.trim()?.ifEmpty { null }

        /**
         * The screen's name for the offline list: the page's `<meta name="vougadev-screen" content="…">` when it says
         * one (a home whose title is a slogan), else its title without the ` · <App>` the site adds.
         */
        fun nameOf(html: String, appName: String): String? {
            val head = html.substringBefore("</head>")
            val said = META.findAll(head).map { it.value }.firstOrNull { SCREEN.containsMatchIn(it) }
                ?.let { CONTENT.find(it)?.groupValues?.get(2) }
                ?.replace(Regex("\\s+"), " ")?.let(::unescape)?.trim()?.ifEmpty { null }
            return said ?: titleOf(html)?.removeSuffix(" · $appName")?.trim()?.ifEmpty { null }
        }

        /**
         * Kept for offline: a same-origin GET, never the sign-in and sign-out addresses, of a type a page needs again.
         * [contentType] is the answer's (null before it is known).
         */
        fun cacheable(url: String, contentType: String?): Boolean {
            val path = try {
                URI(url).rawPath ?: return false
            } catch (_: URISyntaxException) {
                return false
            }
            if (path.startsWith("/auth/")) return false
            val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
            return type == "text/html" || type == "text/css" || type == "application/json" || type == "image/svg+xml" ||
                type.endsWith("javascript") || type.startsWith("image/") || type.startsWith("font/") || type.contains("font")
        }

        private val TITLE = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val SCREEN = Regex("\\bname\\s*=\\s*[\"']?vougadev-screen\\b", RegexOption.IGNORE_CASE)
        private val CONTENT = Regex("\\bcontent\\s*=\\s*([\"'])([\\s\\S]*?)\\1", RegexOption.IGNORE_CASE)

        private fun unescape(text: String) = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

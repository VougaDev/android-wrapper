package com.vougadev.shell.offline

/**
 * Which answers of a trusted origin are not the site's, never shown: an error without the company-page meta. A 5xx (the
 * proxy's 502, 503 or 504 during a release, Cloudflare's "Bad gateway") and a 4xx PAGE (Cloudflare's 403 or 429, the
 * proxy's 404 or 413, a framework's debug or CSRF page: Echo, 2026-10-30) - the site serves its own error pages, with
 * the meta. A 2xx or 3xx is always the site's own answer - a page, a CV, an invoice, a PDF - shown meta or not, and so
 * is a 4xx that is not a page (a file). Only a 5xx says the service is restarting; an edge that answered proves the
 * connection works, never "offline".
 */
object Outage {
    /** [status] null: a 2xx; [mimeType] null: a page. Pure. */
    fun isEdge(status: Int?, hasMeta: Boolean, mimeType: String? = null): Boolean =
        status != null && !hasMeta && (status >= 500 || status >= 400 && isPage(mimeType))

    /** An error that says the service is restarting (asked again until it answers): a 5xx, not a refusal. Pure. */
    fun restarting(status: Int?): Boolean = status == null || status >= 500

    private fun isPage(mimeType: String?): Boolean =
        mimeType == null || mimeType.startsWith("text/") || "json" in mimeType || "xml" in mimeType

    /** Whether an answer's HTML carries the company-page meta (`<meta name="vougadev-page" ...>`) in its head. Pure. */
    fun hasMeta(body: ByteArray): Boolean = META.containsMatchIn(String(body, 0, minOf(body.size, HEAD_BYTES), Charsets.UTF_8))

    private const val HEAD_BYTES = 64 * 1024
    private val META = Regex("<meta\\s[^>]*name\\s*=\\s*[\"']?vougadev-page[\"'\\s/>]", RegexOption.IGNORE_CASE)

    /** Whether the site is back, asked again during a restart: any answer under 500 is its own. Pure. */
    fun back(status: Int): Boolean = status < 500
}

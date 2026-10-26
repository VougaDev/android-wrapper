package com.vougadev.shell.offline

/**
 * Which answers of a trusted origin are the edge's, never shown: a 5xx without the company-page meta (a 502, 503 or 504
 * from the proxy during a release). A 2xx, 3xx or 4xx is the site's own answer - a page, a CV, an invoice, a ticket, a
 * PDF, a printable sheet - shown as it is, meta or not, whatever its type. An edge that answered proves the connection
 * works: the service is restarting, never "offline".
 */
object Outage {
    /** [status] null: a 2xx. Pure. */
    fun isEdge(status: Int?, hasMeta: Boolean): Boolean = status != null && status >= 500 && !hasMeta

    /** Whether the site is back, asked again during a restart: any answer under 500 is its own. Pure. */
    fun back(status: Int): Boolean = status < 500
}

package com.vougadev.shell.offline

/**
 * What the person is told when a page of the site cannot be shown, and never a lie: OFFLINE only when the phone has no
 * validated network. An edge that ANSWERED (a 502, 503 or 504 from the proxy during a release, its page without the
 * company-page meta) proves the connection works: the service is RESTARTING, and the shell retries by itself.
 */
enum class Outage {
    OFFLINE, RESTARTING, UNAVAILABLE;

    companion object {
        /** The edge answered [status] (null: a 200 page without the company-page meta). Pure. */
        fun of(validated: Boolean, status: Int?): Outage = when {
            !validated -> OFFLINE
            status == null || status >= 500 -> RESTARTING
            else -> UNAVAILABLE
        }

        /** Whether the site is back: a page under 500 that is the site's own ([meta] in it), never the edge's. Pure. */
        fun back(status: Int, html: String, meta: String): Boolean = status < 500 && "name=\"$meta\"" in html
    }
}

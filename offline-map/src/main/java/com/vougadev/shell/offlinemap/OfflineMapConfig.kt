package com.vougadev.shell.offlinemap

import com.vougadev.shell.web.AppOrigin

/**
 * What the `offline-map` extension needs from the app's site:
 *
 * - [manifestPath]: lists the regions this person may keep ([OfflineMap.parse]);
 * - [tilePath]: the address of one tile as the site's map pages request it, `{source}`, `{z}`, `{x}` and `{y}` marked,
 *   e.g. `/tiles/{source}/{z}/{x}/{y}`: such a request is answered from the phone when its region is there;
 * - [tileOrigins]: the shared map servers the site's pages take their map from (exact `https://host` origins, e.g.
 *   `https://maps.vougadev.com`): their tiles are answered from the phone too, their style, glyphs and sprite kept for
 *   offline, and a tileset's file may be downloaded from them.
 */
data class OfflineMapConfig(val manifestPath: String, val tilePath: String, val tileOrigins: List<String> = emptyList()) {
    init {
        require(manifestPath.startsWith("/") && !manifestPath.startsWith("//")) { "manifestPath: $manifestPath" }
        require(TilePath.compile(tilePath) != null) { "tilePath needs {source}, {z}, {x} and {y}: $tilePath" }
        tileOrigins.forEach { require(isOrigin(it)) { "tileOrigins: an https origin, no path: $it" } }
    }

    /** The origin of [url] when it is one of [tileOrigins], else null. */
    fun tileOriginOf(url: String?): String? = tileOrigins.firstOrNull { AppOrigin(it).matches(url) }

    private companion object {
        fun isOrigin(value: String) = value.startsWith("https://") &&
            runCatching { AppOrigin(value).originString == value }.getOrDefault(false)
    }
}

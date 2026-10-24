package com.vougadev.shell.offlinemap

/**
 * What the `offline-map` extension needs from the app's site:
 *
 * - [manifestPath]: lists the regions this person may keep ([OfflineMap.parse]);
 * - [tilePath]: the address of one tile as the site's map pages request it, `{source}`, `{z}`, `{x}` and `{y}` marked,
 *   e.g. `/tiles/{source}/{z}/{x}/{y}`: such a request is answered from the phone when its region is there.
 */
data class OfflineMapConfig(val manifestPath: String, val tilePath: String) {
    init {
        require(manifestPath.startsWith("/") && !manifestPath.startsWith("//")) { "manifestPath: $manifestPath" }
        require(TilePath.compile(tilePath) != null) { "tilePath needs {source}, {z}, {x} and {y}: $tilePath" }
    }
}

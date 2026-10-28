package com.vougadev.shell.offlinemap

/**
 * The CORS headers of an answer the phone gives for a tile origin: the page that asked is the app's own, so the answer
 * allows exactly its origin (no credentials: the map server takes a bearer, never cookies). Pure, tested on the JVM.
 */
object CrossOrigin {

    fun answerHeaders(siteOrigin: String): Map<String, String> = mapOf("Access-Control-Allow-Origin" to siteOrigin, "Vary" to "Origin")

    /** The preflight's own: GET, with the headers the page asked for (its `Authorization`), for ten minutes. */
    fun preflightHeaders(request: Map<String, String>): Map<String, String> {
        val asked = request.entries.firstOrNull { it.key.equals("Access-Control-Request-Headers", ignoreCase = true) }?.value.orEmpty()
            .split(',').map { it.trim() }.filter { HEADER.matches(it) }
        return mapOf(
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to asked.ifEmpty { listOf("Authorization") }.joinToString(", "),
            "Access-Control-Max-Age" to "600",
        )
    }

    private val HEADER = Regex("^[A-Za-z0-9-]{1,64}$")
}

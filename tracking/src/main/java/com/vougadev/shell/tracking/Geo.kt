package com.vougadev.shell.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance, for the `distance_m` the phone reports to the stop it heads for. */
object Geo {

    /** Mean Earth radius (IUGG), metres. */
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLng / 2).let { it * it }
        // min(): rounding can push h a hair above 1 for antipodal points, and asin(>1) is NaN.
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }
}

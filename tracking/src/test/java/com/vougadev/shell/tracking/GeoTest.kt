package com.vougadev.shell.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `the same point is zero metres away`() {
        assertEquals(0.0, Geo.distanceMeters(GeoPoint(0.39, 9.45), GeoPoint(0.39, 9.45)), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        // pi * R / 180 with R = 6 371 008.8 m.
        assertEquals(111_195.08, Geo.distanceMeters(GeoPoint(0.0, 9.45), GeoPoint(1.0, 9.45)), 0.01)
    }

    @Test
    fun `one degree of longitude shrinks with the cosine of the latitude`() {
        val atEquator = Geo.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        val at60 = Geo.distanceMeters(GeoPoint(60.0, 0.0), GeoPoint(60.0, 1.0))
        assertEquals(atEquator / 2, at60, 5.0)
    }

    @Test
    fun `a short hop in Libreville`() {
        // 0.001 degree of latitude on one meridian: 111.195 m.
        assertEquals(111.195, Geo.distanceMeters(GeoPoint(0.390, 9.450), GeoPoint(0.391, 9.450)), 0.001)
    }

    @Test
    fun `antipodal points are half the circumference and not NaN`() {
        assertEquals(Math.PI * 6_371_008.8, Geo.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 180.0)), 0.01)
    }

    @Test
    fun `the distance is symmetric`() {
        val a = GeoPoint(0.39, 9.45)
        val b = GeoPoint(-0.72, 8.78)
        assertEquals(Geo.distanceMeters(a, b), Geo.distanceMeters(b, a), 1e-6)
    }
}

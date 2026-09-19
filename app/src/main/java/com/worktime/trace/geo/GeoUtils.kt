package com.worktime.trace.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371008.8

    /** 两坐标点的球面距离（Haversine 公式），单位米。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return EARTH_RADIUS_METERS * c
    }

    fun formatDistance(meters: Double): String = when {
        meters < 1000 -> "${meters.toInt()} m"
        else -> String.format("%.1f km", meters / 1000)
    }
}

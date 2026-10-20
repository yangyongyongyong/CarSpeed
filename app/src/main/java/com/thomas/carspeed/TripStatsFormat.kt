package com.thomas.carspeed

import java.util.Locale
import kotlin.math.roundToInt

internal object TripStatsFormat {
    fun distance(meters: Double): String {
        val safe = meters.coerceAtLeast(0.0)
        if (safe < 1000.0) return "${safe.roundToInt()}米"
        val km = safe / 1000.0
        return if (km >= 100.0) {
            "${km.roundToInt()}公里"
        } else {
            String.format(Locale.US, "%.1f公里", km)
        }
    }

    fun duration(durationMs: Long): String {
        val safe = durationMs.coerceAtLeast(0L)
        if (safe <= 0L) return "0分钟"
        if (safe < 60_000L) return "1分钟以内"
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        return when {
            hours > 0L && minutes > 0L -> "${hours}小时${minutes}分"
            hours > 0L -> "${hours}小时"
            else -> "${minutes}分钟"
        }
    }

    fun speed(kmh: Double): String {
        return String.format(Locale.US, "%.1f km/h", kmh.coerceAtLeast(0.0))
    }
}

package com.thomas.carspeed

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DashcamTelemetry(
    val speedKmh: Float = 0f,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null
)

object DashcamOverlayText {
    fun text(
        context: Context,
        mode: VideoMode = CameraCapabilities.selectedVideoMode(context),
        forceTime: Boolean = false
    ): String {
        return lines(context, mode, forceTime).joinToString("\n").ifBlank { mode.label }
    }

    fun lines(
        context: Context,
        mode: VideoMode = CameraCapabilities.selectedVideoMode(context),
        forceTime: Boolean = false
    ): List<String> {
        val settings = DashcamSettings.load(context)
        val telemetry = DashcamService.currentTelemetry()
        val result = mutableListOf<String>()
        if (forceTime || settings.showTime) {
            result += SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        }
        if (settings.showSpeed) {
            result += String.format(Locale.US, "%.1f km/h", telemetry.speedKmh)
        }
        if (settings.showGps) {
            val lat = telemetry.latitude
            val lon = telemetry.longitude
            result += if (lat != null && lon != null) {
                String.format(Locale.US, "%.6f, %.6f", lat, lon)
            } else {
                "GPS --"
            }
        }
        if (settings.showAddress) {
            result += telemetry.address ?: context.getString(R.string.watermark_address_pending)
        }
        if (settings.showVideoMode) {
            result += mode.label
        }
        return result
    }
}

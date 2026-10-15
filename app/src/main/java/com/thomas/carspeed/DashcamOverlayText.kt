package com.thomas.carspeed

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DashcamTelemetry(
    val speedKmh: Float = 0f,
    val stationaryWaitMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val satelliteCount: Int? = null,
    val address: String? = null,
    val focusDistanceDiopters: Float? = null,
    val autofocusState: String? = null
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
        if (settings.showStationaryWait) {
            result += formatStationaryWait(telemetry.stationaryWaitMs)
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
        if (settings.showSatellites) {
            val count = telemetry.satelliteCount
            result += if (count != null) {
                context.getString(R.string.gps_satellite_label_fmt, count)
            } else {
                context.getString(R.string.gps_satellite_unknown)
            }
        }
        if (settings.showAddress) {
            val layer = RoadLayerEngine.subtitleLabel()
            result += telemetry.address?.let { PlaceSubtitle.withLayer(it, layer) }
                ?: context.getString(R.string.watermark_address_pending)
        }
        if (settings.showRoadLayer && !settings.showAddress) {
            RoadLayerEngine.subtitleLabel()?.let { result += it }
        }
        if (settings.showFocus) {
            result += formatFocus(telemetry, context)
        }
        if (settings.showVideoMode) {
            val hdr = if (DashcamService.isHdrActive()) " · ${context.getString(R.string.hdr_watermark_label)}" else ""
            result += mode.label + hdr
        }
        return result
    }

    fun formatStationaryWait(waitDurationMs: Long): String {
        val totalSec = (waitDurationMs / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "静止等待：%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatFocus(telemetry: DashcamTelemetry, context: Context): String {
        val focus = telemetry.focusDistanceDiopters
        val focusText = when {
            focus == null -> "--"
            focus <= 0.01f -> "∞(${String.format(Locale.US, "%.2f", focus)}D)"
            else -> {
                val meters = 1f / focus
                val distance = if (meters < 1f) {
                    String.format(Locale.US, "%.0fcm", meters * 100f)
                } else if (meters >= 10f) {
                    String.format(Locale.US, "%.0fm", meters)
                } else {
                    String.format(Locale.US, "%.1fm", meters)
                }
                "${String.format(Locale.US, "%.2f", focus)}D($distance)"
            }
        }
        val state = if (CameraFocus.isManualEnabled(context)) {
            "MF"
        } else {
            telemetry.autofocusState ?: "AF--"
        }
        return context.getString(R.string.focus_watermark_fmt, focusText, state)
    }
}

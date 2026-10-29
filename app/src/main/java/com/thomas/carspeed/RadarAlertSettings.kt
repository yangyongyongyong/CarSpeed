package com.thomas.carspeed

import android.content.Context
import java.util.Locale

data class RadarAlertSettings(
    val enabled: Boolean,
    val thresholdsSeconds: List<Int>,
    val leadSeconds: Int,
    val speechRatePercent: Int,
    val unknownRoadLayerElevated: Boolean = true,
    val roadDivergenceCorrectionEnabled: Boolean = true,
    val pitchRoadLayerEnabled: Boolean = true,
    val satelliteTunnelEnabled: Boolean = true,
    val clearStretchEnabled: Boolean = false,
    val clearStretchSpanMeters: Int = RadarClearStretch.DEFAULT_SPAN_METERS,
    val clearStretchIntervalSeconds: Int = RadarClearStretch.DEFAULT_INTERVAL_SECONDS,
    val clearStretchReferenceSpeedKmh: Int = RadarAlertSettingsStore.DEFAULT_CLEAR_STRETCH_SPEED_KMH
) {
    val speechRate: Float
        get() = speechRatePercent.coerceIn(
            RadarAlertSettingsStore.MIN_SPEECH_RATE_PERCENT,
            RadarAlertSettingsStore.MAX_SPEECH_RATE_PERCENT
        ) / 100f
}

object RadarAlertSettingsStore {
    const val PREF_RADAR_ALERT_ENABLED = "radar_alert_enabled"
    const val PREF_RADAR_ALERT_THRESHOLDS = "radar_alert_thresholds_seconds"
    const val PREF_RADAR_ALERT_LEAD_SECONDS = "radar_alert_lead_seconds"
    const val PREF_RADAR_ALERT_SPEECH_RATE_PERCENT = "radar_alert_speech_rate_percent"
    const val PREF_RADAR_UNKNOWN_ROAD_LAYER_ELEVATED = "radar_unknown_road_layer_elevated"
    const val PREF_RADAR_ROAD_DIVERGENCE_CORRECTION = "radar_road_divergence_correction"
    const val PREF_RADAR_PITCH_ROAD_LAYER = "radar_pitch_road_layer"
    const val PREF_RADAR_SATELLITE_TUNNEL = "radar_satellite_tunnel"
    const val PREF_RADAR_CLEAR_STRETCH_ENABLED = "radar_clear_stretch_enabled"
    const val PREF_RADAR_CLEAR_STRETCH_SPAN_METERS = "radar_clear_stretch_span_meters"
    const val PREF_RADAR_CLEAR_STRETCH_INTERVAL_SECONDS = "radar_clear_stretch_interval_seconds"
    const val PREF_RADAR_CLEAR_STRETCH_SPEED_KMH = "radar_clear_stretch_speed_kmh"

    const val DEFAULT_ENABLED = false
    const val DEFAULT_UNKNOWN_ROAD_LAYER_ELEVATED = true
    const val DEFAULT_ROAD_DIVERGENCE_CORRECTION = true
    const val DEFAULT_PITCH_ROAD_LAYER = true
    const val DEFAULT_SATELLITE_TUNNEL = true
    const val DEFAULT_CLEAR_STRETCH_ENABLED = false
    const val DEFAULT_CLEAR_STRETCH_SPEED_KMH = 100
    const val MIN_CLEAR_STRETCH_SPEED_KMH = 20
    const val MAX_CLEAR_STRETCH_SPEED_KMH = 240
    const val DEFAULT_THRESHOLDS_TEXT = "20,10,5"
    const val DEFAULT_LEAD_SECONDS = 2
    const val DEFAULT_SPEECH_RATE_PERCENT = 150
    const val MIN_THRESHOLD_SECONDS = 1
    const val MAX_THRESHOLD_SECONDS = 300
    const val MIN_LEAD_SECONDS = 0
    const val MAX_LEAD_SECONDS = 10
    const val MIN_SPEECH_RATE_PERCENT = 100
    const val MAX_SPEECH_RATE_PERCENT = 220

    fun load(context: Context): RadarAlertSettings {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        return RadarAlertSettings(
            enabled = prefs.getBoolean(PREF_RADAR_ALERT_ENABLED, DEFAULT_ENABLED),
            thresholdsSeconds = parseThresholds(
                prefs.getString(PREF_RADAR_ALERT_THRESHOLDS, DEFAULT_THRESHOLDS_TEXT).orEmpty()
            ),
            leadSeconds = prefs.getInt(PREF_RADAR_ALERT_LEAD_SECONDS, DEFAULT_LEAD_SECONDS)
                .coerceIn(MIN_LEAD_SECONDS, MAX_LEAD_SECONDS),
            speechRatePercent = prefs.getInt(PREF_RADAR_ALERT_SPEECH_RATE_PERCENT, DEFAULT_SPEECH_RATE_PERCENT)
                .coerceIn(MIN_SPEECH_RATE_PERCENT, MAX_SPEECH_RATE_PERCENT),
            unknownRoadLayerElevated = prefs.getBoolean(
                PREF_RADAR_UNKNOWN_ROAD_LAYER_ELEVATED,
                DEFAULT_UNKNOWN_ROAD_LAYER_ELEVATED
            ),
            roadDivergenceCorrectionEnabled = prefs.getBoolean(
                PREF_RADAR_ROAD_DIVERGENCE_CORRECTION,
                DEFAULT_ROAD_DIVERGENCE_CORRECTION
            ),
            pitchRoadLayerEnabled = prefs.getBoolean(
                PREF_RADAR_PITCH_ROAD_LAYER,
                DEFAULT_PITCH_ROAD_LAYER
            ),
            satelliteTunnelEnabled = prefs.getBoolean(
                PREF_RADAR_SATELLITE_TUNNEL,
                DEFAULT_SATELLITE_TUNNEL
            ),
            clearStretchEnabled = prefs.getBoolean(
                PREF_RADAR_CLEAR_STRETCH_ENABLED,
                DEFAULT_CLEAR_STRETCH_ENABLED
            ),
            clearStretchSpanMeters = prefs.getInt(
                PREF_RADAR_CLEAR_STRETCH_SPAN_METERS,
                RadarClearStretch.DEFAULT_SPAN_METERS
            ).coerceIn(RadarClearStretch.MIN_SPAN_METERS, RadarClearStretch.MAX_SPAN_METERS),
            clearStretchIntervalSeconds = prefs.getInt(
                PREF_RADAR_CLEAR_STRETCH_INTERVAL_SECONDS,
                RadarClearStretch.DEFAULT_INTERVAL_SECONDS
            ).coerceIn(RadarClearStretch.MIN_INTERVAL_SECONDS, RadarClearStretch.MAX_INTERVAL_SECONDS),
            clearStretchReferenceSpeedKmh = prefs.getInt(
                PREF_RADAR_CLEAR_STRETCH_SPEED_KMH,
                DEFAULT_CLEAR_STRETCH_SPEED_KMH
            ).coerceIn(MIN_CLEAR_STRETCH_SPEED_KMH, MAX_CLEAR_STRETCH_SPEED_KMH)
        )
    }

    /** 无雷达路段播报的四个值一起存，省得调用方分开写时漏一个。 */
    fun saveClearStretch(
        context: Context,
        enabled: Boolean,
        spanMeters: Int,
        intervalSeconds: Int,
        referenceSpeedKmh: Int
    ) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_RADAR_CLEAR_STRETCH_ENABLED, enabled)
            .putInt(
                PREF_RADAR_CLEAR_STRETCH_SPAN_METERS,
                spanMeters.coerceIn(RadarClearStretch.MIN_SPAN_METERS, RadarClearStretch.MAX_SPAN_METERS)
            )
            .putInt(
                PREF_RADAR_CLEAR_STRETCH_INTERVAL_SECONDS,
                intervalSeconds.coerceIn(
                    RadarClearStretch.MIN_INTERVAL_SECONDS,
                    RadarClearStretch.MAX_INTERVAL_SECONDS
                )
            )
            .putInt(
                PREF_RADAR_CLEAR_STRETCH_SPEED_KMH,
                referenceSpeedKmh.coerceIn(MIN_CLEAR_STRETCH_SPEED_KMH, MAX_CLEAR_STRETCH_SPEED_KMH)
            )
            .apply()
    }

    fun saveThresholds(context: Context, thresholdsSeconds: List<Int>) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RADAR_ALERT_THRESHOLDS, formatThresholds(thresholdsSeconds))
            .apply()
    }

    fun parseThresholds(raw: String): List<Int> {
        val parsed = raw
            .split(',', '，', ' ', '\n', '\t')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { it.coerceIn(MIN_THRESHOLD_SECONDS, MAX_THRESHOLD_SECONDS) }
            .distinct()
            .sortedDescending()
        return parsed.ifEmpty { listOf(20, 10, 5) }
    }

    fun formatThresholds(thresholdsSeconds: List<Int>): String {
        return thresholdsSeconds
            .map { it.coerceIn(MIN_THRESHOLD_SECONDS, MAX_THRESHOLD_SECONDS) }
            .distinct()
            .sortedDescending()
            .joinToString(",")
    }

    fun thresholdsLabel(thresholdsSeconds: List<Int>): String {
        return thresholdsSeconds.joinToString("、") { String.format(Locale.US, "%d秒", it) }
    }
}

package com.thomas.carspeed

import android.content.Context
import java.util.Locale

data class RadarAlertSettings(
    val enabled: Boolean,
    val thresholdsSeconds: List<Int>,
    val leadSeconds: Int,
    val speechRatePercent: Int
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

    const val DEFAULT_ENABLED = false
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
                .coerceIn(MIN_SPEECH_RATE_PERCENT, MAX_SPEECH_RATE_PERCENT)
        )
    }

    fun saveThresholds(context: Context, thresholdsSeconds: List<Int>) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_RADAR_ALERT_THRESHOLDS, formatThresholds(thresholdsSeconds))
            .commit()
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

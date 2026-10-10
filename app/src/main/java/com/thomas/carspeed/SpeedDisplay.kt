package com.thomas.carspeed

object SpeedDisplay {
    const val UNIT_KMH = "kmh"
    const val UNIT_MPH = "mph"
    const val DEFAULT_WARNING_KMH = 120

    fun normalizeUnit(raw: String?): String {
        return if (raw == UNIT_MPH) UNIT_MPH else UNIT_KMH
    }

    fun unitLabel(unit: String): String {
        return if (normalizeUnit(unit) == UNIT_MPH) "mph" else "km/h"
    }

    fun toDisplaySpeed(kmh: Float, unit: String): Float {
        return if (normalizeUnit(unit) == UNIT_MPH) kmh * 0.621371f else kmh
    }

    fun warningLabel(thresholdKmh: Int, unit: String): String {
        val normalized = normalizeUnit(unit)
        val display = toDisplaySpeed(thresholdKmh.toFloat(), normalized)
        return "速度提醒阈值：${display.toInt()} ${unitLabel(normalized)}"
    }

    fun unitIndex(unit: String): Int {
        return if (normalizeUnit(unit) == UNIT_MPH) 1 else 0
    }

    fun unitFromIndex(index: Int): String {
        return if (index == 1) UNIT_MPH else UNIT_KMH
    }
}

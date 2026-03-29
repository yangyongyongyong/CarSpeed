package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.round

object DirectionFormatter {

    fun label(angle: Float): String {
        val a = ((angle % 360f) + 360f) % 360f
        val nearest45 = (round(a / 45f).toInt() * 45) % 360

        val base = when (nearest45) {
            0 -> "北"
            45 -> "东北"
            90 -> "东"
            135 -> "东南"
            180 -> "南"
            225 -> "西南"
            270 -> "西"
            315 -> "西北"
            else -> "北"
        }

        val rawDiff = abs(a - nearest45)
        val diff = minOf(rawDiff, 360f - rawDiff)
        if (diff < 8f) return base

        val pair = when {
            a in 0f..90f -> "北偏东"
            a in 90f..180f -> "南偏东"
            a in 180f..270f -> "南偏西"
            else -> "北偏西"
        }
        return "$pair ${diff.toInt()}°"
    }
}

package com.thomas.carspeed

import kotlin.math.abs

internal object RecordingBubbleScale {
    val percents = intArrayOf(100, 130, 160, 80)
    const val DEFAULT_PERCENT = 100

    fun coerce(percent: Int): Int {
        return percents.minBy { abs(it - percent) }
    }

    fun next(percent: Int): Int {
        val current = coerce(percent)
        val index = percents.indexOf(current).coerceAtLeast(0)
        return percents[(index + 1) % percents.size]
    }
}

package com.thomas.carspeed

internal object RouteTrackStyle {
    const val FAST_SPEED_KMH = 180f
    const val FAST_WIDTH_SCALE = 1.5f
    const val PLAYED_WIDTH_RATIO = 0.5f

    fun segmentSpeedKmh(startKmh: Float, endKmh: Float): Float = (startKmh + endKmh) / 2f

    /** 普通路段为 1；车速超过 180 km/h 时轨迹加宽 50%。 */
    fun widthScale(speedKmh: Float): Float {
        return if (speedKmh > FAST_SPEED_KMH) FAST_WIDTH_SCALE else 1f
    }

    /** 已走白线取对应绿线宽度的一半，避免盖住两侧绿色。 */
    fun playedWidthScale(routeWidthScale: Float): Float = routeWidthScale * PLAYED_WIDTH_RATIO
}

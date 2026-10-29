package com.thomas.carspeed

internal data class RouteRun(val scale: Float, val startIndex: Int, val endIndex: Int)

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

    /**
     * 把相邻、同样粗细的小段合成一条。全程缩得很小时，一段 GPS 一条线会被地图丢掉，路线就看不见了。
     * startIndex、endIndex 都是点的下标，包含两端。
     */
    fun groupRuns(segmentScales: List<Float>): List<RouteRun> {
        if (segmentScales.isEmpty()) return emptyList()
        val runs = mutableListOf<RouteRun>()
        var start = 0
        var scale = segmentScales[0]
        for (index in 1 until segmentScales.size) {
            if (segmentScales[index] != scale) {
                runs += RouteRun(scale, start, index)
                start = index
                scale = segmentScales[index]
            }
        }
        runs += RouteRun(scale, start, segmentScales.size)
        return runs
    }
}

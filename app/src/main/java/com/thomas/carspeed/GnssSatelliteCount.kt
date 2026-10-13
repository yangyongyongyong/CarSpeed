package com.thomas.carspeed

internal object GnssSatelliteCount {
    /** 当前定位解算实际用到的卫星数。 */
    fun usedInFix(satelliteCount: Int, usedInFix: (Int) -> Boolean): Int {
        if (satelliteCount <= 0) return 0
        var used = 0
        for (i in 0 until satelliteCount) {
            if (usedInFix(i)) used += 1
        }
        return used
    }

    /** 参与定位的卫星平均载噪比，单位 dB-Hz。没有可用卫星时为空。 */
    fun meanCn0DbHz(satelliteCount: Int, usedInFix: (Int) -> Boolean, cn0DbHz: (Int) -> Float): Float? {
        if (satelliteCount <= 0) return null
        var sum = 0.0
        var used = 0
        for (i in 0 until satelliteCount) {
            if (!usedInFix(i)) continue
            val cn0 = cn0DbHz(i)
            if (cn0 <= 0f) continue
            sum += cn0
            used += 1
        }
        return if (used == 0) null else (sum / used).toFloat()
    }
}

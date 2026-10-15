package com.thomas.carspeed

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 隧道和地面重叠时，地面仍能看到卫星，隧道里会突然看不到。
 * 卫星颗数或信号强度基本没变，说明还在地面；两者一起变差才当作进隧道。
 * 高架上少星不会改成隧道。
 */
internal class SatelliteTunnelTracker {
    private var lastHealthyMs: Long? = null
    private var baselineCount: Int? = null
    private var baselineCn0DbHz: Float? = null

    var scarce: Boolean = false
        private set

    /** 颗数基本没变，或信号强度基本没变。 */
    var skyConfirmsGround: Boolean = false
        private set

    fun update(timeMs: Long, satelliteCount: Int?, meanCn0DbHz: Float? = null) {
        val countStable = baselineCount?.let { base ->
            satelliteCount != null && satelliteCount >= base - countTolerance(base)
        } ?: false
        val strengthStable = baselineCn0DbHz?.let { base ->
            meanCn0DbHz != null && meanCn0DbHz >= base - STRENGTH_STABLE_DB
        } ?: false
        skyConfirmsGround = countStable || strengthStable

        val strengthOk = meanCn0DbHz == null || baselineCn0DbHz == null ||
            meanCn0DbHz >= baselineCn0DbHz!! - STRENGTH_STABLE_DB
        if (satelliteCount != null && satelliteCount >= HEALTHY_COUNT && strengthOk) {
            baselineCount = satelliteCount
            if (meanCn0DbHz != null) baselineCn0DbHz = meanCn0DbHz
            lastHealthyMs = timeMs
            scarce = false
            skyConfirmsGround = true
            return
        }
        if (skyConfirmsGround) {
            scarce = false
            return
        }
        if (scarce) return
        val healthyMs = lastHealthyMs ?: return
        if (timeMs - healthyMs > RECENT_HEALTHY_MS) return
        if (satelliteCount == null || satelliteCount <= SCARCE_COUNT) {
            scarce = true
        }
    }

    companion object {
        const val HEALTHY_COUNT = 6
        const val SCARCE_COUNT = 3
        const val RECENT_HEALTHY_MS = 30_000L
        const val STRENGTH_STABLE_DB = 6f

        fun countTolerance(baseline: Int): Int {
            return max(4, (baseline * 0.3f).roundToInt())
        }
    }
}

internal fun tunnelGpsWeak(
    hadGoodFix: Boolean,
    accuracyWeak: Boolean,
    fixStale: Boolean,
    satelliteScarce: Boolean,
    skyConfirmsGround: Boolean,
    satelliteTunnelEnabled: Boolean
): Boolean {
    if (satelliteTunnelEnabled && skyConfirmsGround) return false
    val satellites = satelliteTunnelEnabled && satelliteScarce
    if (!hadGoodFix && !satellites) return false
    return (hadGoodFix && accuracyWeak) || (hadGoodFix && fixStale) || satellites
}

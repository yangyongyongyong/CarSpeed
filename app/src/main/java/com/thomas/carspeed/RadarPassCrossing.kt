package com.thomas.carspeed

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 一帧 GPS 的判定结果，见 [RadarPassCrossing.onFix]。
 */
internal data class RadarPassPlan(
    /** 车到雷达在行进方向上的剩余米数；负数是已经在身后了。算不出来是 NaN。 */
    val alongMeters: Double,
    /** 这一帧就该响（贴到雷达了，或者刚开过去）。 */
    val fireNow: Boolean,
    /** 排定时器用的毫秒；null 表示这一帧先不排，等下一帧重算。 */
    val delayMs: Long?
)

/**
 * 「车正好开到雷达点」这一刻的判定。
 *
 * 高德的巡航回调有它自己的节拍：要等下一次回调报出「距离比刚才近了又变远」才认经过，
 * 那时候车已经开过去几十米，提示音自然落在雷达后面——听感就是「响的地方不是雷达的地方」。
 * 这里改成只看 GPS：每帧把雷达点投到车头的方向上，算出还剩多少米，按当前车速折成毫秒排进定时器，
 * 定时器到点前又拿最新一帧重算重排。所以车速在变也追得上，落点贴着雷达。
 *
 * 用「行进方向上的剩余距离」而不是直线距离，是因为高德报的雷达点横向可能偏几十米；
 * 投到车头方向上以后，横向偏差被摊掉，车开到雷达正侧方就是 0。
 */
internal class RadarPassCrossing {
    /** 上一次算过的雷达。换了就重新开始量，不能拿上一条的距离比。 */
    private var key: String? = null
    /** 这一条雷达有没有确认过「还在前面」。没确认过就不许判到点，免得一上来就补响一声。 */
    private var armed = false
    /** 上一次算出的、行进方向上的剩余米数，用来确认车是在靠近还是在远离。 */
    private var lastAlongMeters = Double.NaN

    fun reset() {
        key = null
        armed = false
        lastAlongMeters = Double.NaN
    }

    /**
     * 拿一帧 GPS 算一次。
     *
     * @param bearingDegrees 行进方向。给不出就传 NaN，此时退化成按直线距离估。
     * @param speedKmh 当前车速。
     * @param fixAgeMs 这一帧 GPS 距今多久。外推靠它把车补到「现在」，
     *   否则定时器到点重算时拿到的还是旧位置，一直差半拍。
     */
    fun onFix(
        key: String,
        carLatitude: Double,
        carLongitude: Double,
        bearingDegrees: Float,
        speedKmh: Float,
        fixAgeMs: Long,
        radarLatitude: Double,
        radarLongitude: Double
    ): RadarPassPlan {
        if (this.key != key) {
            this.key = key
            armed = false
            lastAlongMeters = Double.NaN
        }
        if (!RadarBroadcastFix.isPosition(carLatitude, carLongitude) ||
            !RadarBroadcastFix.isPosition(radarLatitude, radarLongitude)
        ) {
            lastAlongMeters = Double.NaN
            return RadarPassPlan(Double.NaN, false, null)
        }
        val usableSpeed = speedKmh.isFinite() && speedKmh > 0f
        val mps = if (usableSpeed) speedKmh / 3.6f else 0f
        // 车往前挪了多久，就沿行进方向把位置补多远。几秒内按直线走够用了。
        // 超过 MAX_FIX_AGE_MS 就不补了：那一刻的定位已经不能代表现在，宁可少补也别补过头。
        val advancedMeters = if (usableSpeed && bearingDegrees in 0f..360f && fixAgeMs in 1..MAX_FIX_AGE_MS) {
            (mps * fixAgeMs / 1000.0).roundToInt()
        } else {
            0
        }
        var carLat = carLatitude
        var carLon = carLongitude
        if (advancedMeters > 0) {
            val advanced = RadarBroadcastFix.project(
                carLatitude,
                carLongitude,
                bearingDegrees,
                advancedMeters
            )
            if (advanced != null) {
                carLat = advanced.first
                carLon = advanced.second
            }
        }
        val straight = RadarAlertLog.distanceMeters(carLat, carLon, radarLatitude, radarLongitude)
            ?.toDouble()
            ?: return RadarPassPlan(Double.NaN, false, null)
        // 行进方向上的剩余米数。给不出方向就退回直线距离——会稍早一点，但不会晚。
        val along = if (bearingDegrees in 0f..360f) {
            val delta = angleDelta(
                bearingDegrees.toDouble(),
                initialBearing(carLat, carLon, radarLatitude, radarLongitude)
            )
            straight * cos(Math.toRadians(delta))
        } else {
            straight
        }
        if (along > ARRIVE_METERS) armed = true
        val previousAlong = lastAlongMeters
        if (armed && along <= ARRIVE_METERS) {
            // 已经开过雷达点：要么从前面贴近来的（上一帧还有距离、这一帧掉到 0 以下），
            // 要么本来就贴着。后者可能是刚锁上就停在这儿，不补响。
            val behind = along < 0.0 &&
                previousAlong.isFinite() && previousAlong > along + RECEDE_METERS
            if (along >= 0.0 || behind) {
                lastAlongMeters = along
                return RadarPassPlan(along, true, null)
            }
        }
        lastAlongMeters = along
        if (!armed) return RadarPassPlan(along, false, null)
        if (mps < MIN_SPEED_MPS) return RadarPassPlan(along, false, null)
        val delayMs = (along / mps * 1000.0).roundToLong()
        // 还早就不排：排太远车速一变就偏，等下一帧重算更准。
        if (delayMs > MAX_DELAY_MS) return RadarPassPlan(along, false, null)
        return RadarPassPlan(along, false, delayMs.coerceAtLeast(0L))
    }

    private fun initialBearing(
        latitude: Double,
        longitude: Double,
        otherLatitude: Double,
        otherLongitude: Double
    ): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(otherLatitude)
        val dLon = Math.toRadians(otherLongitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return Math.toDegrees(atan2(y, x))
    }

    /** 两个角度的差，落在 -180..180。 */
    private fun angleDelta(from: Double, to: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    private companion object {
        /**
         * 沿行进方向贴到这么近就算到了。GPS 本身有几米误差，再抠小没意义。
         * 按 100km/h 算，8 米折合不到 0.3 秒，听感上就是雷达跟前。
         */
        const val ARRIVE_METERS = 8.0
        /** 低于这个速度就别排定时器了：车没动，等它动起来再算。 */
        const val MIN_SPEED_MPS = 0.5f
        /** 一次最多往后排这么久，再远就等下一帧。压在一帧 GPS 上下，中途减速也偏不了多少。 */
        const val MAX_DELAY_MS = 1_500L
        /** 距离要回弹这么多才算真的在往后走，压住 GPS 抖动。 */
        const val RECEDE_METERS = 3.0
        /** 定位超过这么久就不按车速外推了，旧位置补出来的点会跑偏。 */
        const val MAX_FIX_AGE_MS = 3_000L
    }
}

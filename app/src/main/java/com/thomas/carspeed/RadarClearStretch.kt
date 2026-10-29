package com.thomas.carspeed

import java.util.Locale

/**
 * 「前方一段路没有测速雷达」的播报规则。
 *
 * 需求：刚经过一个雷达后，如果后面 N 米内都没有雷达，就隔一段时间提醒一次还剩多少米没雷达；
 * 一直播到前方又出现雷达，或剩余不足 100 米为止。
 *
 * 全部做成纯函数，是为了能把这些边界一条条测出来——这种循环播报最容易出的错就是
 * 「到了雷达还在喊」「剩余 20 米还在报 0.0 公里」，靠实车是碰不全的。
 */
internal object RadarClearStretch {
    const val DEFAULT_SPAN_METERS = 5000
    const val DEFAULT_INTERVAL_SECONDS = 5
    /** 低于这个剩余就收尾，没必要在雷达眼前还喊「100 米内无测速」。 */
    const val STOP_BELOW_METERS = 100
    const val MIN_SPAN_METERS = 500
    const val MAX_SPAN_METERS = 50_000
    const val MIN_INTERVAL_SECONDS = 3
    const val MAX_INTERVAL_SECONDS = 120

    data class Settings(
        val enabled: Boolean = false,
        val spanMeters: Int = DEFAULT_SPAN_METERS,
        val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
        /** 只用来在设置页估算秒数，不参与播报节奏。 */
        val referenceSpeedKmh: Int = 100
    ) {
        val intervalMs: Long
            get() = intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS) * 1000L
    }

    /**
     * 一次播报循环的状态。remainingMeters 是「到下一个雷达还有多远」，
     * 因为要播的是「还剩多少没雷达」，所以剩得越少，播的公里数越小。
     */
    data class Session(
        val startedAtMs: Long,
        val spanMeters: Int,
        val lastSpokenAtMs: Long,
        val lastSpokenMeters: Int
    )

    /**
     * 刚过去一个雷达，看看后面这一段值不值得开一场循环播报。
     *
     * @param nextRadarMeters 下一个雷达还有多远；null 表示后面整段都没查到雷达，
     *        按 spanMeters 满额播报——这正是「连续 5 公里没有测速」的情形。
     */
    fun shouldStart(nextRadarMeters: Int?, settings: Settings): Boolean {
        if (!settings.enabled) return false
        val span = settings.spanMeters.coerceIn(MIN_SPAN_METERS, MAX_SPAN_METERS)
        // 后面没有雷达：整段都是空路，值得报。
        if (nextRadarMeters == null) return span >= STOP_BELOW_METERS
        // 有雷达但离得比门槛还近：这段根本不算「没有雷达的路」，不报。
        if (nextRadarMeters < span) return false
        return span >= STOP_BELOW_METERS
    }

    /**
     * 该不该开口了。距离上一次播报过了 interval 才再播，
     * 这样每 5 秒（可配）一句，不会因为回调频繁而连珠炮。
     */
    fun shouldSpeakNow(session: Session, remainingMeters: Int, nowMs: Long, settings: Settings): Boolean {
        if (remainingMeters < STOP_BELOW_METERS) return false
        if (session.lastSpokenAtMs <= 0L) return true
        return nowMs - session.lastSpokenAtMs >= settings.intervalMs
    }

    /**
     * 这场播报该不该结束。
     *
     * 三种收尾：剩余不到 100 米、前方又出现雷达预警、超出了原本要报的跨度。
     * 超出跨度也要停，否则用户设了 5 公里，路一路空下去会一直报下去。
     */
    fun shouldStop(
        session: Session,
        remainingMeters: Int,
        nowMs: Long
    ): Boolean {
        if (remainingMeters < STOP_BELOW_METERS) return true
        return nowMs - session.startedAtMs > MAX_SESSION_MS
    }

    /**
     * 空路的真实尽头是前方最近的一部摄像头。
     * 按时间扣出来的剩余不能越过它，否则就会出现「刚说还有 4 公里没测速，下一秒却开始报摄像头」。
     */
    fun clampRemaining(remainingMeters: Int, nextRadarMeters: Int?): Int {
        if (nextRadarMeters == null) return remainingMeters
        return minOf(remainingMeters, nextRadarMeters)
    }

    /** 一场循环最长播 30 分钟，防止导航卡住时空路段无限播下去。 */
    private const val MAX_SESSION_MS = 30 * 60 * 1000L

    /**
     * 播报文案。超过 1 公里用公里，否则用米——5 公里路上中段播「4.3 公里」比「4300 米」好懂，
     * 而只剩 300 米时播「0.3 公里」反而别扭。
     */
    fun text(remainingMeters: Int): String {
        val meters = remainingMeters.coerceAtLeast(0)
        return if (meters >= 1000) {
            val km = meters / 1000.0
            // 一位小数就够，多了听着啰嗦；整公里时去掉「.0」。
            val shown = if (km == km.toInt().toDouble()) {
                "${km.toInt()}"
            } else {
                String.format(Locale.US, "%.1f", km)
            }
            "${shown}公里内无测速"
        } else {
            "${meters}米内无测速"
        }
    }

    /**
     * 给设置页用：按输入的速度，跑完这段距离要多少秒。
     * 纯粹是让用户有个直观感觉——「5 公里、150 km/h ≈ 120 秒」，
     * 不参与任何播报计算，所以可以放心改速度而不影响行为。
     */
    fun estimatedSeconds(distanceMeters: Int, speedKmh: Int): Int? {
        if (distanceMeters <= 0 || speedKmh <= 0) return null
        val metersPerSecond = speedKmh / 3.6
        return kotlin.math.round(distanceMeters / metersPerSecond).toInt()
    }
}

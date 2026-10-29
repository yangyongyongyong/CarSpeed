package com.thomas.carspeed

/**
 * 历史回放的轨迹图平时跟着「当前位置」居中。
 * 用户拖动或缩放地图是为了看某一段细节，这时不能马上把镜头抢回去，
 * 从他松手起 10 秒内一律不动，到点再回到自动居中。期间再触摸会重新计时。
 */
internal object MapCameraFollowHold {
    const val HOLD_MS = 10_000L

    /** 距离恢复自动居中还剩多久；已到点返回 0。 */
    fun remainingMs(gestureEndedAtMs: Long, nowMs: Long): Long =
        (gestureEndedAtMs + HOLD_MS - nowMs).coerceIn(0L, HOLD_MS)

    fun shouldResume(gestureEndedAtMs: Long, nowMs: Long): Boolean =
        remainingMs(gestureEndedAtMs, nowMs) == 0L
}

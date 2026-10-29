package com.thomas.carspeed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 蓝牙断开那一刻要判断"车还在不在跑"，得先有个可信的当前车速。
 * 录像服务每秒都在收定位，直接用它手上的那份最准；服务已经收尾时才退化到问系统要最后一次已知位置。
 * 两种都拿不到就返回 null——调用方会当成"还在行驶"，宁可多录一段，也不要因为读不到速度就切掉录像。
 */
internal object TriggerSpeed {
    /** 超过这个岁数的旧定位不能代表"现在停车了"，高速上十几秒就能跑出去几百米。 */
    const val MAX_AGE_MS = 15_000L

    fun currentKmh(context: Context): Float? {
        DashcamService.liveSpeedKmh()?.let { return it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstNotNullOfOrNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            } ?: return null
        return kmhIfFresh(
            roomSpeedMps = if (location.hasSpeed()) location.speed else null,
            fixElapsedMs = location.elapsedRealtimeNanos / 1_000_000L,
            nowElapsedMs = SystemClock.elapsedRealtime()
        )
    }

    /** 旧定位或没有速度字段时返回 null，交给上层按"还在行驶"处理。 */
    fun kmhIfFresh(roomSpeedMps: Float?, fixElapsedMs: Long, nowElapsedMs: Long): Float? {
        if (roomSpeedMps == null) return null
        if (fixElapsedMs <= 0L) return null
        val ageMs = nowElapsedMs - fixElapsedMs
        // 时间戳在未来说明这条定位不可信，和过期一样不采用。
        if (ageMs < 0L || ageMs > MAX_AGE_MS) return null
        return SpeedRules.toKmhClamped(roomSpeedMps)
    }
}

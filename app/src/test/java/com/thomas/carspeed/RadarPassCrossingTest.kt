package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarPassCrossingTest {

    /** 车在原点，雷达点在车头方向 [distanceMeters] 米处。 */
    private fun target(distanceMeters: Int, bearing: Float): Pair<Double, Double> =
        RadarBroadcastFix.project(CAR_LAT, CAR_LON, bearing, distanceMeters)
            ?: error("project failed")

    /** 车头朝正北，往前挪了 [distanceMeters] 米（负数是退到雷达后面）。 */
    private fun carAt(distanceMeters: Int): Pair<Double, Double> = target(distanceMeters, 0f)

    private fun plan(
        crossing: RadarPassCrossing,
        carMeters: Int,
        radarMeters: Int,
        speedKmh: Float = 72f,
        fixAgeMs: Long = 0L,
        key: String = "cam"
    ): RadarPassPlan {
        val car = carAt(carMeters)
        val radar = target(radarMeters, 0f)
        return crossing.onFix(
            key = key,
            carLatitude = car.first,
            carLongitude = car.second,
            bearingDegrees = 0f,
            speedKmh = speedKmh,
            fixAgeMs = fixAgeMs,
            radarLatitude = radar.first,
            radarLongitude = radar.second
        )
    }

    @Test
    fun `远的时候只排定时器不响`() {
        val crossing = RadarPassCrossing()
        val (radarLat, radarLon) = target(200, 0f)
        val result = crossing.onFix(
            key = "cam",
            carLatitude = CAR_LAT,
            carLongitude = CAR_LON,
            bearingDegrees = 0f,
            speedKmh = 72f, // 20 m/s
            fixAgeMs = 0L,
            radarLatitude = radarLat,
            radarLongitude = radarLon
        )
        assertFalse(result.fireNow)
        assertEquals(200.0, result.alongMeters, 3.0)
        // 200 米 / 20 m/s = 10 秒，太远，先不排。
        assertNull(result.delayMs)
    }

    @Test
    fun `近距离按车速折成毫秒`() {
        val crossing = RadarPassCrossing()
        // 先让它量到「还在前面」。
        plan(crossing, carMeters = 0, radarMeters = 120)
        val result = plan(crossing, carMeters = 100, radarMeters = 120, speedKmh = 72f)
        assertFalse(result.fireNow)
        assertEquals(20.0, result.alongMeters, 3.0)
        // 20 米 / 20 m/s = 1 秒。距离是进整过的，允许几十毫秒的差。
        assertTrue("delay=${result.delayMs}", (result.delayMs ?: -1L) in 900L..1_100L)
    }

    @Test
    fun `太远不排，交给下一帧重算`() {
        val crossing = RadarPassCrossing()
        plan(crossing, carMeters = 0, radarMeters = 120)
        // 60 米 / 20 m/s = 3 秒，超过单次排程上限。
        val result = plan(crossing, carMeters = 60, radarMeters = 120, speedKmh = 72f)
        assertFalse(result.fireNow)
        assertNull(result.delayMs)
    }

    @Test
    fun `贴到雷达跟前就立刻响`() {
        val crossing = RadarPassCrossing()
        plan(crossing, carMeters = 0, radarMeters = 120)
        val result = plan(crossing, carMeters = 115, radarMeters = 120, speedKmh = 36f)
        assertTrue(result.fireNow)
        assertNull(result.delayMs)
    }

    @Test
    fun `开过头了也立刻响`() {
        val crossing = RadarPassCrossing()
        plan(crossing, carMeters = 0, radarMeters = 120)
        val result = plan(crossing, carMeters = 140, radarMeters = 120)
        assertTrue(result.fireNow)
        assertEquals(-20.0, result.alongMeters, 3.0)
    }

    @Test
    fun `横向偏出去的雷达点仍按行进方向算`() {
        val crossing = RadarPassCrossing()
        // 雷达点横向偏到正东 60 米：车开到它正侧方时，行进方向上的剩余就是 0。
        val radar = target(60, 90f)
        val result = crossing.onFix(
            key = "cam",
            carLatitude = CAR_LAT,
            carLongitude = CAR_LON,
            bearingDegrees = 0f,
            speedKmh = 72f,
            fixAgeMs = 0L,
            radarLatitude = radar.first,
            radarLongitude = radar.second
        )
        assertEquals(0.0, result.alongMeters, 2.0)
        assertFalse(result.fireNow)
    }

    @Test
    fun `第一帧就贴着雷达也不许补响`() {
        val crossing = RadarPassCrossing()
        // 锁上的时候车已经贴着雷达了，没人确认过「刚才还在前面」。
        val result = plan(crossing, carMeters = 115, radarMeters = 120)
        assertFalse(result.fireNow)
    }

    @Test
    fun `刚锁上就已经在雷达后面也不许补响`() {
        val crossing = RadarPassCrossing()
        // 雷达点是高德在高架/平行路场景下补的，可能落在车后。
        val result = plan(crossing, carMeters = 130, radarMeters = 120)
        assertFalse(result.fireNow)
    }

    @Test
    fun `换了一条雷达重新量`() {
        val crossing = RadarPassCrossing()
        plan(crossing, carMeters = 0, radarMeters = 120, key = "cam-a")
        // 新的一条，即便车已经贴着它，也要等它先确认在前面。
        val result = plan(crossing, carMeters = 115, radarMeters = 120, key = "cam-b")
        assertFalse(result.fireNow)
    }

    @Test
    fun `GPS 位置补到当前时刻`() {
        val crossing = RadarPassCrossing()
        plan(crossing, carMeters = 0, radarMeters = 120)
        // 这一帧是 1 秒前的，按 20 m/s 补 20 米，实际只剩 20 米。
        val result = plan(crossing, carMeters = 80, radarMeters = 120, fixAgeMs = 1_000L)
        assertEquals(20.0, result.alongMeters, 3.0)
        assertTrue("delay=${result.delayMs}", (result.delayMs ?: -1L) in 900L..1_100L)
    }

    @Test
    fun `没定位时不排`() {
        val crossing = RadarPassCrossing()
        val (radarLat, radarLon) = target(80, 0f)
        val result = crossing.onFix(
            key = "cam",
            carLatitude = Double.NaN,
            carLongitude = Double.NaN,
            bearingDegrees = 0f,
            speedKmh = 72f,
            fixAgeMs = 0L,
            radarLatitude = radarLat,
            radarLongitude = radarLon
        )
        assertFalse(result.fireNow)
        assertNull(result.delayMs)
        assertTrue(result.alongMeters.isNaN())
    }

    private companion object {
        /** 北京附近的一个点，够真就行。 */
        const val CAR_LAT = 39.9042
        const val CAR_LON = 116.4074
    }
}

package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

/** 设置页存储圆环的百分比计算，重点是不出现负数、超过 100% 和除零。 */
class StorageUsageTest {
    private fun gib(value: Long) = value * 1024L * 1024L * 1024L

    @Test
    fun twenty_gb_quota_with_five_used_is_quarter() {
        val usage = VideoStorage.StorageUsage(usedBytes = gib(5), limitBytes = gib(20))
        assertEquals(25, usage.percent)
    }

    @Test
    fun empty_usage_is_zero_percent() {
        val usage = VideoStorage.StorageUsage(usedBytes = 0L, limitBytes = gib(20))
        assertEquals(0, usage.percent)
    }

    @Test
    fun usage_above_quota_is_capped_at_hundred() {
        val usage = VideoStorage.StorageUsage(usedBytes = gib(30), limitBytes = gib(20))
        assertEquals(100, usage.percent)
    }

    @Test
    fun zero_quota_is_safe() {
        val usage = VideoStorage.StorageUsage(usedBytes = gib(1), limitBytes = 0L)
        assertEquals(0, usage.percent)
    }

    @Test
    fun negative_used_bytes_never_produce_negative_percent() {
        val usage = VideoStorage.StorageUsage(usedBytes = -1L, limitBytes = gib(20))
        assertEquals(0, usage.percent)
    }

    @Test
    fun percent_truncates_instead_of_rounding_up() {
        // 19.9% 应该显示 19%，避免还没到 20% 就提前跳数。
        val usage = VideoStorage.StorageUsage(usedBytes = gib(20) * 199 / 1000, limitBytes = gib(20))
        assertEquals(19, usage.percent)
    }
}

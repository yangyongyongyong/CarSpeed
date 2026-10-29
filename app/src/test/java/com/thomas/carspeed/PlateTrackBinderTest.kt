package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateTrackBinderTest {
    private fun plate(code: String, confidence: Float = 1.0f, x: Float = 0f, y: Float = 0f): PlateRecognitionResult {
        return PlateRecognitionResult(
            code = code,
            type = -1,
            typeLabel = "OCR候选",
            confidence = confidence,
            x1 = x,
            y1 = y,
            x2 = x + 120f,
            y2 = y + 30f
        )
    }

    private fun sample(elapsedMs: Long, vararg plates: PlateRecognitionResult): PlateRecognitionSample {
        return PlateRecognitionSample(
            elapsedMs = elapsedMs,
            timeMs = elapsedMs,
            imageWidth = 1280,
            imageHeight = 720,
            rotationDegrees = 0,
            plates = plates.toList()
        )
    }

    @Test
    fun one_car_on_a_walk_keeps_a_single_plate_for_the_whole_track() {
        // 同一辆车横向移动，中间帧读错一个字，最后几步读对了两次。
        // 逐帧显示会来回跳号；绑定后整趟都应该是出现最多的那个。
        val samples = listOf(
            sample(0, plate("浙A12345", 1.0f, 200f, 300f)),
            sample(1_000, plate("浙A12346", 0.55f, 260f, 300f)),
            sample(2_000, plate("浙A12345", 1.0f, 320f, 300f)),
            sample(3_000, plate("浙A1Z345", 0.55f, 380f, 300f)),
            sample(4_000, plate("浙A12345", 1.0f, 440f, 300f))
        )

        val bound = PlateTrackBinder.bindAll(samples)

        bound.forEach { sample ->
            assertEquals(1, sample.plates.size)
            assertEquals("浙A12345", sample.plates.first().code)
        }
    }

    @Test
    fun strict_hit_outweighs_more_loose_hits() {
        // 兜底读数出现 3 次，严格命中只有 1 次；按来源强弱加权，严格的赢。
        val samples = listOf(
            sample(0, plate("浙A12345", 1.0f, 200f, 300f)),
            sample(1_000, plate("浙A12346", 0.55f, 240f, 300f)),
            sample(2_000, plate("浙A12347", 0.55f, 280f, 300f)),
            sample(3_000, plate("浙A12346", 0.55f, 320f, 300f))
        )

        val bound = PlateTrackBinder.bindAll(samples)

        bound.forEach { assertEquals("浙A12345", it.plates.first().code) }
    }

    @Test
    fun two_cars_kept_apart_get_their_own_plate() {
        // 左侧一辆、右侧一辆，各自稳定。不能因为同一帧里有两个号就串成一辆车。
        val samples = listOf(
            sample(0, plate("浙A11111", 1.0f, 100f, 300f), plate("苏B22222", 1.0f, 900f, 300f)),
            sample(1_000, plate("浙A11111", 1.0f, 110f, 300f), plate("苏B22222", 1.0f, 890f, 300f)),
            sample(2_000, plate("浙A11111", 1.0f, 120f, 300f), plate("苏B22222", 1.0f, 880f, 300f))
        )

        val bound = PlateTrackBinder.bindAll(samples)

        bound.forEach { sample ->
            val codes = sample.plates.map { it.code }.toSet()
            assertEquals(setOf("浙A11111", "苏B22222"), codes)
        }
    }

    @Test
    fun a_far_away_later_sighting_does_not_reuse_the_old_track() {
        // 相隔 30 秒、位置也远：这是另一辆车，不能并进上一条轨迹去改它的号。
        val samples = listOf(
            sample(0, plate("浙A12345", 1.0f, 100f, 300f)),
            sample(1_000, plate("浙A12345", 1.0f, 110f, 300f)),
            sample(31_000, plate("浙A99999", 1.0f, 900f, 600f)),
            sample(32_000, plate("浙A99999", 1.0f, 905f, 600f))
        )

        val bound = PlateTrackBinder.bindAll(samples)

        assertEquals("浙A12345", bound[0].plates.first().code)
        assertEquals("浙A12345", bound[1].plates.first().code)
        assertEquals("浙A99999", bound[2].plates.first().code)
        assertEquals("浙A99999", bound[3].plates.first().code)
    }

    @Test
    fun realtime_stabilizer_waits_for_a_second_reading_before_overriding() {
        val tracks = mutableListOf<PlateTrackBinder.Track>()

        val first = PlateTrackBinder.stabilize(tracks, sample(0, plate("浙A12345", 1.0f, 200f, 300f)))
        // 第一次读数不做覆盖，先按原样显示。
        assertEquals("浙A12345", first.plates.first().code)

        PlateTrackBinder.stabilize(tracks, sample(1_000, plate("浙A12345", 1.0f, 240f, 300f)))
        // 同一辆车已经确认过「浙A12345」，后面读错的那一帧要跟着显示成它，画面不再跳动。
        val wrong = PlateTrackBinder.stabilize(tracks, sample(2_000, plate("浙A12346", 0.55f, 280f, 300f)))

        assertEquals("浙A12345", wrong.plates.first().code)
    }

    @Test
    fun samples_without_plates_pass_through_untouched() {
        val empty = sample(0)
        val bound = PlateTrackBinder.bindAll(listOf(empty))
        assertTrue(bound.first().plates.isEmpty())
    }
}

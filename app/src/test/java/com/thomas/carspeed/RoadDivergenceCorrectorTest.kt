package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadDivergenceCorrectorTest {
    @Test
    fun left_turn_matches_the_elevated_road() {
        val gps = straightThenLeft()
        val layer = RoadDivergenceCorrector.matchLayer(
            enabled = true,
            pitchFlat = true,
            gpsBearings = gps,
            paths = listOf(
                RoadBearingPath(RoadLayer.ELEVATED, gps),
                RoadBearingPath(RoadLayer.GROUND, List(gps.size) { 0f })
            )
        )
        assertEquals(RoadLayer.ELEVATED, layer)
    }

    @Test
    fun staying_straight_matches_the_ground_road() {
        val gps = List(12) { 0f }
        val layer = RoadDivergenceCorrector.matchLayer(
            enabled = true,
            pitchFlat = true,
            gpsBearings = gps,
            paths = listOf(
                RoadBearingPath(RoadLayer.ELEVATED, straightThenLeft()),
                RoadBearingPath(RoadLayer.GROUND, gps)
            )
        )
        assertEquals(RoadLayer.GROUND, layer)
    }

    @Test
    fun similar_paths_do_not_change_the_layer() {
        val gps = straightThenLeft()
        val almost = gps.mapIndexed { index, bearing -> if (index >= 8) bearing + 10f else bearing }
        val layer = RoadDivergenceCorrector.matchLayer(
            enabled = true,
            pitchFlat = true,
            gpsBearings = gps,
            paths = listOf(
                RoadBearingPath(RoadLayer.ELEVATED, gps),
                RoadBearingPath(RoadLayer.GROUND, almost)
            )
        )
        assertNull(layer)
    }

    @Test
    fun disabled_or_sloped_samples_do_not_match() {
        val gps = straightThenLeft()
        val paths = listOf(
            RoadBearingPath(RoadLayer.ELEVATED, gps),
            RoadBearingPath(RoadLayer.GROUND, List(gps.size) { 0f })
        )
        assertNull(RoadDivergenceCorrector.matchLayer(enabled = false, pitchFlat = true, gpsBearings = gps, paths = paths))
        assertNull(RoadDivergenceCorrector.matchLayer(enabled = true, pitchFlat = false, gpsBearings = gps, paths = paths))
    }

    @Test
    fun tracker_rewrites_the_overlap_from_where_it_started() {
        val tracker = RoadDivergenceTracker()
        val gps = straightThenLeft()
        var correction: DivergenceCorrection? = null
        gps.forEachIndexed { index, bearing ->
            val count = index + 1
            val result = tracker.update(
                sample(
                    timeMs = index * 1_000L,
                    bearing = bearing,
                    paths = listOf(
                        RoadBearingPath(RoadLayer.ELEVATED, gps.take(count)),
                        RoadBearingPath(RoadLayer.GROUND, List(count) { 0f })
                    )
                )
            )
            if (index < 8) assertNull(result)
            if (result != null) correction = result
        }
        assertEquals(RoadLayer.ELEVATED, correction?.layer)
        assertEquals(0L, correction?.fromTimeMs)
    }

    @Test
    fun separated_elevated_match_rewrites_an_earlier_ground_guess() {
        val tracker = RoadDivergenceTracker()
        var correction: DivergenceCorrection? = null
        for (timeMs in 0L..5_000L step 1_000L) {
            assertNull(tracker.update(sample(timeMs, bearing = 0f, flag = RoadLayerAlertGate.ELEVATED_UNDER)))
        }
        for (timeMs in 6_000L..10_000L step 1_000L) {
            val result = tracker.update(sample(timeMs, bearing = 0f, flag = RoadLayerAlertGate.ELEVATED_ON))
            if (result != null) correction = result
        }
        assertEquals(RoadLayer.ELEVATED, correction?.layer)
        assertEquals(0L, correction?.fromTimeMs)
    }

    @Test
    fun our_own_parallel_switch_is_not_evidence() {
        val tracker = RoadDivergenceTracker()
        for (timeMs in 0L..5_000L step 1_000L) {
            tracker.update(sample(timeMs, bearing = 0f, flag = RoadLayerAlertGate.ELEVATED_UNDER))
        }
        tracker.noteOwnSwitch(5_500L)
        for (timeMs in 6_000L..12_000L step 1_000L) {
            assertNull(tracker.update(sample(timeMs, bearing = 0f, flag = RoadLayerAlertGate.ELEVATED_ON)))
        }
    }

    @Test
    fun tunnel_is_not_rewritten_to_ground() {
        val tracker = RoadDivergenceTracker()
        for (timeMs in 0L..5_000L step 1_000L) {
            tracker.update(
                sample(
                    timeMs,
                    bearing = 0f,
                    assumed = RoadLayer.TUNNEL,
                    provisional = false,
                    flag = RoadLayerAlertGate.ELEVATED_ON
                )
            )
        }
        for (timeMs in 6_000L..10_000L step 1_000L) {
            assertNull(
                tracker.update(
                    sample(
                        timeMs,
                        bearing = 0f,
                        assumed = RoadLayer.TUNNEL,
                        provisional = false,
                        flag = RoadLayerAlertGate.ELEVATED_UNDER
                    )
                )
            )
        }
    }

    @Test
    fun slope_does_not_rewrite_the_overlap_before_it() {
        val tracker = RoadDivergenceTracker()
        repeat(6) { index ->
            assertNull(
                tracker.update(
                    sample(
                        timeMs = index * 1_000L,
                        bearing = 0f,
                        paths = listOf(
                            RoadBearingPath(RoadLayer.ELEVATED, List(index + 1) { 0f }),
                            RoadBearingPath(RoadLayer.GROUND, List(index + 1) { 0f })
                        )
                    )
                )
            )
        }
        assertNull(tracker.update(sample(timeMs = 6_000L, bearing = 0f, pitchFlat = false)))

        val gps = straightThenLeft()
        var correction: DivergenceCorrection? = null
        gps.forEachIndexed { index, bearing ->
            val count = index + 1
            val result = tracker.update(
                sample(
                    timeMs = 7_000L + index * 1_000L,
                    bearing = bearing,
                    paths = listOf(
                        RoadBearingPath(RoadLayer.ELEVATED, gps.take(count)),
                        RoadBearingPath(RoadLayer.GROUND, List(count) { 0f })
                    )
                )
            )
            if (result != null) correction = result
        }
        assertEquals(RoadLayer.ELEVATED, correction?.layer)
        assertEquals(7_000L, correction?.fromTimeMs)
    }

    @Test
    fun subtitle_rewrite_keeps_cues_from_before_the_overlap_split() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n12.0 km/h  |  桥下\n\n" +
            "2\n00:00:09,000 --> 00:00:10,000\n12.0 km/h  |  桥下\n\n" +
            "3\n00:00:12,000 --> 00:00:13,000\n12.0 km/h  |  隧道\n\n"
        val elevated = SrtRoadLayerRewrite.rewrite(text, 9_000L, RoadLayerLabels.ON_BRIDGE)
        val beforeSplit = elevated.substringBefore("2\n")
        val afterSplit = elevated.substringAfter("2\n")
        assertTrue(beforeSplit.contains("桥下"))
        assertFalse(beforeSplit.contains("桥上"))
        assertTrue(afterSplit.substringBefore("3\n").contains("12.0 km/h  |  桥上"))
        assertTrue(afterSplit.substringAfter("3\n").contains("桥上"))
        assertFalse(afterSplit.substringAfter("3\n").contains("隧道"))

        val ground = SrtRoadLayerRewrite.rewrite(text, 0L, RoadLayerLabels.UNDER_BRIDGE)
        assertTrue(ground.contains("桥下"))
        assertTrue(ground.contains("隧道"))
        assertFalse(ground.contains("桥上"))
    }

    @Test
    fun subtitle_rewrite_updates_a_layer_suffix_on_the_place_line() {
        val text = "1\n00:00:09,000 --> 00:00:10,000\n杭州市 秋涛路 桥下\n\n"
        val elevated = SrtRoadLayerRewrite.rewrite(text, 0L, RoadLayerLabels.ON_BRIDGE)
        assertTrue(elevated.contains("杭州市 秋涛路 桥上"))
        val tunnel = SrtRoadLayerRewrite.rewrite(
            "1\n00:00:09,000 --> 00:00:10,000\n杭州市 秋涛路 隧道\n\n",
            0L,
            RoadLayerLabels.UNDER_BRIDGE
        )
        assertTrue(tunnel.contains("杭州市 秋涛路 隧道"))
        assertFalse(tunnel.contains("桥下"))
    }

    private fun straightThenLeft(): List<Float> {
        return List(8) { 0f } + listOf(330f, 300f, 270f, 240f)
    }

    private fun sample(
        timeMs: Long,
        bearing: Float?,
        pitchFlat: Boolean = true,
        assumed: RoadLayer = RoadLayer.GROUND,
        provisional: Boolean = true,
        flag: Int = RoadLayerAlertGate.ELEVATED_ON,
        enabled: Boolean = true,
        paths: List<RoadBearingPath> = emptyList()
    ): DivergenceSample {
        return DivergenceSample(
            timeMs = timeMs,
            bearingDegrees = bearing,
            pitchFlat = pitchFlat,
            assumedLayer = assumed,
            provisional = provisional,
            elevatedFlag = flag,
            enabled = enabled,
            paths = paths
        )
    }
}

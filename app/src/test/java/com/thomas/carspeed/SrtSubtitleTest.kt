package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SrtSubtitleTest {
    @Test
    fun cueAtHoldsPreviousCueAcrossSmallGap() {
        val previous = SrtCue(startMs = 0L, endMs = 1_000L, text = "上一条")
        val next = SrtCue(startMs = 2_000L, endMs = 3_000L, text = "下一条")

        assertEquals(previous, SrtSubtitle.cueAt(listOf(previous, next), 1_400L, holdGapMs = 1_500L))
    }

    @Test
    fun cueAtDoesNotHoldPreviousCueAcrossLargeGap() {
        val previous = SrtCue(startMs = 0L, endMs = 1_000L, text = "上一条")
        val next = SrtCue(startMs = 4_000L, endMs = 5_000L, text = "下一条")

        assertNull(SrtSubtitle.cueAt(listOf(previous, next), 2_600L, holdGapMs = 1_500L))
    }

    @Test
    fun cueAtPrefersActiveCueOverHeldPreviousCue() {
        val previous = SrtCue(startMs = 0L, endMs = 1_500L, text = "上一条")
        val active = SrtCue(startMs = 1_000L, endMs = 2_000L, text = "当前条")

        assertEquals(active, SrtSubtitle.cueAt(listOf(previous, active), 1_600L, holdGapMs = 1_500L))
    }

    @Test
    fun cueAtMatchesLinearLookupForSortedAndOverlappingCues() {
        val random = java.util.Random(42L)
        repeat(200) { round ->
            var start = 0L
            val cues = List(1 + random.nextInt(40)) { index ->
                start += random.nextInt(1_500).toLong()
                val duration = 1L + random.nextInt(if (round % 5 == 0) 6_000 else 1_200)
                SrtCue(start, start + duration, "c$index")
            }
            val shuffled = if (round % 7 == 0) cues.shuffled(random) else cues
            for (t in -500L..(start + 8_000L) step 37L) {
                for (hold in longArrayOf(0L, 1_500L)) {
                    assertEquals(
                        SrtSubtitle.linearCueAt(shuffled, t, hold),
                        SrtSubtitle.cueAt(shuffled, t, hold)
                    )
                }
            }
        }
    }

    @Test
    fun cueAtOnEmptyListReturnsNull() {
        assertNull(SrtSubtitle.cueAt(emptyList(), 100L, holdGapMs = 1_500L))
    }
}

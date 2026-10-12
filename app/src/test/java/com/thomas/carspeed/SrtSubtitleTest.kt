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
}

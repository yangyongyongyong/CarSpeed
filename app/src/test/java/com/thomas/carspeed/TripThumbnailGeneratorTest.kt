package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class TripThumbnailGeneratorTest {
    @Test
    fun usesFirstMinuteForLongVideo() {
        assertEquals(60_000L, TripThumbnailGenerator.frameTimeMs(10L * 60_000L))
    }

    @Test
    fun usesMidpointForShortVideo() {
        assertEquals(30_000L, TripThumbnailGenerator.frameTimeMs(60_000L))
    }

    @Test
    fun clampsUnknownDurationToStart() {
        assertEquals(0L, TripThumbnailGenerator.frameTimeMs(0L))
        assertEquals(0L, TripThumbnailGenerator.frameTimeMs(-1L))
    }
}

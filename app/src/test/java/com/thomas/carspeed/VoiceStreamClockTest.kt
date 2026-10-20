package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceStreamClockTest {
    @Test
    fun first_audio_chunk_anchors_the_word_times() {
        val clock = VoiceStreamClock()
        assertNull(clock.elapsedFor(0))

        clock.noteChunk(durationMs = 100, chunkEndElapsedMs = 10_000)
        clock.noteChunk(durationMs = 100, chunkEndElapsedMs = 20_000)
        clock.alignIfNeeded(maxEndMs = 1_500, nowElapsedMs = 11_600)

        assertEquals(9_900L, clock.elapsedFor(0))
        assertEquals(10_300L, clock.elapsedFor(400))
    }

    @Test
    fun result_without_audio_anchor_uses_the_arrival_time() {
        val clock = VoiceStreamClock()
        clock.alignIfNeeded(maxEndMs = 1_000, nowElapsedMs = 50_000)
        assertEquals(49_000L, clock.elapsedFor(0))
        assertEquals(50_000L, clock.elapsedFor(1_000))
    }

    @Test
    fun recognizer_clock_restart_realigns_to_the_new_words() {
        val clock = VoiceStreamClock()
        clock.noteChunk(durationMs = 100, chunkEndElapsedMs = 10_000)
        clock.alignIfNeeded(maxEndMs = 30_000, nowElapsedMs = 40_000)

        clock.alignIfNeeded(maxEndMs = 800, nowElapsedMs = 80_000)
        assertEquals(80_000L, clock.elapsedFor(800))
        assertEquals(79_200L, clock.elapsedFor(0))
    }
}

package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCuePcmTest {
    @Test
    fun start_rises_and_stop_falls_with_a_silent_gap() {
        val sampleRate = 8_000
        val start = RecordingCuePcm.start(sampleRate)
        val stop = RecordingCuePcm.stop(sampleRate)
        val gap = sampleRate * RecordingCuePcm.GAP_MS / 1_000

        val startFirst = sampleRate * RecordingCuePcm.START_FIRST_MS / 1_000
        val startSecond = sampleRate * RecordingCuePcm.START_SECOND_MS / 1_000
        assertEquals(startFirst + gap + startSecond, start.size)
        assertTrue((startFirst until startFirst + gap).all { start[it].toInt() == 0 })
        assertTrue(
            zeroCrossingRate(start.copyOfRange(startFirst + gap, start.size)) >
                zeroCrossingRate(start.copyOfRange(0, startFirst))
        )

        val stopFirst = sampleRate * RecordingCuePcm.STOP_FIRST_MS / 1_000
        val stopSecond = sampleRate * RecordingCuePcm.STOP_SECOND_MS / 1_000
        assertEquals(stopFirst + gap + stopSecond, stop.size)
        assertTrue(
            zeroCrossingRate(stop.copyOfRange(stopFirst + gap, stop.size)) <
                zeroCrossingRate(stop.copyOfRange(0, stopFirst))
        )
    }
}

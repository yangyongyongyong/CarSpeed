package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoShutterPcmTest {
    @Test
    fun shutter_has_two_decaying_clicks_and_a_silent_gap() {
        val sampleRate = 8_000
        val first = sampleRate * 12 / 1_000
        val gap = sampleRate * 28 / 1_000
        val second = sampleRate * 55 / 1_000
        val samples = PhotoShutterPcm.samples(sampleRate)

        assertEquals(first + gap + second, samples.size)
        assertTrue(shutterBurstIsLouderAtTheStart(samples, 0, first))
        assertTrue(shutterBurstIsLouderAtTheStart(samples, first + gap, second))
        assertTrue((first until first + gap).all { samples[it].toInt() == 0 })
        assertTrue(samples.all { abs(it.toInt()) <= 32_767 })
    }

    @Test
    fun volume_scales_the_cue_and_full_volume_stays_unchanged() {
        val samples = shortArrayOf(10_000, -8_000, 0)

        assertSame(samples, scaleCueSamples(samples, 100))
        assertSame(samples, scaleCueSamples(samples, 140))
        assertEquals(5_000, scaleCueSamples(samples, 50)[0].toInt())
        assertEquals(-4_000, scaleCueSamples(samples, 50)[1].toInt())
        assertEquals(0, scaleCueSamples(samples, 0)[0].toInt())
    }
}

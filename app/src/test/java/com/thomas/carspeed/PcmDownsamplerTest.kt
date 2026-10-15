package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmDownsamplerTest {
    @Test
    fun same_rate_preserves_samples_including_negative() {
        val downsampler = PcmDownsampler()
        val out = downsampler.accept(pcm16le(1, -1, 32000), pcm16le(1, -1, 32000).size, 16_000, 16_000)
        assertEquals(listOf(1.toShort(), (-1).toShort(), 32000.toShort()), out.toList())
    }

    @Test
    fun halves_the_rate_and_keeps_phase_across_buffers() {
        val downsampler = PcmDownsampler()
        assertEquals(listOf(1.toShort(), 3.toShort()), downsampler.accept(pcm16le(1, 2, 3, 4), 8, 2, 1).toList())
        assertEquals(listOf(5.toShort(), 7.toShort()), downsampler.accept(pcm16le(5, 6, 7, 8), 8, 2, 1).toList())
    }

    @Test
    fun one_second_of_44100_becomes_16000_samples() {
        val input = ByteArray(44_100 * 2)
        val out = PcmDownsampler().accept(input, input.size, 44_100, 16_000)
        assertEquals(16_000, out.size)
    }

    @Test
    fun empty_or_truncated_input_produces_no_samples() {
        val downsampler = PcmDownsampler()
        assertEquals(0, downsampler.accept(ByteArray(0), 0, 44_100, 16_000).size)
        assertEquals(0, downsampler.accept(byteArrayOf(0x01), 1, 44_100, 16_000).size)
        assertEquals(0, downsampler.accept(pcm16le(1, 2), 4, 0, 16_000).size)
    }

    private fun pcm16le(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.toShort().toInt()
            out[index * 2] = (value and 0xff).toByte()
            out[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }
}

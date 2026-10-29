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
    fun halves_the_rate_by_averaging_and_keeps_phase_across_buffers() {
        val downsampler = PcmDownsampler()
        assertEquals(listOf(2.toShort(), 4.toShort()), downsampler.accept(pcm16le(10, -6, 3, 5), 8, 2, 1).toList())
        assertEquals(listOf(6.toShort(), 8.toShort()), downsampler.accept(pcm16le(5, 7, 7, 9), 8, 2, 1).toList())
    }

    @Test
    fun averaging_window_spans_buffer_boundary() {
        val downsampler = PcmDownsampler()
        // 3:1，第一个窗口跨两块：(3 + 6 + 9) / 3 = 6
        assertEquals(emptyList<Short>(), downsampler.accept(pcm16le(3, 6), 4, 3, 1).toList())
        assertEquals(listOf(6.toShort()), downsampler.accept(pcm16le(9), 2, 3, 1).toList())
    }

    @Test
    fun alternating_signal_above_nyquist_is_filtered_out() {
        val samples = IntArray(4_800) { if (it % 2 == 0) 10_000 else -10_000 }
        val out = PcmDownsampler().accept(pcm16le(*samples), samples.size * 2, 48_000, 16_000)
        assertEquals(1_600, out.size)
        // 3:1 平均后 ±10000 的交替信号只剩 ±3333，而不是原样混叠成 ±10000。
        assertEquals(true, out.all { kotlin.math.abs(it.toInt()) <= 3_334 })
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

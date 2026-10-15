package com.thomas.carspeed

import kotlin.math.PI
import kotlin.math.sin

/** 开始录像是两声上扬，结束录像是两声下降，和拍照快门分开。 */
internal object RecordingCuePcm {
    const val GAP_MS = 40
    const val START_FIRST_HZ = 880
    const val START_FIRST_MS = 70
    const val START_SECOND_HZ = 1_320
    const val START_SECOND_MS = 90
    const val STOP_FIRST_HZ = 740
    const val STOP_FIRST_MS = 70
    const val STOP_SECOND_HZ = 440
    const val STOP_SECOND_MS = 140

    fun start(sampleRate: Int): ShortArray = sequence(
        sampleRate,
        listOf(START_FIRST_HZ to START_FIRST_MS, START_SECOND_HZ to START_SECOND_MS)
    )

    fun stop(sampleRate: Int): ShortArray = sequence(
        sampleRate,
        listOf(STOP_FIRST_HZ to STOP_FIRST_MS, STOP_SECOND_HZ to STOP_SECOND_MS)
    )

    fun sequence(sampleRate: Int, parts: List<Pair<Int, Int>>): ShortArray {
        val rate = sampleRate.coerceAtLeast(8_000)
        val chunks = parts.map { (hz, ms) -> beep(rate, hz, ms) }
        val gap = samplesForMs(rate, GAP_MS)
        val total = chunks.sumOf { it.size } + gap * (chunks.size - 1).coerceAtLeast(0)
        val out = ShortArray(total)
        var cursor = 0
        chunks.forEachIndexed { index, chunk ->
            chunk.copyInto(out, cursor)
            cursor += chunk.size
            if (index < chunks.lastIndex) cursor += gap
        }
        return out
    }

    private fun beep(sampleRate: Int, frequencyHz: Int, durationMs: Int): ShortArray {
        val length = samplesForMs(sampleRate, durationMs)
        val fade = samplesForMs(sampleRate, 6).coerceAtMost(length / 2)
        val out = ShortArray(length)
        for (i in 0 until length) {
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i >= length - fade -> (length - 1 - i).toDouble() / fade
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val angle = 2.0 * PI * frequencyHz * i / sampleRate
            out[i] = (sin(angle) * envelope * 30_000.0).toInt().coerceIn(-32_767, 32_767).toShort()
        }
        return out
    }

    private fun samplesForMs(sampleRate: Int, ms: Int): Int = (sampleRate * ms / 1_000).coerceAtLeast(1)
}

internal fun zeroCrossingRate(samples: ShortArray): Double {
    if (samples.size < 2) return 0.0
    var crosses = 0
    for (index in 1 until samples.size) {
        val previous = samples[index - 1].toInt()
        val current = samples[index].toInt()
        if ((previous < 0 && current >= 0) || (previous >= 0 && current < 0)) crosses++
    }
    return crosses.toDouble() / (samples.size - 1)
}

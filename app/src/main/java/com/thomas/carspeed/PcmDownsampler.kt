package com.thomas.carspeed

import kotlin.math.roundToInt

/**
 * 把录像的 16-bit 小端 PCM 抽成语音识别用的采样率。
 * 降采样时对每个输出点覆盖的输入区间取平均（简单低通），减少高频混叠；
 * 区间可以跨缓冲区，累加状态在两次调用之间保留。
 */
internal class PcmDownsampler {
    private var nextOutputIndex = 0L
    private var consumedSamples = 0L
    private var pendingSum = 0L
    private var pendingCount = 0

    fun reset() {
        nextOutputIndex = 0L
        consumedSamples = 0L
        pendingSum = 0L
        pendingCount = 0
    }

    fun accept(pcm16le: ByteArray, length: Int, fromRate: Int, toRate: Int): ShortArray {
        if (length < 2 || fromRate <= 0 || toRate <= 0) return ShortArray(0)
        val inputSamples = length / 2
        val start = consumedSamples
        val end = start + inputSamples
        if (fromRate == toRate) {
            val out = ShortArray(inputSamples)
            for (index in 0 until inputSamples) {
                out[index] = littleEndianShort(pcm16le, index)
            }
            consumedSamples = end
            nextOutputIndex = end
            pendingSum = 0L
            pendingCount = 0
            return out
        }
        if (fromRate < toRate) return upsampleNearest(pcm16le, start, end, fromRate, toRate)

        val from = fromRate.toLong()
        val to = toRate.toLong()
        // 输出点 k 覆盖输入 [k*from/to, (k+1)*from/to)，区间终点 <= end 的输出点在本块内完成。
        val completedThrough = ((end + 1) * to - 1) / from
        val out = ShortArray((completedThrough - nextOutputIndex).coerceAtLeast(0L).toInt())
        var written = 0
        var outputIndex = nextOutputIndex
        var boundary = (outputIndex + 1) * from / to
        var sum = pendingSum
        var count = pendingCount
        for (index in 0 until inputSamples) {
            sum += littleEndianShort(pcm16le, index)
            count++
            if (start + index + 1 >= boundary) {
                if (written < out.size) {
                    out[written++] = (sum.toDouble() / count).roundToInt().toShort()
                }
                sum = 0L
                count = 0
                outputIndex++
                boundary = (outputIndex + 1) * from / to
            }
        }
        nextOutputIndex = outputIndex
        consumedSamples = end
        pendingSum = sum
        pendingCount = count
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun upsampleNearest(pcm16le: ByteArray, start: Long, end: Long, fromRate: Int, toRate: Int): ShortArray {
        val firstOutput = nextOutputIndex
        val lastOutputExclusive = (end * toRate.toLong() + fromRate - 1) / fromRate.toLong()
        val out = ShortArray((lastOutputExclusive - firstOutput).coerceAtLeast(0L).toInt())
        var written = 0
        var outputIndex = firstOutput
        while (written < out.size) {
            val position = outputIndex * fromRate.toLong() / toRate.toLong()
            if (position >= end) break
            if (position >= start) {
                out[written++] = littleEndianShort(pcm16le, (position - start).toInt())
            }
            outputIndex++
        }
        nextOutputIndex = outputIndex
        consumedSamples = end
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun littleEndianShort(data: ByteArray, index: Int): Short {
        val offset = index * 2
        val low = data[offset].toInt() and 0xff
        val high = data[offset + 1].toInt() and 0xff
        return ((high shl 8) or low).toShort()
    }
}

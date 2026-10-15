package com.thomas.carspeed

/**
 * 把录像的 16-bit 小端 PCM 抽成语音识别用的采样率。
 * 跨缓冲区保留读指针，避免每块音频都从 0 重新抽样。
 */
internal class PcmDownsampler {
    private var nextOutputIndex = 0L
    private var consumedSamples = 0L

    fun reset() {
        nextOutputIndex = 0L
        consumedSamples = 0L
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
            return out
        }
        val out = ArrayList<Short>()
        var outputIndex = nextOutputIndex
        while (true) {
            val position = outputIndex * fromRate.toLong() / toRate.toLong()
            if (position >= end) break
            if (position >= start) {
                out.add(littleEndianShort(pcm16le, (position - start).toInt()))
            }
            outputIndex++
        }
        nextOutputIndex = outputIndex
        consumedSamples = end
        return out.toShortArray()
    }

    private fun littleEndianShort(data: ByteArray, index: Int): Short {
        val offset = index * 2
        val low = data[offset].toInt() and 0xff
        val high = data[offset + 1].toInt() and 0xff
        return ((high shl 8) or low).toShort()
    }
}

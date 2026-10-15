package com.thomas.carspeed

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.abs

/** 合成一声短快门，开车时不用看屏幕也能知道拍照已经触发。 */
internal object PhotoShutterPcm {
    fun samples(sampleRate: Int): ShortArray {
        val rate = sampleRate.coerceAtLeast(8_000)
        val first = samplesForMs(rate, 12)
        val gap = samplesForMs(rate, 28)
        val second = samplesForMs(rate, 55)
        val out = ShortArray(first + gap + second)
        writeBurst(out, start = 0, length = first, amplitude = 32_767, seed = 0x1234ABCD)
        writeBurst(out, start = first + gap, length = second, amplitude = 32_767, seed = 0x5A17C3E1)
        return out
    }

    private fun samplesForMs(sampleRate: Int, ms: Int): Int = (sampleRate * ms / 1_000).coerceAtLeast(1)

    private fun writeBurst(out: ShortArray, start: Int, length: Int, amplitude: Int, seed: Int) {
        var noise = seed
        for (i in 0 until length) {
            val index = start + i
            if (index !in out.indices) return
            noise = noise * 1664525 + 1013904223
            val sample = ((noise ushr 16) and 0xFFFF) - 32_768
            val remain = 1.0 - i.toDouble() / length.toDouble()
            out[index] = (sample * remain * amplitude / 32_768.0).toInt()
                .coerceIn(-32_767, 32_767)
                .toShort()
        }
    }
}

internal object PhotoShutterSound {
    private const val SAMPLE_RATE = 22_050
    private val mainHandler = Handler(Looper.getMainLooper())

    fun play(context: Context) {
        playPcm(context, PhotoShutterPcm.samples(SAMPLE_RATE))
    }

    fun playRecordingStart(context: Context) {
        playPcm(context, RecordingCuePcm.start(SAMPLE_RATE))
    }

    fun playRecordingStop(context: Context) {
        playPcm(context, RecordingCuePcm.stop(SAMPLE_RATE))
    }

    private fun playPcm(context: Context, pcm: ShortArray) {
        if (!DashcamSettings.cueSoundsEnabled(context)) return
        val volume = DashcamSettings.cueVolumePercent(context)
        if (volume <= 0) return
        val scaled = scaleCueSamples(pcm, volume)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            playNow(context.applicationContext, scaled)
        } else {
            val appContext = context.applicationContext
            mainHandler.post { playNow(appContext, scaled) }
        }
    }

    private fun playNow(context: Context, pcm: ShortArray) {
        val ducked = AppPlaybackDuck.acquire(context)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AppPlaybackDuck.mediaAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull()
        if (track == null || track.write(pcm, 0, pcm.size) <= 0) {
            track?.release()
            ducked.release()
            return
        }
        track.play()
        val durationMs = pcm.size * 1_000L / SAMPLE_RATE + 80L
        mainHandler.postDelayed({
            runCatching {
                track.stop()
                track.release()
            }
            ducked.release()
        }, durationMs)
    }
}

internal fun scaleCueSamples(samples: ShortArray, volumePercent: Int): ShortArray {
    val percent = volumePercent.coerceIn(0, 100)
    if (percent >= 100) return samples
    if (percent <= 0) return ShortArray(samples.size)
    val gain = percent / 100.0
    return ShortArray(samples.size) { index ->
        (samples[index] * gain).toInt().coerceIn(-32_767, 32_767).toShort()
    }
}

internal fun shutterBurstIsLouderAtTheStart(samples: ShortArray, burstStart: Int, burstLength: Int): Boolean {
    if (burstLength < 10) return false
    val head = burstLength / 5
    val early = (0 until head).maxOf { abs(samples[burstStart + it].toInt()) }
    val lateStart = burstStart + burstLength - head
    val late = (0 until head).maxOf { abs(samples[lateStart + it].toInt()) }
    return early > late && early > 1_000
}

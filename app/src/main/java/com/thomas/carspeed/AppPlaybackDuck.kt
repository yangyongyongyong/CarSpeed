package com.thomas.carspeed

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 提示音和播报响的时候，把正在放的音乐暂时压低。
 * 多路提示叠在一起时，要等全部结束才把音乐放开。
 */
internal class PlaybackDuckCounter {
    private var holds = 0

    fun acquire(): Boolean {
        holds += 1
        return holds == 1
    }

    fun release(): Boolean {
        if (holds == 0) return false
        holds -= 1
        return holds == 0
    }
}

internal class DuckToken(
    private val onRelease: () -> Unit
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

internal object AppPlaybackDuck {
    private val counter = PlaybackDuckCounter()
    private var manager: AudioManager? = null
    private var request: AudioFocusRequest? = null

    fun mediaAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
    }

    fun speechAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
    }

    fun acquire(context: Context): DuckToken {
        if (counter.acquire()) {
            val audio = context.applicationContext.getSystemService(AudioManager::class.java)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(mediaAttributes())
                .setAcceptsDelayedFocusGain(false)
                .build()
            audio.requestAudioFocus(focus)
            manager = audio
            request = focus
        }
        return DuckToken {
            if (counter.release()) {
                val active = request
                val audio = manager
                request = null
                if (active != null && audio != null) {
                    runCatching { audio.abandonAudioFocusRequest(active) }
                }
            }
        }
    }
}

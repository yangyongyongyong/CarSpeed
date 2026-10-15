package com.thomas.carspeed

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object VoiceCommandFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var pendingUtteranceId: String? = null
    private val speechDucks = mutableMapOf<String, DuckToken>()

    fun photoTaken(context: Context) {
        speak(context, "照片已保存")
    }

    fun recordingStarted(context: Context) {
        speak(context, "录像已启动")
    }

    fun recordingStopped(context: Context) {
        speak(context, "录像已结束")
    }

    fun alreadyRecording(context: Context) {
        speak(context, "正在录像")
    }

    fun notRecording(context: Context) {
        speak(context, "当前没有录像")
    }

    fun radarAlertStarted(context: Context) {
        speak(context, "测速雷达已开启")
    }

    fun radarAlertStopped(context: Context) {
        speak(context, "测速雷达已关闭")
    }

    fun radarAlertNeedsLocation(context: Context) {
        speak(context, "需要精确定位权限才能开启测速雷达")
    }

    private fun speak(context: Context, text: String) {
        val utteranceId = "voice-command-${System.currentTimeMillis()}"
        holdForSpeech(context, utteranceId)
        playTone()
        ensureTts(context.applicationContext)
        if (ttsReady) {
            speakNow(text, utteranceId)
        } else {
            pendingText = text
            pendingUtteranceId = utteranceId
        }
    }

    private fun ensureTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val activeTts = tts ?: return@TextToSpeech
                activeTts.language = Locale.CHINA
                activeTts.setAudioAttributes(AppPlaybackDuck.speechAttributes())
                activeTts.setSpeechRate(1.15f)
                activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { finishSpeech(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { finishSpeech(utteranceId) }
                    }
                })
                pendingText?.let { text ->
                    val utteranceId = pendingUtteranceId ?: "voice-command-late"
                    pendingText = null
                    pendingUtteranceId = null
                    speakNow(text, utteranceId)
                }
            } else {
                pendingText = null
                pendingUtteranceId?.let { finishSpeech(it) }
                pendingUtteranceId = null
            }
        }
    }

    private fun speakNow(text: String, utteranceId: String) {
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0")
        }
        AppSpeechGuard.markSpeechStarted()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun holdForSpeech(context: Context, utteranceId: String) {
        speechDucks.remove(utteranceId)?.release()
        speechDucks[utteranceId] = AppPlaybackDuck.acquire(context)
        mainHandler.postDelayed({ finishSpeech(utteranceId) }, 8_000L)
    }

    private fun finishSpeech(utteranceId: String?) {
        if (utteranceId == null) return
        speechDucks.remove(utteranceId)?.release()
    }

    private fun playTone() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        mainHandler.postDelayed({ tone.release() }, 300L)
    }

}

package com.thomas.carspeed

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

object VoiceCommandFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null

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

    private fun speak(context: Context, text: String) {
        playTone()
        ensureTts(context.applicationContext)
        if (ttsReady) {
            speakNow(text)
        } else {
            pendingText = text
        }
    }

    private fun ensureTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val activeTts = tts ?: return@TextToSpeech
                activeTts.language = Locale.CHINA
                activeTts.setSpeechRate(1.15f)
                pendingText?.let { text ->
                    pendingText = null
                    speakNow(text)
                }
            } else {
                pendingText = null
            }
        }
    }

    private fun speakNow(text: String) {
        val utteranceId = "voice-command-${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun playTone() {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        mainHandler.postDelayed({ tone.release() }, 300L)
    }
}

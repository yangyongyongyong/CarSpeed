package com.thomas.carspeed

internal enum class VoiceCaptureMode {
    OFF,
    MICROPHONE,
    RECORDING_PCM
}

internal object VoiceCapture {
    fun mode(voiceEnabled: Boolean, audioPermission: Boolean, recording: Boolean): VoiceCaptureMode {
        if (!voiceEnabled || !audioPermission) return VoiceCaptureMode.OFF
        if (recording) return VoiceCaptureMode.RECORDING_PCM
        return VoiceCaptureMode.MICROPHONE
    }
}

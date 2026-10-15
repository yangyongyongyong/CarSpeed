package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCaptureTest {
    @Test
    fun voice_stays_off_without_the_switch_or_microphone_permission() {
        assertEquals(VoiceCaptureMode.OFF, VoiceCapture.mode(voiceEnabled = false, audioPermission = true, recording = false))
        assertEquals(VoiceCaptureMode.OFF, VoiceCapture.mode(voiceEnabled = true, audioPermission = false, recording = true))
    }

    @Test
    fun idle_voice_uses_its_own_microphone() {
        assertEquals(
            VoiceCaptureMode.MICROPHONE,
            VoiceCapture.mode(voiceEnabled = true, audioPermission = true, recording = false)
        )
    }

    @Test
    fun recording_feeds_voice_from_the_recording_microphone() {
        assertEquals(
            VoiceCaptureMode.RECORDING_PCM,
            VoiceCapture.mode(voiceEnabled = true, audioPermission = true, recording = true)
        )
    }
}

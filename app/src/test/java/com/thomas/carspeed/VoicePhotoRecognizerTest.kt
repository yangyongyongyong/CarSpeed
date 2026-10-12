package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePhotoRecognizerTest {
    @Test
    fun photo_command_requires_exact_independent_phrase() {
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("拍照"))
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("拍 照"))
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("拍照。"))

        assertNull(VoiceCommandParser.parseCommand("我喜欢拍照"))
        assertNull(VoiceCommandParser.parseCommand("前方三百米拍照"))
        assertNull(VoiceCommandParser.parseCommand("[unk] 拍照"))
        assertNull(VoiceCommandParser.parseCommand("拍照吧"))
    }

    @Test
    fun recording_commands_require_exact_independent_phrase() {
        assertEquals(VoiceCommand.START_RECORDING, VoiceCommandParser.parseCommand("开始录像"))
        assertEquals(VoiceCommand.STOP_RECORDING, VoiceCommandParser.parseCommand("停止录像"))

        assertNull(VoiceCommandParser.parseCommand("现在开始录像"))
        assertNull(VoiceCommandParser.parseCommand("请停止录像"))
    }

    @Test
    fun radar_commands_require_exact_independent_phrase() {
        assertEquals(VoiceCommand.RADAR_ALERT_ON, VoiceCommandParser.parseCommand("测速雷达开"))
        assertEquals(VoiceCommand.RADAR_ALERT_OFF, VoiceCommandParser.parseCommand("测速雷达关"))
        assertEquals(VoiceCommand.RADAR_ALERT_ON, VoiceCommandParser.parseCommand("测 速 雷 达 开"))
        assertEquals(VoiceCommand.RADAR_ALERT_OFF, VoiceCommandParser.parseCommand("测速雷达关。"))

        assertNull(VoiceCommandParser.parseCommand("请测速雷达开"))
        assertNull(VoiceCommandParser.parseCommand("测速雷达开一下"))
        assertNull(VoiceCommandParser.parseCommand("前方测速雷达开拍"))
        assertNull(VoiceCommandParser.parseCommand("测速雷达关闭"))
    }
}

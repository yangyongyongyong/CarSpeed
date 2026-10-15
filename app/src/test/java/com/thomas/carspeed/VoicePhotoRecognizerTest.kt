package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePhotoRecognizerTest {
    @Test
    fun photo_command_requires_exact_independent_phrase() {
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("我要拍照"))
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("帮我拍照"))
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("给我拍照"))
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("拍一张"))
        assertEquals(VoiceCommand.TAKE_PHOTO, VoiceCommandParser.parseCommand("照一张"))

        assertNull(VoiceCommandParser.parseCommand("拍照"))
        assertNull(VoiceCommandParser.parseCommand("拍 照"))
        assertNull(VoiceCommandParser.parseCommand("拍照。"))
        assertNull(VoiceCommandParser.parseCommand("照相"))
        assertNull(VoiceCommandParser.parseCommand("我喜欢拍照"))
        assertNull(VoiceCommandParser.parseCommand("前方三百米拍照"))
        assertNull(VoiceCommandParser.parseCommand("前方100米有限速拍照"))
        assertNull(VoiceCommandParser.parseCommand("前方一百米有限速拍照"))
        assertNull(VoiceCommandParser.parseCommand("限速拍照"))
        assertNull(VoiceCommandParser.parseCommand("[unk] 拍照"))
        assertNull(VoiceCommandParser.parseCommand("拍照吧"))
    }

    @Test
    fun app_speech_temporarily_suppresses_photo_commands() {
        AppSpeechGuard.markSpeechStarted(nowMs = 10_000L)

        assertTrue(AppSpeechGuard.shouldSuppressVoiceCommand(nowMs = 10_500L))
        assertFalse(AppSpeechGuard.shouldSuppressVoiceCommand(nowMs = 17_000L))
    }

    @Test
    fun recording_commands_require_exact_independent_phrase() {
        assertEquals(VoiceCommand.START_RECORDING, VoiceCommandParser.parseCommand("开始录像"))
        assertEquals(VoiceCommand.STOP_RECORDING, VoiceCommandParser.parseCommand("停止录像"))

        assertNull(VoiceCommandParser.parseCommand("现在开始录像"))
        assertNull(VoiceCommandParser.parseCommand("请停止录像"))
    }

    @Test
    fun every_supported_phrase_parses_and_near_misses_do_not() {
        mapOf(
            "我要拍照" to VoiceCommand.TAKE_PHOTO,
            "帮我拍照" to VoiceCommand.TAKE_PHOTO,
            "给我拍照" to VoiceCommand.TAKE_PHOTO,
            "拍一张" to VoiceCommand.TAKE_PHOTO,
            "照一张" to VoiceCommand.TAKE_PHOTO,
            "我要拍照。" to VoiceCommand.TAKE_PHOTO,
            "我要拍照！" to VoiceCommand.TAKE_PHOTO,
            "  拍一张  " to VoiceCommand.TAKE_PHOTO,
            "开始录像" to VoiceCommand.START_RECORDING,
            "开始录制" to VoiceCommand.START_RECORDING,
            "开始拍摄" to VoiceCommand.START_RECORDING,
            "我要录像" to VoiceCommand.START_RECORDING,
            "我要录制" to VoiceCommand.START_RECORDING,
            "开 始 录 像" to VoiceCommand.START_RECORDING,
            "停止录像" to VoiceCommand.STOP_RECORDING,
            "停止录制" to VoiceCommand.STOP_RECORDING,
            "停止拍摄" to VoiceCommand.STOP_RECORDING,
            "结束录像" to VoiceCommand.STOP_RECORDING,
            "结束录制" to VoiceCommand.STOP_RECORDING,
            "测速雷达开" to VoiceCommand.RADAR_ALERT_ON,
            "测速雷达关" to VoiceCommand.RADAR_ALERT_OFF
        ).forEach { (text, command) ->
            assertEquals(text, command, VoiceCommandParser.parseCommand(text))
        }

        listOf(
            "",
            "拍照",
            "拍张",
            "我要拍张",
            "拍一张吧",
            "我要拍照谢谢",
            "前方有拍照",
            "现在开始录像",
            "请停止录像",
            "开始录象",
            "停止拍摄了",
            "测速雷达开启",
            "测速雷达关闭",
            "前方测速雷达开"
        ).forEach { text ->
            assertNull(text, VoiceCommandParser.parseCommand(text))
        }
    }

    @Test
    fun grammar_keeps_commands_and_lets_music_fall_into_unknown() {
        val grammar = VoiceCommandParser.commandGrammar()
        listOf(
            "我要拍照",
            "帮我拍照",
            "给我拍照",
            "照一张",
            "拍一张",
            "开始录像",
            "停止录像",
            "测速雷达开",
            "测速雷达关",
            "[unk]"
        ).forEach { phrase ->
            assertTrue(grammar.contains("\"$phrase\""))
        }
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

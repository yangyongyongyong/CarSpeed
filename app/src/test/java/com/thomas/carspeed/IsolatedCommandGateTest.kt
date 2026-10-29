package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsolatedCommandGateTest {
    @Test
    fun photo_command_waits_for_one_second_after_the_phrase() {
        val gate = IsolatedCommandGate()
        val words = phrase("我要拍照", startMs = 5_000, endMs = 5_900)

        assertNull(gate.onWords(words, confirmedThroughMs = 5_900))
        assertNull(gate.onSilenceAdvanced(6_899))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(6_900))
        assertNull(gate.onSilenceAdvanced(8_000))
    }

    @Test
    fun surrounding_words_block_a_photo_command() {
        val gate = IsolatedCommandGate()
        val words = listOf(
            TimedWord("前", 1_000, 1_200),
            TimedWord("方", 1_200, 1_400),
            TimedWord("有", 1_400, 1_600),
            TimedWord("我要拍照", 1_700, 2_500)
        )

        assertNull(gate.onWords(words, confirmedThroughMs = 8_000))
    }

    @Test
    fun embedded_photo_keyword_is_not_a_command() {
        val gate = IsolatedCommandGate()
        val words = listOf(
            TimedWord("前", 1_000, 1_200),
            TimedWord("方", 1_200, 1_400),
            TimedWord("有", 1_400, 1_600),
            TimedWord("拍", 1_600, 1_800),
            TimedWord("照", 1_800, 2_000)
        )

        assertNull(gate.onWords(words, confirmedThroughMs = 8_000))
    }

    @Test
    fun pause_before_and_after_accepts_the_same_phrase() {
        val gate = IsolatedCommandGate()
        val words = listOf(TimedWord("前方有车", 1_000, 1_800)) +
            phrase("我要拍照", startMs = 3_200, endMs = 4_000)

        assertNull(gate.onWords(words, confirmedThroughMs = 4_000))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(5_000))
    }

    @Test
    fun unknown_tokens_from_music_do_not_count_as_speech() {
        val gate = IsolatedCommandGate()
        val words = listOf(TimedWord("[unk]", 0, 4_800)) +
            phrase("我要拍照", startMs = 5_000, endMs = 5_800)

        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onWords(words, confirmedThroughMs = 6_800))
    }

    @Test
    fun a_later_isolated_command_can_fire_again() {
        val gate = IsolatedCommandGate()
        assertEquals(
            VoiceCommand.TAKE_PHOTO,
            gate.onWords(phrase("拍一张", startMs = 1_000, endMs = 1_600), confirmedThroughMs = 2_600)
        )
        assertEquals(
            VoiceCommand.START_RECORDING,
            gate.onWords(phrase("开始录像", startMs = 8_000, endMs = 8_700), confirmedThroughMs = 9_700)
        )
    }

    @Test
    fun words_touching_the_command_reject_recording_too() {
        val gate = IsolatedCommandGate()
        val words = listOf(TimedWord("现在", 1_000, 1_400)) +
            phrase("开始录像", startMs = 1_500, endMs = 2_200)

        assertNull(gate.onWords(words, confirmedThroughMs = 5_000))
    }

    @Test
    fun recognizer_restart_does_not_keep_the_old_timeline() {
        val gate = IsolatedCommandGate()
        gate.onWords(listOf(TimedWord("你好", 100_000, 100_400)), confirmedThroughMs = 100_400)

        assertEquals(
            VoiceCommand.TAKE_PHOTO,
            gate.onWords(phrase("我要拍照", startMs = 800, endMs = 1_600), confirmedThroughMs = 2_600)
        )
    }

    @Test
    fun vosk_json_word_order_is_read_and_isolated() {
        val heard = """
            {"text":"我 要 拍 照","result":[
              {"conf":1.0,"end":1.2,"start":0.8,"word":"我"},
              {"conf":1.0,"end":1.5,"start":1.2,"word":"要"},
              {"conf":1.0,"end":1.9,"start":1.5,"word":"拍"},
              {"conf":1.0,"end":2.3,"start":1.9,"word":"照"}
            ]}
        """.trimIndent()
        val gate = IsolatedCommandGate()

        assertNull(gate.onWords(VoiceCommandWords.parse(heard), confirmedThroughMs = 2_300))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(3_300))
    }

    @Test
    fun plain_text_needs_one_second_of_stable_command() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("我要拍照", nowMs = 0, endpoint = false))
        assertNull(gate.onClock(999))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onClock(1_000))
        assertNull(gate.onText("我要拍照", nowMs = 1_200, endpoint = false))
    }

    @Test
    fun plain_text_rejects_keyword_inside_another_sentence() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("前方有拍照", nowMs = 0, endpoint = false))
        assertNull(gate.onText("我要拍照", nowMs = 400, endpoint = true))
        assertNull(gate.onClock(5_000))
        assertNull(gate.onText("[unk] 拍照", nowMs = 6_000, endpoint = true))
        assertNull(gate.onText("我要拍照谢谢", nowMs = 7_000, endpoint = true))
    }

    @Test
    fun plain_text_strips_unknown_tokens_and_accepts_a_second_phrase_after_a_gap() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("[unk] 我要拍照", nowMs = 0, endpoint = false))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onClock(1_000))
        assertNull(gate.onText("", nowMs = 1_100, endpoint = false))
        assertNull(gate.onText("帮我拍照", nowMs = 1_500, endpoint = true))
        assertNull(gate.onText("", nowMs = 1_600, endpoint = false))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onText("帮我拍照", nowMs = 2_700, endpoint = true))
    }

    @Test
    fun photo_command_remembers_when_the_phrase_started() {
        val gate = IsolatedCommandGate()
        val words = listOf(TimedWord("你好", 1_000, 1_400)) +
            phrase("我要拍照", startMs = 3_000, endMs = 3_800)

        assertNull(gate.onWords(words, confirmedThroughMs = 3_800))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(4_800))
        assertEquals(3_000L, gate.firedStartMs())
    }

    @Test
    fun word_timeline_wins_over_plain_text() {
        val gate = IsolatedCommandGate()
        val words = phrase("我要拍照", startMs = 1_000, endMs = 1_800)
        assertNull(gate.onWords(words, confirmedThroughMs = 1_800))

        assertNull(gate.onText("我要拍照", nowMs = 9_000, endpoint = true))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(2_800))
    }

    @Test
    fun different_voice_commands_do_not_block_each_other_during_cooldown() {
        val cooldown = VoiceCommandCooldown(cooldownMs = 3_000L)

        assertEquals(true, cooldown.tryAcquire(VoiceCommand.START_RECORDING, nowMs = 1_000L))
        assertEquals(false, cooldown.tryAcquire(VoiceCommand.START_RECORDING, nowMs = 2_000L))
        assertEquals(true, cooldown.tryAcquire(VoiceCommand.TAKE_PHOTO, nowMs = 1_500L))
        assertEquals(false, cooldown.tryAcquire(VoiceCommand.TAKE_PHOTO, nowMs = 3_000L))
        assertEquals(true, cooldown.tryAcquire(VoiceCommand.TAKE_PHOTO, nowMs = 4_501L))
    }

    private fun phrase(text: String, startMs: Long, endMs: Long): List<TimedWord> {
        val chars = text.map { it.toString() }
        val step = (endMs - startMs) / chars.size
        return chars.mapIndexed { index, char ->
            TimedWord(char, startMs + step * index, startMs + step * (index + 1))
        }
    }
}

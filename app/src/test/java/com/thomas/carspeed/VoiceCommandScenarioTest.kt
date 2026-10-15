package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代替真机喊话。时间轴按识别器的约定：
 * 句中 partial 只确认到最后一个词的结束时间；句尾 endpoint 已经包含说完后的 1 秒。
 * 音乐和杂音用 [unk]，不算前后的话。真正的词贴在口令前后 1 秒内则整句作废。
 */
class VoiceCommandScenarioTest {
    @Test
    fun each_photo_phrase_fires_only_after_one_second_of_silence() {
        listOf("我要拍照", "帮我拍照", "给我拍照", "拍一张", "照一张").forEach { phrase ->
            val gate = IsolatedCommandGate()
            val said = listOf(TimedWord(phrase, 2_000, 2_700))

            assertNull(phrase, gate.onWords(said, confirmedThroughMs = 2_700))
            assertNull(phrase, gate.onSilenceAdvanced(3_699))
            assertEquals(phrase, VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(3_700))
            assertNull(phrase, gate.onSilenceAdvanced(5_000))
        }
    }

    @Test
    fun recording_and_radar_phrases_use_the_same_pause() {
        mapOf(
            "开始录像" to VoiceCommand.START_RECORDING,
            "开始录制" to VoiceCommand.START_RECORDING,
            "开始拍摄" to VoiceCommand.START_RECORDING,
            "我要录像" to VoiceCommand.START_RECORDING,
            "我要录制" to VoiceCommand.START_RECORDING,
            "停止录像" to VoiceCommand.STOP_RECORDING,
            "停止录制" to VoiceCommand.STOP_RECORDING,
            "停止拍摄" to VoiceCommand.STOP_RECORDING,
            "结束录像" to VoiceCommand.STOP_RECORDING,
            "结束录制" to VoiceCommand.STOP_RECORDING,
            "测速雷达开" to VoiceCommand.RADAR_ALERT_ON,
            "测速雷达关" to VoiceCommand.RADAR_ALERT_OFF
        ).forEach { (phrase, command) ->
            val gate = IsolatedCommandGate()
            val said = listOf(TimedWord(phrase, 1_000, 1_800))

            assertNull(phrase, gate.onWords(said, confirmedThroughMs = 1_800))
            assertEquals(phrase, command, gate.onSilenceAdvanced(2_800))
        }
    }

    @Test
    fun final_result_counts_the_trailing_second_already_waited_by_the_endpointer() {
        val gate = IsolatedCommandGate()
        val said = listOf(TimedWord("停止录像", 0, 900))

        assertEquals(VoiceCommand.STOP_RECORDING, gate.onWords(said, confirmedThroughMs = 900 + 1_000))
    }

    @Test
    fun leading_gap_of_999ms_blocks_and_1000ms_allows() {
        val blocked = IsolatedCommandGate()
        assertNull(
            blocked.onWords(
                listOf(TimedWord("前方", 0, 1_000)) + chars("我要拍照", 1_999, 2_800),
                confirmedThroughMs = 2_800 + 1_000
            )
        )

        val allowed = IsolatedCommandGate()
        assertEquals(
            VoiceCommand.TAKE_PHOTO,
            allowed.onWords(
                listOf(TimedWord("前方", 0, 1_000)) + chars("我要拍照", 2_000, 2_800),
                confirmedThroughMs = 2_800 + 1_000
            )
        )
    }

    @Test
    fun trailing_reply_within_one_second_cancels_even_if_an_old_timer_fires() {
        val gate = IsolatedCommandGate()
        val said = listOf(TimedWord("我要拍照", 5_000, 5_800))

        assertNull(gate.onWords(said, confirmedThroughMs = 5_800))
        assertNull(gate.onWords(said + TimedWord("谢谢", 6_300, 6_700), confirmedThroughMs = 6_700))
        assertNull(gate.onSilenceAdvanced(5_800 + 1_000))
        assertNull(gate.onSilenceAdvanced(6_700 + 1_000))
    }

    @Test
    fun trailing_gap_of_999ms_blocks_and_1000ms_allows() {
        val blocked = IsolatedCommandGate()
        assertNull(
            blocked.onWords(
                listOf(TimedWord("我要拍照", 1_000, 2_000), TimedWord("啊", 2_999, 3_200)),
                confirmedThroughMs = 9_000
            )
        )

        val allowed = IsolatedCommandGate()
        assertEquals(
            VoiceCommand.TAKE_PHOTO,
            allowed.onWords(
                listOf(TimedWord("我要拍照", 1_000, 2_000), TimedWord("啊", 3_000, 3_200)),
                confirmedThroughMs = 9_000
            )
        )
    }

    @Test
    fun navigation_sentence_then_a_real_pause_then_the_command() {
        val tooSoon = IsolatedCommandGate()
        val navigation = chars("前方有拍照", 0, 2_000)
        assertNull(
            tooSoon.onWords(
                navigation + chars("我要拍照", 2_999, 3_800),
                confirmedThroughMs = 3_800 + 1_000
            )
        )

        val afterPause = IsolatedCommandGate()
        assertEquals(
            VoiceCommand.TAKE_PHOTO,
            afterPause.onWords(
                navigation + chars("我要拍照", 3_000, 3_800),
                confirmedThroughMs = 3_800 + 1_000
            )
        )
    }

    @Test
    fun whole_navigation_token_is_never_a_photo_command() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onWords(listOf(TimedWord("前方有拍照", 0, 1_800)), confirmedThroughMs = 9_000))
        assertNull(gate.onSilenceAdvanced(20_000))
    }

    @Test
    fun music_tokens_on_both_sides_do_not_count_as_speech() {
        listOf("[unk]", "<unk>", "unk").forEach { noise ->
            val gate = IsolatedCommandGate()
            val words = listOf(
                TimedWord(noise, 0, 4_000),
                TimedWord("我要拍照", 4_200, 5_000),
                TimedWord(noise, 5_100, 8_000)
            )

            assertNull(noise, gate.onWords(words, confirmedThroughMs = 5_000))
            assertEquals(noise, VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(6_000))
        }
    }

    @Test
    fun short_unknown_inside_a_phrase_still_joins_but_a_long_hole_splits_it() {
        val joined = IsolatedCommandGate()
        assertEquals(
            VoiceCommand.TAKE_PHOTO,
            joined.onWords(
                listOf(
                    TimedWord("我", 1_000, 1_200),
                    TimedWord("要", 1_200, 1_400),
                    TimedWord("[unk]", 1_400, 1_800),
                    TimedWord("拍", 1_800, 2_000),
                    TimedWord("照", 2_000, 2_200)
                ),
                confirmedThroughMs = 2_200 + 1_000
            )
        )

        val split = IsolatedCommandGate()
        assertNull(
            split.onWords(
                listOf(
                    TimedWord("我", 1_000, 1_200),
                    TimedWord("要", 1_200, 1_400),
                    TimedWord("[unk]", 1_400, 2_201),
                    TimedWord("拍", 2_201, 2_400),
                    TimedWord("照", 2_400, 2_600)
                ),
                confirmedThroughMs = 9_000
            )
        )
    }

    @Test
    fun words_arrive_one_by_one_and_fire_only_when_the_phrase_is_complete_and_quiet() {
        val gate = IsolatedCommandGate()
        val steps = listOf(
            chars("我", 0, 200),
            chars("我要", 0, 400),
            chars("我要拍", 0, 700),
            chars("我要拍照", 0, 1_000)
        )
        steps.forEach { partial ->
            assertNull(gate.onWords(partial, confirmedThroughMs = partial.last().endMs))
        }
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(2_000))
    }

    @Test
    fun speech_older_than_the_word_memory_does_not_block_a_new_command() {
        val gate = IsolatedCommandGate()
        val words = listOf(TimedWord("你好", 0, 400)) + chars("我要拍照", 31_000, 31_800)

        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onWords(words, confirmedThroughMs = 31_800 + 1_000))
    }

    @Test
    fun plain_text_recovers_after_a_sentence_only_when_the_next_phrase_is_isolated() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("前方三百米有拍照", nowMs = 0, endpoint = true))
        assertNull(gate.onText("我要拍照", nowMs = 400, endpoint = true))
        assertNull(gate.onClock(900))
        assertNull(gate.onText("", nowMs = 1_000, endpoint = false))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onText("我要拍照", nowMs = 2_000, endpoint = true))
    }

    @Test
    fun plain_text_phrase_that_started_too_soon_does_not_become_valid_by_lasting_longer() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("前方有拍照", nowMs = 0, endpoint = false))
        assertNull(gate.onText("我要拍照", nowMs = 400, endpoint = false))
        assertNull(gate.onText("我要拍照", nowMs = 2_000, endpoint = true))
    }

    @Test
    fun plain_text_music_around_the_phrase_still_needs_one_stable_second() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("[unk] 我要拍照 [unk]", nowMs = 0, endpoint = false))
        assertNull(gate.onClock(999))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onClock(1_000))
    }

    @Test
    fun plain_text_extra_words_before_the_pause_cancel_the_pending_command() {
        val gate = IsolatedCommandGate()

        assertNull(gate.onText("我要拍照", nowMs = 0, endpoint = false))
        assertNull(gate.onText("我要拍照谢谢", nowMs = 400, endpoint = false))
        assertNull(gate.onClock(2_000))
        assertNull(gate.onText("我要拍照谢谢", nowMs = 3_000, endpoint = true))
    }

    @Test
    fun partial_json_reads_word_times_and_ignores_a_collapsed_sentence() {
        val partial = """
            {"partial":"拍一张","partial_result":[
              {"conf":0.8,"end":1.6,"start":1.0,"word":"拍一张"}
            ]}
        """.trimIndent()
        val gate = IsolatedCommandGate()
        val words = VoiceCommandWords.parse(partial)

        assertEquals(listOf(TimedWord("拍一张", 1_000, 1_600)), words)
        assertNull(gate.onWords(words, confirmedThroughMs = 1_600))
        assertEquals(VoiceCommand.TAKE_PHOTO, gate.onSilenceAdvanced(2_600))

        val collapsed = """{"text":"前方有拍照","result":[{"word":"前方有拍照","start":0.2,"end":1.4}]}"""
        assertTrue(VoiceCommandWords.parse(collapsed).isNotEmpty())
        assertNull(IsolatedCommandGate().onWords(VoiceCommandWords.parse(collapsed), confirmedThroughMs = 9_000))
        assertTrue(VoiceCommandWords.parse("不是 json").isEmpty())
        assertTrue(VoiceCommandWords.parse("""{"result":[{"word":"我","start":2.0,"end":1.0}]}""").isEmpty())
    }

    private fun chars(text: String, startMs: Long, endMs: Long): List<TimedWord> {
        val pieces = text.map { it.toString() }
        val step = (endMs - startMs) / pieces.size
        return pieces.mapIndexed { index, char ->
            TimedWord(char, startMs + step * index, startMs + step * (index + 1))
        }
    }
}

package com.thomas.carspeed

/**
 * 口令必须自成一句：前后各约 1 秒没有别的已识别词语。
 * 音乐和杂音经常被识别成 [unk]，不把它当成前后的话，避免播着歌就永远凑不齐停顿。
 * 「前方有拍照」这类句子里，前后是真正的词，间隔不到 1 秒，不会当成口令。
 */
internal class IsolatedCommandGate(
    private val pauseMs: Long = PAUSE_MS
) {
    private var words: List<TimedWord> = emptyList()
    private var predecessorEndMs: Long? = null
    private var confirmedThroughMs = 0L
    private var firedThroughMs = Long.MIN_VALUE
    private var textState: TextState = TextState.Idle

    @Synchronized
    fun reset() {
        words = emptyList()
        predecessorEndMs = null
        confirmedThroughMs = 0L
        firedThroughMs = Long.MIN_VALUE
        textState = TextState.Idle
    }

    @Synchronized
    fun onWords(incoming: List<TimedWord>, confirmedThroughMs: Long): VoiceCommand? {
        val real = incoming.filterNot { VoiceCommandWords.isUnknown(it.word) }
        if (real.isNotEmpty()) {
            merge(real)
            textState = TextState.Idle
        }
        this.confirmedThroughMs = maxOf(this.confirmedThroughMs, confirmedThroughMs)
        return takeReady()
    }

    @Synchronized
    fun onSilenceAdvanced(confirmedThroughMs: Long): VoiceCommand? {
        this.confirmedThroughMs = maxOf(this.confirmedThroughMs, confirmedThroughMs)
        return takeReady()
    }

    @Synchronized
    fun onText(raw: String, nowMs: Long, endpoint: Boolean): VoiceCommand? {
        if (words.isNotEmpty()) return null
        val command = VoiceCommandWords.commandAfterStrippingUnknown(raw)
        if (command == null) {
            if (VoiceCommandWords.withoutUnknown(raw).isNotBlank()) {
                textState = TextState.Foreign(nowMs)
                return null
            }
            return onBlank(nowMs, endpoint)
        }
        return onCommandText(command, nowMs, endpoint)
    }

    @Synchronized
    fun onClock(nowMs: Long): VoiceCommand? {
        if (words.isNotEmpty()) return null
        val pending = textState as? TextState.Pending ?: return null
        if (!pending.leadingOk || nowMs - pending.sinceMs < pauseMs) return null
        textState = TextState.Fired(pending.command, nowMs)
        return pending.command
    }

    private fun onBlank(nowMs: Long, endpoint: Boolean): VoiceCommand? {
        when (val state = textState) {
            is TextState.Pending -> {
                if (state.leadingOk && (endpoint || nowMs - state.sinceMs >= pauseMs)) {
                    textState = TextState.Fired(state.command, nowMs)
                    return state.command
                }
                if (!state.leadingOk) textState = TextState.Separated(nowMs)
            }
            is TextState.Fired -> textState = TextState.Separated(nowMs)
            else -> Unit
        }
        return null
    }

    private fun onCommandText(command: VoiceCommand, nowMs: Long, endpoint: Boolean): VoiceCommand? {
        when (val state = textState) {
            is TextState.Pending -> {
                if (state.command != command) {
                    textState = TextState.Pending(command, nowMs, state.leadingOk)
                }
            }
            is TextState.Fired -> return null
            is TextState.Foreign -> {
                textState = TextState.Pending(command, nowMs, nowMs - state.atMs >= pauseMs)
            }
            is TextState.Separated -> {
                textState = TextState.Pending(command, nowMs, nowMs - state.atMs >= pauseMs)
            }
            TextState.Idle -> textState = TextState.Pending(command, nowMs, leadingOk = true)
        }
        val pending = textState as? TextState.Pending ?: return null
        if (pending.leadingOk && (endpoint || nowMs - pending.sinceMs >= pauseMs)) {
            textState = TextState.Fired(command, nowMs)
            return command
        }
        return null
    }

    private fun merge(incoming: List<TimedWord>) {
        val restarted = words.isNotEmpty() &&
            incoming.maxOf { it.endMs } + 500 < words.maxOf { it.endMs }
        if (restarted) {
            words = incoming.sortedBy { it.startMs }
            predecessorEndMs = null
            firedThroughMs = Long.MIN_VALUE
            confirmedThroughMs = 0L
            return
        }
        val rewriteFrom = incoming.minOf { it.startMs }
        words = (words.filter { it.startMs < rewriteFrom } + incoming).sortedBy { it.startMs }
        trim()
    }

    private fun trim() {
        val newest = words.maxOfOrNull { it.endMs } ?: return
        val horizon = newest - WORD_MEMORY_MS
        val removed = words.filter { it.endMs < horizon }
        if (removed.isNotEmpty()) {
            predecessorEndMs = removed.maxOf { it.endMs }
            words = words.filter { it.endMs >= horizon }
        }
    }

    private fun takeReady(): VoiceCommand? {
        val ready = readySpans().maxByOrNull { it.endMs } ?: return null
        if (ready.endMs <= firedThroughMs) return null
        firedThroughMs = ready.endMs
        return ready.command
    }

    private fun readySpans(): List<Span> {
        val found = mutableListOf<Span>()
        val tokens = words
        for (i in tokens.indices) {
            var text = ""
            for (j in i until minOf(i + MAX_WORDS_IN_PHRASE, tokens.size)) {
                if (j > i && tokens[j].startMs - tokens[j - 1].endMs > MAX_INTERNAL_GAP_MS) break
                text += tokens[j].word
                val command = VoiceCommandParser.parseCommand(text) ?: continue
                val gapBefore = if (i == 0) {
                    predecessorEndMs?.let { tokens[i].startMs - it } ?: Long.MAX_VALUE
                } else {
                    tokens[i].startMs - tokens[i - 1].endMs
                }
                if (gapBefore < pauseMs) continue
                val gapAfter = if (j + 1 < tokens.size) {
                    tokens[j + 1].startMs - tokens[j].endMs
                } else {
                    confirmedThroughMs - tokens[j].endMs
                }
                if (gapAfter < pauseMs) continue
                found.add(Span(command, tokens[j].endMs))
            }
        }
        return found
    }

    private data class Span(val command: VoiceCommand, val endMs: Long)

    private sealed class TextState {
        data object Idle : TextState()
        data class Foreign(val atMs: Long) : TextState()
        data class Pending(val command: VoiceCommand, val sinceMs: Long, val leadingOk: Boolean) : TextState()
        data class Fired(val command: VoiceCommand, val atMs: Long) : TextState()
        data class Separated(val atMs: Long) : TextState()
    }

    companion object {
        const val PAUSE_MS = 1_000L
        private const val MAX_INTERNAL_GAP_MS = 800L
        private const val MAX_WORDS_IN_PHRASE = 8
        private const val WORD_MEMORY_MS = 30_000L
    }
}

internal data class TimedWord(val word: String, val startMs: Long, val endMs: Long)

internal object VoiceCommandWords {
    fun parse(hypothesis: String?): List<TimedWord> {
        if (hypothesis.isNullOrBlank()) return emptyList()
        val array = arrayBody(hypothesis, "partial_result") ?: arrayBody(hypothesis, "result") ?: return emptyList()
        val words = mutableListOf<TimedWord>()
        var search = 0
        while (true) {
            val start = array.indexOf('{', search)
            if (start < 0) break
            val end = array.indexOf('}', start)
            if (end < 0) break
            val obj = array.substring(start, end + 1)
            search = end + 1
            val word = jsonString(obj, "word") ?: continue
            val startSec = jsonNumber(obj, "start") ?: continue
            val endSec = jsonNumber(obj, "end") ?: continue
            val startMs = (startSec * 1000.0).toLong()
            val endMs = (endSec * 1000.0).toLong()
            if (endMs < startMs) continue
            words.add(TimedWord(word, startMs, endMs))
        }
        return words
    }

    fun isUnknown(word: String): Boolean {
        val normalized = word.trim().lowercase()
        return normalized.isEmpty() || normalized == "[unk]" || normalized == "<unk>" || normalized == "unk"
    }

    fun withoutUnknown(text: String): String {
        return text.replace("[unk]", " ", ignoreCase = true)
            .replace("<unk>", " ", ignoreCase = true)
    }

    fun commandAfterStrippingUnknown(text: String): VoiceCommand? {
        val stripped = withoutUnknown(text)
        if (stripped.isBlank()) return null
        return VoiceCommandParser.parseCommand(stripped)
    }

    private fun arrayBody(json: String, key: String): String? {
        val marker = "\"$key\""
        val keyAt = json.indexOf(marker)
        if (keyAt < 0) return null
        val open = json.indexOf('[', keyAt + marker.length)
        if (open < 0) return null
        val close = json.indexOf(']', open)
        if (close < 0) return null
        return json.substring(open + 1, close)
    }

    private fun jsonString(obj: String, name: String): String? {
        val match = Regex(""""$name"\s*:\s*"([^"\\]*)"""").find(obj) ?: return null
        return match.groupValues[1]
    }

    private fun jsonNumber(obj: String, name: String): Double? {
        val match = Regex(""""$name"\s*:\s*(-?\d+(?:\.\d+)?)""").find(obj) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }
}

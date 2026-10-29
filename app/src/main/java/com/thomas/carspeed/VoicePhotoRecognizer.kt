package com.thomas.carspeed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

data class VoicePhotoRecognizerState(
    val activeOwner: String? = null,
    val modelState: String = "idle",
    val modelPath: String? = null,
    val modelReady: Boolean = false,
    val listening: Boolean = false,
    val lastText: String? = null,
    val lastCommand: String? = null,
    val lastError: String? = null
)

enum class VoiceCommand {
    TAKE_PHOTO,
    START_RECORDING,
    STOP_RECORDING,
    RADAR_ALERT_ON,
    RADAR_ALERT_OFF
}

internal object VoiceCommandParser {
    fun parseCommand(text: String): VoiceCommand? {
        val normalized = normalize(text)
        return when {
            RADAR_ALERT_OFF_COMMANDS.any { normalized == it } -> VoiceCommand.RADAR_ALERT_OFF
            RADAR_ALERT_ON_COMMANDS.any { normalized == it } -> VoiceCommand.RADAR_ALERT_ON
            STOP_RECORDING_COMMANDS.any { normalized == it } -> VoiceCommand.STOP_RECORDING
            START_RECORDING_COMMANDS.any { normalized == it } -> VoiceCommand.START_RECORDING
            PHOTO_COMMANDS.any { normalized == it } -> VoiceCommand.TAKE_PHOTO
            else -> null
        }
    }

    fun commandGrammar(): String {
        val commands = (
            PHOTO_COMMANDS +
                START_RECORDING_COMMANDS +
                STOP_RECORDING_COMMANDS +
                RADAR_ALERT_ON_COMMANDS +
                RADAR_ALERT_OFF_COMMANDS +
                GRAMMAR_ONLY_COMMANDS
            )
            .distinct()
            .joinToString(", ") { "\"$it\"" }
        return "[$commands, \"[unk]\"]"
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.CHINA)
            .replace(" ", "")
            .replace("，", "")
            .replace(",", "")
            .replace("。", "")
            .replace(".", "")
            .replace("！", "")
            .replace("!", "")
            .replace("？", "")
            .replace("?", "")
    }

    private val PHOTO_COMMANDS = listOf("我要拍照", "帮我拍照", "给我拍照", "照一张", "拍一张")
    private val START_RECORDING_COMMANDS = listOf("开始录像", "开始录制", "开始拍摄", "我要录像", "我要录制")
    private val STOP_RECORDING_COMMANDS = listOf("停止录像", "停止录制", "停止拍摄", "结束录像", "结束录制")
    private val RADAR_ALERT_ON_COMMANDS = listOf("测速雷达开")
    private val RADAR_ALERT_OFF_COMMANDS = listOf("测速雷达关")
    private val GRAMMAR_ONLY_COMMANDS = listOf(
        "我 要 拍 照",
        "帮 我 拍 照",
        "给 我 拍 照",
        "开 始 录 像",
        "开 始 录 制",
        "开 始 拍 摄",
        "我 要 录 像",
        "我 要 录 制",
        "停 止 录 像",
        "停 止 录 制",
        "停 止 拍 摄",
        "结 束 录 像",
        "结 束 录 制",
        "测 速 雷 达 开",
        "测 速 雷 达 关"
    )
}

// 播报开始时间只做记录：拍照口令不再靠播报后的静音窗口丢弃，改看前后有没有约 1 秒停顿。
internal object AppSpeechGuard {
    @Volatile var lastSpeechStartedAtMs = 0L
        private set

    fun markSpeechStarted(nowMs: Long = System.currentTimeMillis()) {
        lastSpeechStartedAtMs = nowMs
    }
}

internal object VoiceRetryBackoff {
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 10 * 60_000L

    /** 第 1 次失败等 5 秒，之后每次翻倍，最长 10 分钟。 */
    fun delayMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 20)
        return (BASE_DELAY_MS shl shift).coerceAtMost(MAX_DELAY_MS)
    }
}

object VoicePhotoRecognizer {
    private const val ENGINE = "vosk"
    private const val MODEL_NAME = "vosk-model-small-cn-0.22"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val SAMPLE_RATE = 16_000.0f
    private const val RESTART_DELAY_MS = 1_000L
    private const val COMMAND_DEBOUNCE_MS = 3_000L
    private const val MAX_CONSECUTIVE_FAILURES = 8
    private const val WAIT_FOR_WIFI_RETRY_MS = 60_000L
    private const val SLOW_RETRY_MS = 30 * 60_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var state = VoicePhotoRecognizerState()

    @Volatile private var model: Model? = null
    @Volatile private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    @Volatile private var externalMic = false
    private val recognizerLock = Any()
    private val downsampler = PcmDownsampler()
    private val pcmQueue = ArrayBlockingQueue<PcmChunk>(4)
    private val pcmWorkerStarted = AtomicBoolean(false)
    @Volatile private var activeOwner: String? = null
    private var activeContext: Context? = null
    private var activeOnCommand: ((VoiceCommand, Long?) -> Unit)? = null
    private var starting = false
    private var lastAcceptedCommand: VoiceCommand? = null
    private var lastAcceptedCommandAtMs = 0L
    private val commandGate = IsolatedCommandGate()
    private val streamClock = VoiceStreamClock()
    private var pauseToken = 0
    private var pauseRunnable: Runnable? = null
    private var armedText: String? = null
    private var sawWordTimes = false
    private var consecutiveFailures = 0

    private class ModelDownloadDeferred(message: String) : IllegalStateException(message)

    fun currentState(): VoicePhotoRecognizerState = state

    fun start(context: Context, owner: String, onCommand: (VoiceCommand, Long?) -> Unit) {
        if (activeOwner != null && activeOwner != owner) {
            val previousOwner = activeOwner
            releaseSpeechService()
            starting = false
            resetCommandGate()
            previousOwner?.let { VoicePhotoMonitor.markStopped(it) }
        }
        activeContext = context.applicationContext
        activeOwner = owner
        activeOnCommand = onCommand
        if (externalMic) {
            if (recognizer != null || starting) return
        } else if (speechService != null || starting) {
            return
        } else if (recognizer != null) {
            attachMicrophone(owner)
            return
        }
        starting = true
        updateState(owner, modelState = "preparing", listening = false, lastError = null)
        worker.execute {
            try {
                val appContext = context.applicationContext
                val modelDir = ensureModel(appContext)
                val loadedModel = model ?: Model(modelDir.absolutePath).also { model = it }
                mainHandler.post {
                    if (activeOwner != owner) {
                        starting = false
                        return@post
                    }
                    try {
                        val readyRecognizer = recognizer ?: newRecognizer(loadedModel).also { recognizer = it }
                        resetCommandGate()
                        if (externalMic) {
                            starting = false
                            updateState(
                                owner,
                                modelState = "ready",
                                modelPath = modelDir.absolutePath,
                                modelReady = true,
                                listening = true,
                                lastError = null
                            )
                            consecutiveFailures = 0
                            VoicePhotoMonitor.markStarted(owner, ENGINE)
                            return@post
                        }
                        speechService = SpeechService(readyRecognizer, SAMPLE_RATE).also { service ->
                            service.startListening(listener(owner))
                        }
                        starting = false
                        consecutiveFailures = 0
                        updateState(
                            owner,
                            modelState = "ready",
                            modelPath = modelDir.absolutePath,
                            modelReady = true,
                            listening = true,
                            lastError = null
                        )
                        VoicePhotoMonitor.markStarted(owner, ENGINE)
                    } catch (e: Exception) {
                        starting = false
                        handleError(owner, e)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    starting = false
                    handleError(owner, e)
                }
            }
        }
    }

    fun beginExternalCapture(context: Context, owner: String, onCommand: (VoiceCommand, Long?) -> Unit) {
        activeContext = context.applicationContext
        activeOwner = owner
        activeOnCommand = onCommand
        val alreadyExternal = externalMic && speechService == null
        if (!alreadyExternal) {
            releaseSpeechService()
            synchronized(recognizerLock) {
                externalMic = true
                downsampler.reset()
            }
        }
        if (recognizer == null && !starting) {
            start(context, owner, onCommand)
        } else if (recognizer != null) {
            updateState(owner, listening = true, lastError = null)
            VoicePhotoMonitor.markStarted(owner, ENGINE)
        }
    }

    fun endExternalCapture(owner: String) {
        if (activeOwner != null && activeOwner != owner) return
        synchronized(recognizerLock) {
            externalMic = false
            downsampler.reset()
        }
        pcmQueue.clear()
    }

    fun feedExternalPcm(data: ByteArray, length: Int, sampleRate: Int) {
        if (!externalMic || length <= 1) return
        ensurePcmWorker()
        val copy = ByteArray(length)
        System.arraycopy(data, 0, copy, 0, length)
        val chunk = PcmChunk(copy, sampleRate, SystemClock.elapsedRealtime())
        if (!pcmQueue.offer(chunk)) {
            pcmQueue.poll()
            pcmQueue.offer(chunk)
        }
    }

    fun stop(owner: String) {
        if (activeOwner != owner) return
        externalMic = false
        streamClock.reset()
        resetCommandGate()
        mainHandler.removeCallbacksAndMessages(null)
        releaseSpeechService()
        synchronized(recognizerLock) {
            downsampler.reset()
            runCatching { recognizer?.close() }
            recognizer = null
        }
        pcmQueue.clear()
        activeOwner = null
        activeContext = null
        activeOnCommand = null
        starting = false
        consecutiveFailures = 0
        state = state.copy(activeOwner = owner, listening = false)
        VoicePhotoMonitor.markStopped(owner)
    }

    private fun listener(owner: String): RecognitionListener {
        return object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                handleText(owner, hypothesis, endpoint = false)
            }

            override fun onResult(hypothesis: String?) {
                handleText(owner, hypothesis, endpoint = true)
            }

            override fun onFinalResult(hypothesis: String?) {
                handleText(owner, hypothesis, endpoint = true)
                if (activeOwner == owner && speechService == null) restart(owner)
            }

            override fun onError(exception: Exception?) {
                handleError(owner, exception ?: RuntimeException("未知语音识别错误"))
            }

            override fun onTimeout() {
                restart(owner)
            }
        }
    }

    private fun handleText(owner: String, hypothesis: String?, endpoint: Boolean) {
        val text = recognizedText(hypothesis)
        if (text.isNotBlank()) {
            updateState(owner, lastText = text, lastError = null)
            VoicePhotoMonitor.markHeard(owner, text)
        }
        val timed = VoiceCommandWords.parse(hypothesis).filterNot { VoiceCommandWords.isUnknown(it.word) }
        if (timed.isNotEmpty()) {
            sawWordTimes = true
            val endMs = timed.maxOf { it.endMs }
            streamClock.alignIfNeeded(endMs, SystemClock.elapsedRealtime())
            val confirmedThroughMs = if (endpoint) endMs + IsolatedCommandGate.PAUSE_MS else endMs
            val command = commandGate.onWords(timed, confirmedThroughMs)
            deliver(owner, command, command?.let { commandGate.firedStartMs() })
            // 同一个词的结束时间会来回微调；只有词语本身变了才重新计算后面的 1 秒。
            val spoken = "${timed.first().startMs / 300}:${timed.joinToString("") { it.word }}"
            if (!endpoint && spoken != armedText) {
                armedText = spoken
                schedulePause(owner, endMs)
            }
            return
        }
        if (sawWordTimes) return
        val nowMs = System.currentTimeMillis()
        deliver(owner, commandGate.onText(text, nowMs, endpoint), null)
        if (!endpoint && text.isNotBlank() && text != armedText) {
            armedText = text
            schedulePause(owner, streamEndMs = null)
        }
    }

    private fun deliver(owner: String, command: VoiceCommand?, streamStartMs: Long?) {
        if (command == null || activeOwner != owner || !shouldAcceptCommand(command)) return
        state = state.copy(lastCommand = command.name)
        VoicePhotoMonitor.markTriggered(owner)
        val spokenAtElapsedMs = streamStartMs?.let { streamClock.elapsedFor(it) }
        activeOnCommand?.invoke(command, spokenAtElapsedMs)
    }

    private fun schedulePause(owner: String, streamEndMs: Long?) {
        pauseRunnable?.let { mainHandler.removeCallbacks(it) }
        val token = ++pauseToken
        val runnable = Runnable {
            if (token != pauseToken || activeOwner != owner) return@Runnable
            val command = if (streamEndMs != null) {
                commandGate.onSilenceAdvanced(streamEndMs + IsolatedCommandGate.PAUSE_MS)
            } else {
                commandGate.onClock(System.currentTimeMillis())
            }
            val streamStartMs = if (streamEndMs != null) command?.let { commandGate.firedStartMs() } else null
            deliver(owner, command, streamStartMs)
        }
        pauseRunnable = runnable
        mainHandler.postDelayed(runnable, IsolatedCommandGate.PAUSE_MS)
    }

    private fun resetCommandGate() {
        pauseToken += 1
        pauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pauseRunnable = null
        armedText = null
        sawWordTimes = false
        commandGate.reset()
    }

    private fun shouldAcceptCommand(command: VoiceCommand): Boolean {
        val now = System.currentTimeMillis()
        if (command == lastAcceptedCommand && now - lastAcceptedCommandAtMs < COMMAND_DEBOUNCE_MS) {
            return false
        }
        lastAcceptedCommand = command
        lastAcceptedCommandAtMs = now
        return true
    }

    private fun handleError(owner: String, error: Exception) {
        val message = error.message ?: error.javaClass.simpleName
        releaseSpeechService()
        synchronized(recognizerLock) {
            runCatching { recognizer?.close() }
            recognizer = null
        }
        if (error is ModelDownloadDeferred) {
            // 等 Wi‑Fi 不算失败；只是定时看一眼网络，不会产生流量。
            updateState(owner, modelState = "waiting_wifi", listening = false, lastError = message)
            VoicePhotoMonitor.markError(owner, -3, message)
            restart(owner, WAIT_FOR_WIFI_RETRY_MS)
            return
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            val stoppedMessage = "语音识别连续失败：$message。30 分钟后再试，也可以检查网络后重新开启语音口令。"
            updateState(owner, modelState = "failed", listening = false, lastError = stoppedMessage)
            VoicePhotoMonitor.markError(owner, -3, stoppedMessage)
            restart(owner, SLOW_RETRY_MS)
            return
        }
        updateState(owner, listening = false, lastError = message)
        VoicePhotoMonitor.markError(owner, -3, message)
        restart(owner, VoiceRetryBackoff.delayMs(consecutiveFailures))
    }


    private fun newRecognizer(loadedModel: Model): Recognizer {
        streamClock.reset()
        return Recognizer(loadedModel, SAMPLE_RATE, VoiceCommandParser.commandGrammar()).also { created ->
            created.setWords(true)
            created.setPartialWords(true)
            // 说完后静音满 1 秒才截断这句话，和口令前后停顿用的是同一秒。
            created.setEndpointerDelays(5.0f, 1.0f, 30.0f)
        }
    }

    private fun attachMicrophone(owner: String) {
        val readyRecognizer = recognizer ?: return
        try {
            releaseSpeechService()
            resetCommandGate()
            speechService = SpeechService(readyRecognizer, SAMPLE_RATE).also { service ->
                service.startListening(listener(owner))
            }
            starting = false
            consecutiveFailures = 0
            updateState(owner, modelState = "ready", modelReady = true, listening = true, lastError = null)
            VoicePhotoMonitor.markStarted(owner, ENGINE)
        } catch (e: Exception) {
            starting = false
            handleError(owner, e)
        }
    }

    private fun releaseSpeechService() {
        val service = speechService ?: return
        speechService = null
        runCatching { service.stop() }
        runCatching { service.shutdown() }
    }

    private fun ensurePcmWorker() {
        if (!pcmWorkerStarted.compareAndSet(false, true)) return
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CarSpeedVoicePcm").apply { isDaemon = true }
        }.execute {
            while (true) {
                val chunk = try {
                    pcmQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                processPcmChunk(chunk)
            }
        }
    }

    private fun processPcmChunk(chunk: PcmChunk) {
        val owner = activeOwner ?: return
        try {
            val payload = synchronized(recognizerLock) {
                if (!externalMic) return
                val readyRecognizer = recognizer ?: return
                val samples = downsampler.accept(chunk.data, chunk.data.size, chunk.sampleRate, SAMPLE_RATE.toInt())
                if (samples.isEmpty()) return
                val endpoint = readyRecognizer.acceptWaveForm(samples, samples.size)
                val json = if (endpoint) readyRecognizer.result else readyRecognizer.partialResult
                Triple(endpoint, json, samples.size)
            }
            val durationMs = payload.third * 1_000L / SAMPLE_RATE.toInt()
            streamClock.noteChunk(durationMs, chunk.capturedAtElapsedMs)
            mainHandler.post {
                if (activeOwner != owner || !externalMic) return@post
                handleText(owner, payload.second, endpoint = payload.first)
            }
        } catch (e: Exception) {
            mainHandler.post {
                if (activeOwner == owner) handleError(owner, e)
            }
        }
    }

    private class PcmChunk(
        val data: ByteArray,
        val sampleRate: Int,
        val capturedAtElapsedMs: Long
    )

    private fun restart(owner: String, delayMs: Long = RESTART_DELAY_MS) {
        if (activeOwner != owner) return
        resetCommandGate()
        releaseSpeechService()
        val context = activeContext ?: return
        val onCommand = activeOnCommand ?: return
        mainHandler.postDelayed({ start(context, owner, onCommand) }, delayMs)
    }

    private fun ensureModel(context: Context): File {
        val modelDir = modelDir(context)
        if (isModelReady(modelDir)) {
            updateState(activeOwner, modelState = "ready", modelPath = modelDir.absolutePath, modelReady = true)
            return modelDir
        }
        if (!isUnmeteredNetwork(context)) {
            throw ModelDownloadDeferred("等待连接 Wi‑Fi 后下载离线语音模型（约 42MB）")
        }
        updateState(activeOwner, modelState = "downloading", modelPath = modelDir.absolutePath, modelReady = false)
        val baseDir = modelDir.parentFile ?: context.filesDir
        baseDir.mkdirs()
        val zipFile = File(context.cacheDir, "$MODEL_NAME.zip")
        downloadModelZip(zipFile)
        updateState(activeOwner, modelState = "unpacking", modelPath = modelDir.absolutePath, modelReady = false)
        val tmpDir = File(baseDir, "$MODEL_NAME.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        try {
            unzipModel(zipFile, tmpDir)
        } catch (e: Exception) {
            tmpDir.deleteRecursively()
            throw IllegalStateException("离线语音模型压缩包损坏：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            zipFile.delete()
        }
        modelDir.deleteRecursively()
        if (!tmpDir.renameTo(modelDir)) {
            throw IllegalStateException("无法保存离线语音模型")
        }
        if (!isModelReady(modelDir)) {
            throw IllegalStateException("离线语音模型文件不完整")
        }
        updateState(activeOwner, modelState = "ready", modelPath = modelDir.absolutePath, modelReady = true)
        return modelDir
    }

    private fun isUnmeteredNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun downloadModelZip(target: File) {
        val partFile = File(target.parentFile, target.name + ".part")
        partFile.delete()
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("下载离线语音模型失败：HTTP $code")
            }
            val expectedBytes = connection.contentLengthLong
            val written = connection.inputStream.use { input ->
                FileOutputStream(partFile).use { output -> input.copyTo(output) }
            }
            if (expectedBytes > 0 && written != expectedBytes) {
                throw IllegalStateException("离线语音模型下载不完整（$written/$expectedBytes 字节）")
            }
            target.delete()
            if (!partFile.renameTo(target)) {
                throw IllegalStateException("无法保存离线语音模型压缩包")
            }
        } catch (e: Exception) {
            partFile.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun unzipModel(zipFile: File, outputDir: File) {
        val rootPath = outputDir.canonicalPath + File.separator
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val strippedName = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (strippedName.isBlank()) continue
                val outFile = File(outputDir, strippedName)
                if (!outFile.canonicalPath.startsWith(rootPath)) {
                    throw IllegalStateException("模型压缩包路径非法")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun modelDir(context: Context): File {
        return File(context.filesDir, "vosk/$MODEL_NAME")
    }

    private fun isModelReady(dir: File): Boolean {
        return File(dir, "am/final.mdl").exists() && File(dir, "conf/model.conf").exists()
    }

    private fun recognizedText(hypothesis: String?): String {
        if (hypothesis.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(hypothesis)
            json.optString("partial").ifBlank { json.optString("text") }
        } catch (_: RuntimeException) {
            hypothesis
        }
    }

    private fun updateState(
        owner: String?,
        modelState: String = state.modelState,
        modelPath: String? = state.modelPath,
        modelReady: Boolean = state.modelReady,
        listening: Boolean = state.listening,
        lastText: String? = state.lastText,
        lastError: String? = state.lastError
    ) {
        state = state.copy(
            activeOwner = owner ?: state.activeOwner,
            modelState = modelState,
            modelPath = modelPath,
            modelReady = modelReady,
            listening = listening,
            lastText = lastText,
            lastError = lastError
        )
    }

}

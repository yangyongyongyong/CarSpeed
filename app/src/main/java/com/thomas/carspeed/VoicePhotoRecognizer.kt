package com.thomas.carspeed

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
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

    private val PHOTO_COMMANDS = listOf("我要拍照", "帮我拍照", "给我拍照", "拍照", "照相", "照一张", "拍一张")
    private val START_RECORDING_COMMANDS = listOf("开始录像", "开始录制", "开始拍摄", "我要录像", "我要录制")
    private val STOP_RECORDING_COMMANDS = listOf("停止录像", "停止录制", "停止拍摄", "结束录像", "结束录制")
    private val RADAR_ALERT_ON_COMMANDS = listOf("测速雷达开")
    private val RADAR_ALERT_OFF_COMMANDS = listOf("测速雷达关")
    private val GRAMMAR_ONLY_COMMANDS = listOf(
        "我 要 拍 照",
        "帮 我 拍 照",
        "给 我 拍 照",
        "拍 照",
        "照 相",
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

object VoicePhotoRecognizer {
    private const val ENGINE = "vosk"
    private const val MODEL_NAME = "vosk-model-small-cn-0.22"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val SAMPLE_RATE = 16_000.0f
    private const val RESTART_DELAY_MS = 1_000L
    private const val COMMAND_DEBOUNCE_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var state = VoicePhotoRecognizerState()

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var activeOwner: String? = null
    private var activeContext: Context? = null
    private var activeOnCommand: ((VoiceCommand) -> Unit)? = null
    private var starting = false
    private var lastAcceptedCommand: VoiceCommand? = null
    private var lastAcceptedCommandAtMs = 0L

    fun currentState(): VoicePhotoRecognizerState = state

    fun start(context: Context, owner: String, onCommand: (VoiceCommand) -> Unit) {
        if (activeOwner != null && activeOwner != owner) {
            val previousOwner = activeOwner
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
            starting = false
            previousOwner?.let { VoicePhotoMonitor.markStopped(it) }
        }
        activeContext = context.applicationContext
        activeOwner = owner
        activeOnCommand = onCommand
        if (speechService != null || starting) return
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
                        val recognizer = Recognizer(loadedModel, SAMPLE_RATE, VoiceCommandParser.commandGrammar())
                        speechService = SpeechService(recognizer, SAMPLE_RATE).also { service ->
                            service.startListening(listener(owner))
                        }
                        starting = false
                        updateState(
                            owner,
                            modelState = "ready",
                            modelPath = modelDir.absolutePath,
                            modelReady = true,
                            listening = true,
                            lastError = null
                        )
                        VoicePhotoMonitor.markStarted(owner, ENGINE)
                    } catch (e: RuntimeException) {
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

    fun stop(owner: String) {
        if (activeOwner != owner) return
        mainHandler.removeCallbacksAndMessages(null)
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        activeOwner = null
        activeContext = null
        activeOnCommand = null
        starting = false
        state = state.copy(activeOwner = owner, listening = false)
        VoicePhotoMonitor.markStopped(owner)
    }

    private fun listener(owner: String): RecognitionListener {
        return object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                handleText(owner, hypothesis, allowCommand = false)
            }

            override fun onResult(hypothesis: String?) {
                handleText(owner, hypothesis, allowCommand = true)
            }

            override fun onFinalResult(hypothesis: String?) {
                handleText(owner, hypothesis, allowCommand = true)
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

    private fun handleText(owner: String, hypothesis: String?, allowCommand: Boolean) {
        val text = recognizedText(hypothesis)
        if (text.isBlank()) return
        updateState(owner, lastText = text, lastError = null)
        VoicePhotoMonitor.markHeard(owner, text)
        if (!allowCommand) return
        val command = VoiceCommandParser.parseCommand(text) ?: return
        if (activeOwner != owner || !shouldAcceptCommand(command)) return
        state = state.copy(lastCommand = command.name)
        VoicePhotoMonitor.markTriggered(owner)
        activeOnCommand?.invoke(command)
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
        updateState(owner, listening = false, lastError = message)
        VoicePhotoMonitor.markError(owner, -3, message)
        speechService?.shutdown()
        speechService = null
        restart(owner)
    }

    private fun restart(owner: String) {
        if (activeOwner != owner) return
        speechService?.shutdown()
        speechService = null
        val context = activeContext ?: return
        val onCommand = activeOnCommand ?: return
        mainHandler.postDelayed({ start(context, owner, onCommand) }, RESTART_DELAY_MS)
    }

    private fun ensureModel(context: Context): File {
        val modelDir = modelDir(context)
        if (isModelReady(modelDir)) {
            updateState(activeOwner, modelState = "ready", modelPath = modelDir.absolutePath, modelReady = true)
            return modelDir
        }
        updateState(activeOwner, modelState = "downloading", modelPath = modelDir.absolutePath, modelReady = false)
        val baseDir = modelDir.parentFile ?: context.filesDir
        baseDir.mkdirs()
        val zipFile = File(context.cacheDir, "$MODEL_NAME.zip")
        URL(MODEL_URL).openStream().use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        }
        updateState(activeOwner, modelState = "unpacking", modelPath = modelDir.absolutePath, modelReady = false)
        val tmpDir = File(baseDir, "$MODEL_NAME.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        unzipModel(zipFile, tmpDir)
        zipFile.delete()
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

    private fun unzipModel(zipFile: File, outputDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val strippedName = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (strippedName.isBlank()) continue
                val outFile = File(outputDir, strippedName)
                if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath)) {
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

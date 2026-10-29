package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class PlateRecognitionState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val analyzerBound: Boolean = false,
    val engine: String = PlateRecognitionMonitor.ENGINE_NONE,
    val engineReady: Boolean = false,
    val analyzedFrames: Int = 0,
    val recordedSamples: Int = 0,
    val lastAnalyzedAtMs: Long = 0L,
    val lastFrameWidth: Int? = null,
    val lastFrameHeight: Int? = null,
    val lastRotationDegrees: Int? = null,
    val lastPlateCode: String? = null,
    val lastPlateCount: Int = 0,
    val recognizedPlates: Int = 0,
    val smartFocusTriggeredCount: Int = 0,
    val lastSmartFocusAtMs: Long = 0L,
    val lastSidecarPath: String? = null,
    val lastError: String? = null
)

data class PlateRecognitionResult(
    val code: String,
    val type: Int,
    val typeLabel: String,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("code", code)
            .put("type", type)
            .put("typeLabel", typeLabel)
            .put("confidence", confidence.toDouble())
            .put("x1", x1.toDouble())
            .put("y1", y1.toDouble())
            .put("x2", x2.toDouble())
            .put("y2", y2.toDouble())
    }
}

data class PlateRecognitionSample(
    val elapsedMs: Long,
    val timeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val plates: List<PlateRecognitionResult>,
    val ocrText: String? = null
)

data class PlateOcrFrame(
    val plates: List<PlateRecognitionResult>,
    val text: String? = null
)

data class PlateOverlayFrame(
    val imageWidth: Int,
    val imageHeight: Int,
    val rotationDegrees: Int,
    val plates: List<PlateRecognitionResult>
)

fun PlateRecognitionSample.toOverlayFrame(): PlateOverlayFrame {
    return PlateOverlayFrame(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        rotationDegrees = rotationDegrees,
        plates = plates
    )
}

class PlateRecognitionSession(
    private val startWallMs: Long,
    private val startElapsedMs: Long,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val analyzerBound: Boolean = true,
    private val initialError: String? = null
) {
    private val lock = Any()
    private val samples = mutableListOf<PlateRecognitionSample>()
    /** 同一辆车的读数串成轨迹，整条轨迹只用一个号，避免逐帧"换牌"。 */
    private val tracks = mutableListOf<PlateTrackBinder.Track>()
    private var lastAnalyzeElapsedMs = 0L
    private var analyzedFrames = 0
    private var recognizedPlates = 0

    /** 录像会话里抓到的 JPEG 帧。在后台线程调用。 */
    fun analyzeJpeg(jpeg: ByteArray, capturedElapsedMs: Long): PlateOverlayFrame? {
        if (capturedElapsedMs - lastAnalyzeElapsedMs < intervalMs) return null
        lastAnalyzeElapsedMs = capturedElapsedMs
        var bitmap: Bitmap? = null
        return try {
            val rotationDegrees = jpegRotationDegrees(jpeg)
            bitmap = decodeForAnalysis(jpeg) ?: return null
            val recognition = PlateRecognitionEngine.recognize(bitmap, rotationDegrees)
            val elapsedMs = (capturedElapsedMs - startElapsedMs).coerceAtLeast(0L)
            val raw = PlateRecognitionSample(
                elapsedMs = elapsedMs,
                timeMs = startWallMs + elapsedMs,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                rotationDegrees = rotationDegrees,
                plates = recognition.plates,
                ocrText = recognition.text
            )
            val sampleCount: Int
            val frameCount: Int
            val totalPlateCount: Int
            // 先并入轨迹再取号：画面和存盘用的是同一个号，回放时不会跟录像时看到的对不上。
            val sample = synchronized(lock) {
                val stabilized = PlateTrackBinder.stabilize(tracks, raw)
                analyzedFrames += 1
                recognizedPlates += stabilized.plates.size
                // 存原始读数：收尾时要拿未压缩的观测重新算全局最优，稳定后的结果信息已经塌缩了。
                samples += raw
                frameCount = analyzedFrames
                sampleCount = samples.size
                totalPlateCount = recognizedPlates
                stabilized
            }
            PlateRecognitionMonitor.onFrame(sample, frameCount, sampleCount, totalPlateCount)
            sample.toOverlayFrame()
        } catch (e: RuntimeException) {
            PlateRecognitionMonitor.onError(e.message ?: e.javaClass.simpleName)
            null
        }
        // 不手动 recycle：识别超时后 ML Kit 后台线程可能还在读这张图，交给 GC。
    }

    private fun decodeForAnalysis(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null
        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= ANALYSIS_LONG_EDGE) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    private fun jpegRotationDegrees(jpeg: ByteArray): Int {
        return runCatching {
            when (
                ExifInterface(ByteArrayInputStream(jpeg))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
    }

    fun finish(context: Context, videoFile: File): File? {
        // 收尾时用原始读数重算一遍：整段都看完再定号，比录像过程中的实时判断更准。
        val snapshot = synchronized(lock) { PlateTrackBinder.bindAll(samples.toList()) }
        return try {
            val file = PlateRecognitionStorage.writeSidecar(
                context = context,
                videoFile = videoFile,
                startWallMs = startWallMs,
                intervalMs = intervalMs,
                analyzerBound = analyzerBound,
                error = initialError ?: PlateRecognitionMonitor.currentState().lastError,
                samples = snapshot
            )
            PlateRecognitionMonitor.onSaved(file, snapshot.size)
            file
        } catch (e: Exception) {
            PlateRecognitionMonitor.onError(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    fun discard() {
        PlateRecognitionMonitor.onInactive()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1_000L
        private const val ANALYSIS_LONG_EDGE = 1280
    }
}

object PlateRecognitionStorage {
    fun writeSidecar(
        context: Context,
        videoFile: File,
        startWallMs: Long,
        intervalMs: Long,
        analyzerBound: Boolean,
        error: String?,
        samples: List<PlateRecognitionSample>
    ): File {
        val file = VideoStorage.plateFileFor(context, videoFile)
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("videoFileName", videoFile.name)
            .put("startTimeMs", startWallMs)
            .put("intervalMs", intervalMs)
            .put("engine", PlateRecognitionMonitor.ENGINE_MLKIT_CHINESE_OCR)
            .put("engineReady", PlateRecognitionEngine.isReady())
            .put("analyzerBound", analyzerBound)
            .put("error", error ?: JSONObject.NULL)
            .put("note", "离线识别结果来自 ML Kit 中文 OCR 车牌候选筛选；坐标基于分析帧尺寸，不直接写入视频画面。")
            .put("samples", JSONArray(samples.map { it.toJson() }))
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    fun writeSidecar(
        videoFile: File,
        startWallMs: Long,
        intervalMs: Long,
        analyzerBound: Boolean,
        error: String?,
        samples: List<PlateRecognitionSample>
    ): File {
        val file = VideoStorage.plateFileFor(videoFile)
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("videoFileName", videoFile.name)
            .put("startTimeMs", startWallMs)
            .put("intervalMs", intervalMs)
            .put("engine", PlateRecognitionMonitor.ENGINE_MLKIT_CHINESE_OCR)
            .put("engineReady", PlateRecognitionEngine.isReady())
            .put("analyzerBound", analyzerBound)
            .put("error", error ?: JSONObject.NULL)
            .put("note", "离线识别结果来自 ML Kit 中文 OCR 车牌候选筛选；坐标基于分析帧尺寸，不直接写入视频画面。")
            .put("samples", JSONArray(samples.map { it.toJson() }))
        file.parentFile?.mkdirs()
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    fun readSamples(videos: List<File>, offsetsMs: List<Long>): List<PlateRecognitionSample> {
        return videos.flatMapIndexed { index, video ->
            val file = VideoStorage.plateFileFor(video)
            if (!file.exists()) return@flatMapIndexed emptyList()
            try {
                parseSamplesText(file.readText(Charsets.UTF_8), offsetsMs.getOrElse(index) { 0L })
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun readSamples(
        context: Context,
        videos: List<File>,
        offsetsMs: List<Long>,
        durationsMs: List<Long> = emptyList()
    ): List<PlateRecognitionSample> {
        return videos.flatMapIndexed { index, video ->
            val offset = offsetsMs.getOrElse(index) { 0L }
            val durationMs = durationsMs.getOrElse(index) { 0L }
            VideoStorage.plateFilesFor(context, video).firstNotNullOfOrNull { file ->
                if (!file.exists()) return@firstNotNullOfOrNull null
                try {
                    parseSamplesText(file.readText(Charsets.UTF_8), offset, durationMs)
                } catch (_: Exception) {
                    null
                }
            }.orEmpty()
        }
    }

    fun parseSamplesText(text: String, offsetMs: Long = 0L, durationMs: Long = 0L): List<PlateRecognitionSample> {
        val root = JSONObject(text)
        val samples = root.optJSONArray("samples") ?: return emptyList()
        return (0 until samples.length()).mapNotNull { index ->
            val sample = samples.optJSONObject(index) ?: return@mapNotNull null
            val localElapsedMs = sample.optLong("elapsedMs", -1L)
            if (localElapsedMs < 0L) return@mapNotNull null
            if (durationMs > 0L && localElapsedMs > durationMs + SEGMENT_SIDE_CAR_TOLERANCE_MS) return@mapNotNull null
            val platesJson = sample.optJSONArray("plates") ?: JSONArray()
            PlateRecognitionSample(
                elapsedMs = offsetMs + localElapsedMs,
                timeMs = sample.optLong("timeMs", 0L),
                imageWidth = sample.optInt("imageWidth", 0),
                imageHeight = sample.optInt("imageHeight", 0),
                rotationDegrees = sample.optInt("rotationDegrees", 0),
                plates = (0 until platesJson.length()).mapNotNull { plateIndex ->
                    val plate = platesJson.optJSONObject(plateIndex) ?: return@mapNotNull null
                    PlateRecognitionResult(
                        code = plate.optString("code"),
                        type = plate.optInt("type", -1),
                        typeLabel = plate.optString("typeLabel"),
                        confidence = plate.optDouble("confidence", 0.0).toFloat(),
                        x1 = plate.optDouble("x1", 0.0).toFloat(),
                        y1 = plate.optDouble("y1", 0.0).toFloat(),
                        x2 = plate.optDouble("x2", 0.0).toFloat(),
                        y2 = plate.optDouble("y2", 0.0).toFloat()
                    )
                }.filter { it.code.isNotBlank() }
            )
        }
    }

    private fun PlateRecognitionSample.toJson(): JSONObject {
        return JSONObject()
            .put("elapsedMs", elapsedMs)
            .put("timeMs", timeMs)
            .put("imageWidth", imageWidth)
            .put("imageHeight", imageHeight)
            .put("rotationDegrees", rotationDegrees)
            .put("ocrText", ocrText ?: JSONObject.NULL)
            .put("plates", JSONArray(plates.map { it.toJson() }))
    }

    private const val SEGMENT_SIDE_CAR_TOLERANCE_MS = 500L
}

object PlateRecognitionEngine {
    private val initLock = Any()

    @Volatile
    private var chineseRecognizer: TextRecognizer? = null

    @Volatile
    private var lastError: String? = null

    fun isReady(): Boolean = chineseRecognizer != null

    fun currentError(): String? = lastError

    /** 中文识别模型本身也认拉丁字母和数字，不再每帧多跑一遍拉丁模型。 */
    fun recognize(bitmap: Bitmap, rotationDegrees: Int): PlateOcrFrame {
        val recognizer = ensureChineseRecognizer() ?: return PlateOcrFrame(emptyList())
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)
        return try {
            val text = Tasks.await(
                recognizer.process(inputImage),
                RECOGNITION_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            lastError = null
            PlateOcrFrame(
                plates = extractPlateCandidates(text).distinctBy { it.code },
                text = text.text
                    .trim()
                    .take(MAX_OCR_TEXT_CHARS)
                    .takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            PlateRecognitionMonitor.onError(lastError.orEmpty())
            PlateOcrFrame(emptyList())
        }
    }

    private fun ensureChineseRecognizer(): TextRecognizer? {
        chineseRecognizer?.let { return it }
        return synchronized(initLock) {
            chineseRecognizer?.let { return@synchronized it }
            try {
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { client ->
                    chineseRecognizer = client
                    lastError = null
                    PlateRecognitionMonitor.onEngineReady()
                }
            } catch (e: RuntimeException) {
                lastError = e.message ?: e.javaClass.simpleName
                PlateRecognitionMonitor.onError(lastError.orEmpty())
                null
            }
        }
    }

    private fun extractPlateCandidates(text: Text): List<PlateRecognitionResult> {
        val sources = text.textBlocks.flatMap { block ->
            buildList {
                add(OcrTextSource(block.text, block.boundingBox))
                block.lines.forEach { line ->
                    add(OcrTextSource(line.text, line.boundingBox))
                    val elementText = line.elements.joinToString(separator = "") { it.text }
                    val elementBox = unionRects(line.elements.mapNotNull { it.boundingBox })
                    if (elementText.isNotBlank()) add(OcrTextSource(elementText, elementBox ?: line.boundingBox))
                }
            }
        }
        val strict = sources.flatMap { source -> source.extractMatches(PLATE_REGEX, "OCR候选", OCR_CANDIDATE_CONFIDENCE) }
        if (strict.isNotEmpty()) return strict.distinctBy { it.code }
        return sources
            .flatMap { source -> source.extractMatches(LOOSE_PLATE_REGEX, "疑似车牌", LOOSE_CANDIDATE_CONFIDENCE) }
            .filter { candidate -> candidate.code.hasLetterAndDigit() }
            .distinctBy { it.code }
    }

    private fun normalizePlateText(text: String): String {
        return text.uppercase(Locale.US)
            .replace('粵', '粤')
            .replace(PLATE_SEPARATOR_REGEX, "")
    }

    private fun OcrTextSource.extractMatches(
        regex: Regex,
        label: String,
        confidence: Float
    ): List<PlateRecognitionResult> {
        val normalized = normalizePlateText(text)
        val rect = box?.takeIf { it.isPlausiblePlateBox() } ?: return emptyList()
        return regex.findAll(normalized).map { match ->
            PlateRecognitionResult(
                code = match.value,
                type = UNKNOWN_TYPE,
                typeLabel = label,
                confidence = confidence,
                x1 = rect.left.toFloat(),
                y1 = rect.top.toFloat(),
                x2 = rect.right.toFloat(),
                y2 = rect.bottom.toFloat()
            )
        }.toList()
    }

    private fun Rect.isPlausiblePlateBox(): Boolean {
        val width = width().toFloat()
        val height = height().toFloat()
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (longSide < MIN_PLATE_BOX_WIDTH || shortSide < MIN_PLATE_BOX_HEIGHT) return false
        val ratio = longSide / shortSide.coerceAtLeast(1f)
        return ratio in MIN_PLATE_BOX_RATIO..MAX_PLATE_BOX_RATIO
    }

    private fun unionRects(rects: List<Rect>): Rect? {
        if (rects.isEmpty()) return null
        return Rect(rects.first()).apply {
            rects.drop(1).forEach { union(it) }
        }
    }

    private fun String.hasLetterAndDigit(): Boolean {
        return any { it in 'A'..'Z' } && any { it in '0'..'9' }
    }

    private data class OcrTextSource(
        val text: String,
        val box: Rect?
    )

    private const val UNKNOWN_TYPE = -1
    private const val OCR_CANDIDATE_CONFIDENCE = 1.0f
    private const val LOOSE_CANDIDATE_CONFIDENCE = 0.55f
    private const val RECOGNITION_TIMEOUT_MS = 1_500L
    private const val MAX_OCR_TEXT_CHARS = 300
    private const val MIN_PLATE_BOX_WIDTH = 36f
    private const val MIN_PLATE_BOX_HEIGHT = 8f
    private const val MIN_PLATE_BOX_RATIO = 1.4f
    private const val MAX_PLATE_BOX_RATIO = 8.5f
    private val PLATE_SEPARATOR_REGEX = Regex("[\\s·.。\\-_:：]+")
    private val PLATE_REGEX = Regex("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领警学港澳][A-Z][A-Z0-9]{5,6}")
    private val LOOSE_PLATE_REGEX = Regex("[A-Z][A-Z0-9]{5,6}")
}

object PlateRecognitionMonitor {
    const val ENGINE_NONE = "none"
    const val ENGINE_MLKIT_CHINESE_OCR = "mlkit-chinese-ocr"

    @Volatile
    private var state = PlateRecognitionState()

    @Volatile
    private var latestFrame: PlateOverlayFrame? = null

    fun currentState(): PlateRecognitionState = state

    fun currentOverlayFrame(): PlateOverlayFrame? = latestFrame

    fun onConfigured(enabled: Boolean, analyzerBound: Boolean, error: String? = null) {
        state = state.copy(
            enabled = enabled,
            analyzerBound = analyzerBound,
            active = enabled && analyzerBound && state.active,
            engine = if (enabled) ENGINE_MLKIT_CHINESE_OCR else ENGINE_NONE,
            engineReady = PlateRecognitionEngine.isReady(),
            lastError = error ?: PlateRecognitionEngine.currentError()
        )
    }

    fun onActive(enabled: Boolean, analyzerBound: Boolean) {
        state = PlateRecognitionState(
            enabled = enabled,
            active = enabled && analyzerBound,
            analyzerBound = analyzerBound,
            engine = if (enabled) ENGINE_MLKIT_CHINESE_OCR else ENGINE_NONE,
            engineReady = PlateRecognitionEngine.isReady(),
            lastSidecarPath = state.lastSidecarPath,
            lastError = if (analyzerBound || !enabled) PlateRecognitionEngine.currentError() else state.lastError
        )
        latestFrame = null
    }

    fun onFrame(
        sample: PlateRecognitionSample,
        frameCount: Int,
        sampleCount: Int,
        totalPlateCount: Int
    ) {
        state = state.copy(
            active = true,
            engine = ENGINE_MLKIT_CHINESE_OCR,
            engineReady = PlateRecognitionEngine.isReady(),
            analyzedFrames = frameCount,
            recordedSamples = sampleCount,
            lastAnalyzedAtMs = sample.timeMs,
            lastFrameWidth = sample.imageWidth,
            lastFrameHeight = sample.imageHeight,
            lastRotationDegrees = sample.rotationDegrees,
            lastPlateCode = sample.plates.firstOrNull()?.code ?: state.lastPlateCode,
            lastPlateCount = sample.plates.size,
            recognizedPlates = totalPlateCount,
            smartFocusTriggeredCount = state.smartFocusTriggeredCount,
            lastSmartFocusAtMs = state.lastSmartFocusAtMs,
            lastError = PlateRecognitionEngine.currentError()
        )
        latestFrame = sample.toOverlayFrame()
    }

    fun onSmartFocusTriggered(timeMs: Long) {
        state = state.copy(
            smartFocusTriggeredCount = state.smartFocusTriggeredCount + 1,
            lastSmartFocusAtMs = timeMs
        )
    }

    fun onEngineReady() {
        state = state.copy(
            engine = ENGINE_MLKIT_CHINESE_OCR,
            engineReady = true,
            lastError = null
        )
    }

    fun onSaved(file: File, sampleCount: Int) {
        state = state.copy(
            active = false,
            recordedSamples = sampleCount,
            lastSidecarPath = file.absolutePath
        )
    }

    fun onError(error: String) {
        state = state.copy(lastError = error)
    }

    fun onInactive() {
        state = state.copy(active = false)
        latestFrame = null
    }
}

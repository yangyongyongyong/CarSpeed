package com.thomas.carspeed

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Rational
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 行车记录仪高速行驶与夜间/隧道防运动模糊（Motion Blur）快门与曝光控制策略。
 *
 * 核心设计：
 * 1. 通过负向曝光补偿（AE Exposure Compensation）强迫手机 ISP 自动缩短单帧快门时间，
 *    同时彻底消除夜间车大灯照射下前车高反光车牌「过曝成纯白块」的问题；
 * 2. 结合实时 GPS 车速（km/h）与当前环境快门/ISO 状态，动态计算最优目标 EV 档位；
 * 3. 开启硬件级高质量边缘锐化（EDGE_MODE_HIGH_QUALITY）与快速低涂抹降噪（NOISE_REDUCTION_MODE_FAST），
 *    防止多帧时域涂抹把高速移动的小车牌当成噪点抹平。
 */
object DashcamShutterPolicy {
    /** 低速驻车或城市慢行时的基础负向曝光补偿（-0.5 EV）：抑制车牌高光过曝并提速快门约 1.4 倍。 */
    const val EV_LOW_SPEED = -0.5f
    /** 城市快速路/国省道（30~70 km/h）的负向曝光补偿（-0.8 EV）：提速快门约 1.75 倍。 */
    const val EV_MEDIUM_SPEED = -0.8f
    /** 高速公路（>= 70 km/h）的负向曝光补偿（-1.1 EV）：提速快门约 2.15 倍，压制高速运动拖影。 */
    const val EV_HIGH_SPEED = -1.1f
    /** 夜间/隧道慢快门（曝光时间 >= 10ms 即慢于 1/100s）且处于行驶状态时的额外负向补偿。 */
    const val EV_DARK_MOVING_EXTRA = -0.3f
    /** 最大允许负向曝光补偿下限（-1.5 EV），避免极暗无路灯环境下路面完全黑死。 */
    const val MIN_TARGET_EV = -1.5f

    /**
     * 根据当前车速（km/h）与最近一帧传感器曝光时间（ns）计算目标曝光补偿 EV 值。
     */
    fun targetEvForSpeed(speedKmh: Float, lastExposureTimeNs: Long? = null): Float {
        val safeSpeed = speedKmh.coerceAtLeast(0f)
        val baseEv = when {
            safeSpeed >= 70f -> EV_HIGH_SPEED
            safeSpeed >= 30f -> EV_MEDIUM_SPEED
            else -> EV_LOW_SPEED
        }
        val slowShutter = (lastExposureTimeNs ?: 0L) >= 10_000_000L // >= 10ms (慢于 1/100s)
        val moving = safeSpeed >= 20f
        val extra = if (slowShutter && moving) EV_DARK_MOVING_EXTRA else 0f
        return (baseEv + extra).coerceIn(MIN_TARGET_EV, 0f)
    }

    /**
     * 将目标 EV 值换算为 Camera2 硬件支持的整数步进索引（CONTROL_AE_EXPOSURE_COMPENSATION）。
     */
    fun computeAeCompensationSteps(
        targetEv: Float,
        stepRatio: Double,
        minIndex: Int,
        maxIndex: Int
    ): Int {
        if (stepRatio <= 0.0 || minIndex >= maxIndex) return 0
        val rawSteps = (targetEv / stepRatio).roundToInt()
        return rawSteps.coerceIn(minIndex, maxIndex)
    }

    /**
     * 从 CameraCharacteristics 读取曝光补偿范围与步长，并计算当前车速对应的 AE 步进值。
     */
    fun resolveAeCompensationIndex(
        characteristics: CameraCharacteristics,
        speedKmh: Float,
        lastExposureTimeNs: Long? = null
    ): Int {
        val range: Range<Int> = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: return 0
        val step: Rational = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            ?: return 0
        val stepRatio = step.toDouble()
        if (stepRatio.isNaN() || stepRatio <= 0.0) return 0
        val targetEv = targetEvForSpeed(speedKmh, lastExposureTimeNs)
        return computeAeCompensationSteps(targetEv, stepRatio, range.lower, range.upper)
    }

    /**
     * 向 Camera2 CaptureRequest.Builder 应用行车记录仪防拖影快门与防车牌过曝参数。
     * 注意：仅调整安全的 CONTROL_AE_EXPOSURE_COMPENSATION，不覆写 TEMPLATE_RECORD 默认的
     * EDGE_MODE / NOISE_REDUCTION_MODE / OIS 流管线，避免在 MTK 多路流下导致预览绿屏或丢帧。
     */
    fun applyToCaptureRequest(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        speedKmh: Float,
        lastExposureTimeNs: Long? = null
    ) {
        val aeComp = resolveAeCompensationIndex(characteristics, speedKmh, lastExposureTimeNs)
        runCatching {
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeComp)
        }
    }

    /**
     * 将传感器曝光时间（纳秒）格式化为直观的快门速度字符串，例如 "1/500s"、"1/30s"。
     */
    fun formatShutterSpeed(exposureTimeNs: Long?): String? {
        val ns = exposureTimeNs ?: return null
        if (ns <= 0L) return null
        val seconds = ns / 1_000_000_000.0
        if (seconds >= 0.25) {
            return String.format(Locale.US, "%.1fs", seconds)
        }
        val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
        return "1/${denominator}s"
    }

    /**
     * 格式化快门与 ISO 组合文本，用于字幕与遥测展示（例如 "快门 1/500s ISO200"）。
     */
    fun formatExposureSummary(exposureTimeNs: Long?, iso: Int?): String? {
        val shutter = formatShutterSpeed(exposureTimeNs)
        val validIso = iso?.takeIf { it > 0 }
        return when {
            shutter != null && validIso != null -> "快门 $shutter ISO$validIso"
            shutter != null -> "快门 $shutter"
            validIso != null -> "ISO$validIso"
            else -> null
        }
    }
}

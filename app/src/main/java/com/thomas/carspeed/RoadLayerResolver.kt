package com.thomas.carspeed

import kotlin.math.abs

/**
 * 用手机俯仰角判断车在高架还是地面。
 *
 * 平路姿态稳住 [RoadLayerRules.BASELINE_HOLD_MS] 后作为基准。相对基准持续倾斜
 * [RoadLayerRules.SLOPE_HOLD_MS]，再回到基准并稳住 [RoadLayerRules.RELEVEL_HOLD_MS]，
 * 才算完成一次匝道：抬头是上到高架，低头是下到桥下。下坡之后定位持续变弱，记为隧道。
 * 卫星数从正常突然掉到很少，也算定位变弱；高架上少星不会改成隧道。
 * 只有一层，已经在顶层时再抬头仍保持高架。坡没有重新变平，不切换。
 * 手机被移动后清空识别，预警回到配置的默认层级。
 * 重合路分开后如果能确定实际走的那条路，可以回头改写这段重叠的层级。
 */
internal class RoadLayerResolver {
    private var detectedLayer = RoadLayer.UNKNOWN
    private var correctedByDivergence = false
    private var phase = PitchPhase.COLLECTING_BASELINE
    private var lastSample: RoadAttitudeSample? = null
    private var baselinePitchDegrees: Float? = null
    private var lastSampleMs: Long? = null
    private var lastPitchMs: Long? = null

    private var stableSinceMs: Long? = null
    private var stableAnchor: Float? = null
    private var stableSum = 0.0
    private var stableCount = 0

    private var slopeSinceMs: Long? = null
    private var slopeSign = 0
    private var relevelSinceMs: Long? = null
    private var weakGpsSinceMs: Long? = null
    private var tunnelClearSinceMs: Long? = null

    fun update(sample: RoadAttitudeSample, config: RoadLayerConfig = RoadLayerConfig()): RoadLayerDecision {
        lastSample = sample
        val previousMs = lastSampleMs
        lastSampleMs = sample.timeMs
        if (sample.phoneMoved) {
            resetDetection()
            return decision(sample, config)
        }
        if (previousMs != null && sample.timeMs - previousMs > RoadLayerRules.MAX_SAMPLE_GAP_MS) {
            resetProgress()
        }
        noteGps(sample.timeMs, sample.gpsWeak)
        val pitch = sample.pitchDegrees
        if (pitch == null || !sample.forwardKnown) return decision(sample, config)

        when (phase) {
            PitchPhase.COLLECTING_BASELINE -> collectBaseline(sample.timeMs, pitch)
            PitchPhase.FLAT -> trackFlat(sample.timeMs, pitch, sample.gpsWeak, config.pitchRecognitionEnabled)
            PitchPhase.ON_SLOPE -> trackSlope(sample.timeMs, pitch)
            PitchPhase.CONFIRMING_LEVEL -> confirmLevel(sample.timeMs, pitch, config.pitchRecognitionEnabled)
        }
        lastPitchMs = sample.timeMs
        return decision(sample, config)
    }

    fun applyCorrection(layer: RoadLayer, config: RoadLayerConfig = RoadLayerConfig()): RoadLayerDecision {
        if (layer == RoadLayer.ELEVATED || layer == RoadLayer.GROUND) {
            detectedLayer = layer
            correctedByDivergence = true
        }
        val sample = lastSample ?: RoadAttitudeSample(timeMs = 0L, pitchDegrees = null, forwardKnown = false)
        return decision(sample, config)
    }

    private fun collectBaseline(timeMs: Long, pitch: Float) {
        if (!noteStable(timeMs, pitch)) return
        baselinePitchDegrees = (stableSum / stableCount).toFloat()
        phase = PitchPhase.FLAT
        clearSlope()
        relevelSinceMs = null
    }

    private fun trackFlat(timeMs: Long, pitch: Float, gpsWeak: Boolean, pitchRecognitionEnabled: Boolean) {
        if (pitchRecognitionEnabled) updateTunnel(timeMs, gpsWeak)
        val baseline = baselinePitchDegrees ?: return
        val offset = pitch - baseline
        if (abs(offset) <= RoadLayerRules.FLAT_BAND_DEGREES) {
            val dt = ((timeMs - (lastPitchMs ?: timeMs)).coerceAtLeast(0L)).toFloat()
            val alpha = (dt / BASELINE_TRACK_MS).coerceIn(0f, 1f)
            baselinePitchDegrees = baseline + (pitch - baseline) * alpha
            clearSlope()
            return
        }
        if (abs(offset) < RoadLayerRules.SLOPE_ENTER_DEGREES) {
            clearSlope()
            return
        }
        if (noteSlope(timeMs, offset)) {
            phase = PitchPhase.ON_SLOPE
            relevelSinceMs = null
        }
    }

    private fun trackSlope(timeMs: Long, pitch: Float) {
        val baseline = baselinePitchDegrees ?: return
        val offset = pitch - baseline
        if (abs(offset) <= RoadLayerRules.FLAT_BAND_DEGREES) {
            phase = PitchPhase.CONFIRMING_LEVEL
            relevelSinceMs = timeMs
            return
        }
        if (abs(offset) >= RoadLayerRules.SLOPE_ENTER_DEGREES) {
            noteSlope(timeMs, offset)
        }
    }

    private fun confirmLevel(timeMs: Long, pitch: Float, pitchRecognitionEnabled: Boolean) {
        val baseline = baselinePitchDegrees ?: return
        val offset = pitch - baseline
        if (abs(offset) >= RoadLayerRules.SLOPE_ENTER_DEGREES) {
            phase = PitchPhase.ON_SLOPE
            relevelSinceMs = null
            noteSlope(timeMs, offset)
            return
        }
        if (abs(offset) > RoadLayerRules.FLAT_BAND_DEGREES) {
            relevelSinceMs = null
            return
        }
        val since = relevelSinceMs ?: timeMs.also { relevelSinceMs = it }
        if (timeMs - since < RoadLayerRules.RELEVEL_HOLD_MS) return
        if (pitchRecognitionEnabled) {
            detectedLayer = when {
                slopeSign > 0 -> RoadLayer.ELEVATED
                weakGpsHeld(timeMs) -> RoadLayer.TUNNEL
                else -> RoadLayer.GROUND
            }
            correctedByDivergence = false
        }
        baselinePitchDegrees = pitch
        phase = PitchPhase.FLAT
        clearSlope()
        relevelSinceMs = null
        clearStable()
    }

    private fun noteGps(timeMs: Long, gpsWeak: Boolean) {
        if (!gpsWeak) {
            weakGpsSinceMs = null
            return
        }
        if (weakGpsSinceMs == null) weakGpsSinceMs = timeMs
    }

    private fun weakGpsHeld(timeMs: Long): Boolean {
        val since = weakGpsSinceMs ?: return false
        return timeMs - since >= RoadLayerRules.TUNNEL_ENTER_MS
    }

    private fun updateTunnel(timeMs: Long, gpsWeak: Boolean) {
        if (detectedLayer == RoadLayer.GROUND && weakGpsHeld(timeMs)) {
            detectedLayer = RoadLayer.TUNNEL
            tunnelClearSinceMs = null
            return
        }
        if (detectedLayer != RoadLayer.TUNNEL) {
            tunnelClearSinceMs = null
            return
        }
        if (gpsWeak) {
            tunnelClearSinceMs = null
            return
        }
        val since = tunnelClearSinceMs ?: timeMs.also { tunnelClearSinceMs = it }
        if (timeMs - since >= RoadLayerRules.RELEVEL_HOLD_MS) {
            detectedLayer = RoadLayer.GROUND
            tunnelClearSinceMs = null
        }
    }

    private fun noteStable(timeMs: Long, pitch: Float): Boolean {
        val anchor = stableAnchor
        if (anchor == null || abs(pitch - anchor) > RoadLayerRules.FLAT_BAND_DEGREES) {
            stableAnchor = pitch
            stableSinceMs = timeMs
            stableSum = pitch.toDouble()
            stableCount = 1
            return false
        }
        stableSum += pitch
        stableCount += 1
        val since = stableSinceMs ?: timeMs
        return timeMs - since >= RoadLayerRules.BASELINE_HOLD_MS
    }

    private fun noteSlope(timeMs: Long, offset: Float): Boolean {
        val sign = if (offset >= 0f) 1 else -1
        if (slopeSign != sign || slopeSinceMs == null) {
            slopeSign = sign
            slopeSinceMs = timeMs
            return false
        }
        return timeMs - slopeSinceMs!! >= RoadLayerRules.SLOPE_HOLD_MS
    }

    private fun decision(sample: RoadAttitudeSample, config: RoadLayerConfig): RoadLayerDecision {
        if (!config.pitchRecognitionEnabled && !correctedByDivergence) {
            detectedLayer = RoadLayer.UNKNOWN
        }
        val alertLayer = if (detectedLayer == RoadLayer.UNKNOWN) config.defaultLayer else detectedLayer
        return RoadLayerDecision(
            detectedLayer = detectedLayer,
            alertLayer = alertLayer,
            usingDefault = detectedLayer == RoadLayer.UNKNOWN,
            phase = phase,
            baselinePitchDegrees = baselinePitchDegrees,
            pitchDegrees = sample.pitchDegrees,
            correctedByDivergence = correctedByDivergence
        )
    }

    private fun resetDetection() {
        detectedLayer = RoadLayer.UNKNOWN
        correctedByDivergence = false
        weakGpsSinceMs = null
        resetProgress()
    }

    private fun resetProgress() {
        phase = PitchPhase.COLLECTING_BASELINE
        baselinePitchDegrees = null
        lastPitchMs = null
        clearStable()
        clearSlope()
        relevelSinceMs = null
        tunnelClearSinceMs = null
    }

    private fun clearStable() {
        stableSinceMs = null
        stableAnchor = null
        stableSum = 0.0
        stableCount = 0
    }

    private fun clearSlope() {
        slopeSinceMs = null
        slopeSign = 0
    }
}

internal object RoadLayerRules {
    const val BASELINE_HOLD_MS = 30_000L
    const val SLOPE_HOLD_MS = 8_000L
    const val RELEVEL_HOLD_MS = 15_000L
    const val FLAT_BAND_DEGREES = 0.8f
    const val SLOPE_ENTER_DEGREES = 1.5f
    const val MAX_SAMPLE_GAP_MS = 2_000L
    const val TUNNEL_ENTER_MS = 3_000L
    const val WEAK_GPS_ACCURACY_METERS = 40f
}

private const val BASELINE_TRACK_MS = 60_000f

internal enum class RoadLayer {
    UNKNOWN,
    GROUND,
    ELEVATED,
    TUNNEL
}

internal object RoadLayerLabels {
    const val ON_BRIDGE = "桥上"
    const val UNDER_BRIDGE = "桥下"
    const val TUNNEL = "隧道"
    val TOKENS = setOf(ON_BRIDGE, UNDER_BRIDGE, TUNNEL)

    fun subtitle(decision: RoadLayerDecision, amapOverlap: Boolean): String? {
        if (decision.detectedLayer == RoadLayer.UNKNOWN && !amapOverlap) return null
        return when (decision.alertLayer) {
            RoadLayer.ELEVATED -> ON_BRIDGE
            RoadLayer.GROUND -> UNDER_BRIDGE
            RoadLayer.TUNNEL -> TUNNEL
            RoadLayer.UNKNOWN -> null
        }
    }
}

internal enum class PitchPhase {
    COLLECTING_BASELINE,
    FLAT,
    ON_SLOPE,
    CONFIRMING_LEVEL
}

internal data class RoadLayerConfig(
    val unknownDefaultsToElevated: Boolean = true,
    val divergenceCorrectionEnabled: Boolean = true,
    val pitchRecognitionEnabled: Boolean = true,
    val satelliteTunnelEnabled: Boolean = true
) {
    val defaultLayer: RoadLayer
        get() = if (unknownDefaultsToElevated) RoadLayer.ELEVATED else RoadLayer.GROUND
}

internal data class RoadAttitudeSample(
    val timeMs: Long,
    val pitchDegrees: Float?,
    val phoneMoved: Boolean = false,
    val forwardKnown: Boolean = true,
    val gpsWeak: Boolean = false
)

internal data class RoadLayerDecision(
    val detectedLayer: RoadLayer,
    val alertLayer: RoadLayer,
    val usingDefault: Boolean,
    val phase: PitchPhase,
    val baselinePitchDegrees: Float?,
    val pitchDegrees: Float?,
    val correctedByDivergence: Boolean = false
)

/**
 * 高德平行路回调里，高架标志为 0 无高架、1 在高架上、2 在高架下。
 * [SWITCH_TYPE_ELEVATED] 是 [com.amap.api.navi.AMapNavi.switchParallelRoad] 的高架上下切换。
 */
internal object RoadLayerAlertGate {
    const val ELEVATED_NONE = 0
    const val ELEVATED_ON = 1
    const val ELEVATED_UNDER = 2
    const val SWITCH_TYPE_ELEVATED = 2
    const val SWITCH_WAIT_MS = 8_000L
    const val SWITCH_COOLDOWN_MS = 20_000L

    fun needsSwitch(alertElevated: Boolean, elevatedFlag: Int): Boolean {
        return when (elevatedFlag) {
            ELEVATED_ON -> !alertElevated
            ELEVATED_UNDER -> alertElevated
            else -> false
        }
    }

    fun mode(
        alertElevated: Boolean,
        elevatedFlag: Int,
        pendingSwitchSinceMs: Long,
        nowMs: Long
    ): OverlapAlertMode {
        if (elevatedFlag == ELEVATED_NONE) return OverlapAlertMode.NORMAL
        if (!needsSwitch(alertElevated, elevatedFlag)) return OverlapAlertMode.LAYER
        if (pendingSwitchSinceMs <= 0L || nowMs - pendingSwitchSinceMs < SWITCH_WAIT_MS) {
            return OverlapAlertMode.SUPPRESS
        }
        return OverlapAlertMode.LAYER
    }
}

internal enum class OverlapAlertMode {
    NORMAL,
    LAYER,
    SUPPRESS
}

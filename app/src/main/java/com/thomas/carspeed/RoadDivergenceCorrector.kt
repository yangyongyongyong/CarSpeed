package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 高架和地面重合一段后再分开时，用分开之后的走向回头核对刚才的层级。
 *
 * 两条路在重合时航向一样，分叉之后一条转弯、另一条直行，定位跟着哪条走，
 * 整段重合就属于哪条。这段时间车身必须一直是平的：上下坡会换层，不能把坡前的标定改掉。
 * 巡航接口没有两条路的坐标时，改用高架匹配自己稳定落到的那一层；本机主动切换平行路不算证据。
 */
internal object RoadDivergenceCorrector {
    fun matchLayer(
        enabled: Boolean,
        pitchFlat: Boolean,
        gpsBearings: List<Float>,
        paths: List<RoadBearingPath>
    ): RoadLayer? {
        if (!enabled || !pitchFlat) return null
        if (gpsBearings.size < RoadDivergenceRules.MIN_SAMPLES) return null
        val usable = paths.filter { path ->
            (path.layer == RoadLayer.ELEVATED || path.layer == RoadLayer.GROUND) &&
                path.bearings.size >= RoadDivergenceRules.MIN_SAMPLES
        }
        if (usable.size < 2) return null
        val scored = usable
            .map { path -> path.layer to tailError(gpsBearings, path.bearings) }
            .sortedBy { it.second }
        val best = scored[0]
        val second = scored[1]
        if (best.second > RoadDivergenceRules.MAX_MATCH_ERROR_DEGREES) return null
        if (second.second - best.second < RoadDivergenceRules.MATCH_MARGIN_DEGREES) return null
        return best.first
    }

    private fun tailError(gps: List<Float>, road: List<Float>): Float {
        val count = minOf(gps.size, road.size)
        if (count < RoadDivergenceRules.MIN_TAIL_SAMPLES) return 180f
        val gpsTail = gps.takeLast(count)
        val roadTail = road.takeLast(count)
        val from = splitIndex(gpsTail)
        val compared = count - from
        if (compared < RoadDivergenceRules.MIN_TAIL_SAMPLES) return 180f
        var sum = 0.0
        for (index in from until count) {
            sum += headingDelta(gpsTail[index], roadTail[index])
        }
        return (sum / compared).toFloat()
    }

    private fun splitIndex(bearings: List<Float>): Int {
        val earliestTail = (bearings.size - RoadDivergenceRules.MIN_TAIL_SAMPLES).coerceAtLeast(0)
        val earlyCount = (bearings.size / 3).coerceAtLeast(RoadDivergenceRules.MIN_TAIL_SAMPLES)
        if (earlyCount > earliestTail) return earliestTail
        val early = circularMean(bearings.take(earlyCount))
        for (index in earlyCount until bearings.size) {
            if (headingDelta(bearings[index], early) >= RoadDivergenceRules.TURN_DEGREES) return index
        }
        return (bearings.size * 2 / 3).coerceAtMost(earliestTail)
    }

    private fun circularMean(degrees: List<Float>): Float {
        if (degrees.isEmpty()) return 0f
        var east = 0.0
        var north = 0.0
        for (degree in degrees) {
            val radians = Math.toRadians(degree.toDouble())
            east += sin(radians)
            north += cos(radians)
        }
        if (east == 0.0 && north == 0.0) return 0f
        val mean = Math.toDegrees(atan2(east, north)).toFloat()
        return if (mean < 0f) mean + 360f else mean
    }

    private fun headingDelta(first: Float, second: Float): Float {
        var delta = (first - second) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return abs(delta)
    }
}

internal object RoadDivergenceRules {
    const val MIN_SAMPLES = 6
    const val MIN_TAIL_SAMPLES = 3
    const val TURN_DEGREES = 25f
    const val MATCH_MARGIN_DEGREES = 12f
    const val MAX_MATCH_ERROR_DEGREES = 20f
    const val MAX_BEARING_GAP_MS = 5_000L
    const val MAX_BEARINGS = 1_200
    const val MIN_FLAG_SAMPLES = 3
    const val FLAG_SESSION_MIN_MS = 8_000L
    const val OWN_SWITCH_IGNORE_MS = 8_000L
}

internal data class RoadBearingPath(
    val layer: RoadLayer,
    val bearings: List<Float>
)

internal data class DivergenceCorrection(
    val layer: RoadLayer,
    val fromTimeMs: Long
)

internal data class DivergenceSample(
    val timeMs: Long,
    val bearingDegrees: Float?,
    val pitchFlat: Boolean,
    val assumedLayer: RoadLayer,
    val provisional: Boolean,
    val elevatedFlag: Int,
    val enabled: Boolean,
    val paths: List<RoadBearingPath> = emptyList()
)

internal class RoadDivergenceTracker {
    private val bearings = ArrayList<Float>()
    private var sessionStartMs: Long? = null
    private var lastBearingMs: Long? = null
    private var assumedLayer = RoadLayer.UNKNOWN
    private var sessionProvisional = true
    private var corrected = false
    private var ownSwitchAtMs = 0L
    private var originFlag = RoadLayerAlertGate.ELEVATED_NONE
    private var originFlagSamples = 0
    private var originEstablished = false
    private var currentFlag = RoadLayerAlertGate.ELEVATED_NONE
    private var currentFlagSamples = 0
    private var lastPaths: List<RoadBearingPath> = emptyList()

    fun noteOwnSwitch(timeMs: Long) {
        ownSwitchAtMs = timeMs
    }

    fun reset() {
        bearings.clear()
        sessionStartMs = null
        lastBearingMs = null
        assumedLayer = RoadLayer.UNKNOWN
        sessionProvisional = true
        corrected = false
        originFlag = RoadLayerAlertGate.ELEVATED_NONE
        originFlagSamples = 0
        originEstablished = false
        currentFlag = RoadLayerAlertGate.ELEVATED_NONE
        currentFlagSamples = 0
        lastPaths = emptyList()
    }

    fun clear() {
        reset()
        ownSwitchAtMs = 0L
    }

    fun update(sample: DivergenceSample): DivergenceCorrection? {
        if (!sample.enabled) {
            reset()
            return null
        }
        if (!sample.pitchFlat) {
            reset()
            return null
        }
        val overlap = sample.elevatedFlag == RoadLayerAlertGate.ELEVATED_ON ||
            sample.elevatedFlag == RoadLayerAlertGate.ELEVATED_UNDER
        if (!overlap) {
            val correction = conclude(sample.timeMs, sample.paths.ifEmpty { lastPaths })
            reset()
            return correction
        }
        if (sample.paths.isNotEmpty()) lastPaths = sample.paths
        val previousBearingMs = lastBearingMs
        if (previousBearingMs != null && sample.timeMs - previousBearingMs > RoadDivergenceRules.MAX_BEARING_GAP_MS) {
            reset()
        }
        if (sample.bearingDegrees != null && sample.timeMs != lastBearingMs) {
            if (sessionStartMs == null) {
                sessionStartMs = sample.timeMs
                assumedLayer = sample.assumedLayer
                sessionProvisional = sample.provisional
            }
            bearings.add(sample.bearingDegrees)
            if (bearings.size > RoadDivergenceRules.MAX_BEARINGS) bearings.removeAt(0)
            lastBearingMs = sample.timeMs
        }
        noteFlag(sample.elevatedFlag, sample.timeMs)
        if (corrected) return null
        return conclude(sample.timeMs, lastPaths)
    }

    private fun noteFlag(flag: Int, timeMs: Long) {
        if (flag != RoadLayerAlertGate.ELEVATED_ON && flag != RoadLayerAlertGate.ELEVATED_UNDER) return
        if (causedByUs(timeMs)) {
            originFlag = flag
            originFlagSamples = RoadDivergenceRules.MIN_FLAG_SAMPLES
            originEstablished = true
            currentFlag = flag
            currentFlagSamples = RoadDivergenceRules.MIN_FLAG_SAMPLES
            return
        }
        if (!originEstablished) {
            if (flag == originFlag && originFlagSamples > 0) {
                originFlagSamples += 1
            } else {
                originFlag = flag
                originFlagSamples = 1
            }
            currentFlag = originFlag
            currentFlagSamples = originFlagSamples
            if (originFlagSamples >= RoadDivergenceRules.MIN_FLAG_SAMPLES) originEstablished = true
            return
        }
        if (flag == currentFlag) {
            currentFlagSamples += 1
        } else {
            currentFlag = flag
            currentFlagSamples = 1
        }
    }

    private fun causedByUs(timeMs: Long): Boolean {
        return ownSwitchAtMs != 0L &&
            timeMs >= ownSwitchAtMs &&
            timeMs - ownSwitchAtMs <= RoadDivergenceRules.OWN_SWITCH_IGNORE_MS
    }

    private fun conclude(timeMs: Long, paths: List<RoadBearingPath>): DivergenceCorrection? {
        if (corrected) return null
        val start = sessionStartMs ?: return null
        if (bearings.size < RoadDivergenceRules.MIN_SAMPLES) return null
        val matched = RoadDivergenceCorrector.matchLayer(
            enabled = true,
            pitchFlat = true,
            gpsBearings = bearings,
            paths = paths
        )
        val flagged = if (timeMs - start >= RoadDivergenceRules.FLAG_SESSION_MIN_MS) flagLayer() else null
        val layer = matched ?: flagged ?: return null
        if (assumedLayer == RoadLayer.TUNNEL && layer == RoadLayer.GROUND) return null
        if (layer == assumedLayer && !sessionProvisional) return null
        corrected = true
        return DivergenceCorrection(layer = layer, fromTimeMs = start)
    }

    private fun flagLayer(): RoadLayer? {
        if (!originEstablished) return null
        if (currentFlagSamples < RoadDivergenceRules.MIN_FLAG_SAMPLES) return null
        if (currentFlag == originFlag) return null
        return when (currentFlag) {
            RoadLayerAlertGate.ELEVATED_ON -> RoadLayer.ELEVATED
            RoadLayerAlertGate.ELEVATED_UNDER -> RoadLayer.GROUND
            else -> null
        }
    }
}

internal object SrtRoadLayerRewrite {
    private val timeRegex = Regex(
        "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s+-->\\s+(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})"
    )

    fun rewrite(text: String, fromElapsedMs: Long, label: String): String {
        if (label.isBlank() || text.isEmpty()) return text
        val normalized = text.replace("\r\n", "\n")
        val endsWithNewline = normalized.endsWith("\n")
        val blocks = normalized.split(Regex("\n{2,}"))
        val rewritten = blocks.map { block ->
            if (block.isBlank()) block else rewriteBlock(block, fromElapsedMs, label)
        }
        var joined = rewritten.joinToString("\n\n")
        if (endsWithNewline && !joined.endsWith("\n")) joined += "\n"
        return joined
    }

    fun replaceLayerToken(line: String, label: String): String {
        val parts = line.split("  |  ")
        if (parts.none { part -> layerTokenIn(part) != null }) return line
        return parts.joinToString("  |  ") { part ->
            val token = layerTokenIn(part) ?: return@joinToString part
            when {
                label == RoadLayerLabels.UNDER_BRIDGE && token == RoadLayerLabels.TUNNEL -> part
                part.trim() == token -> label
                else -> part.trimEnd().removeSuffix(token).trimEnd() + " " + label
            }
        }
    }

    private fun layerTokenIn(part: String): String? {
        val token = part.trim()
        if (token in RoadLayerLabels.TOKENS) return token
        return RoadLayerLabels.TOKENS.firstOrNull { token.endsWith(" $it") }
    }

    private fun rewriteBlock(block: String, fromElapsedMs: Long, label: String): String {
        val lines = block.lines()
        val timeLine = lines.firstOrNull { it.contains("-->") } ?: return block
        val match = timeRegex.find(timeLine) ?: return block
        val startMs = parseTime(match.groupValues, 1)
        if (startMs < fromElapsedMs) return block
        return lines.joinToString("\n") { line ->
            if (line.contains("-->")) line else replaceLayerToken(line, label)
        }
    }

    private fun parseTime(values: List<String>, offset: Int): Long {
        val hours = values[offset].toLongOrNull() ?: 0L
        val minutes = values[offset + 1].toLongOrNull() ?: 0L
        val seconds = values[offset + 2].toLongOrNull() ?: 0L
        val millis = values[offset + 3].toLongOrNull() ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }
}

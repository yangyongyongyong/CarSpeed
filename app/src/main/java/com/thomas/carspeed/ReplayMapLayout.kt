package com.thomas.carspeed

import kotlin.math.roundToInt

internal enum class FloatEdge { LEFT, TOP, RIGHT, BOTTOM }

internal data class ReplayFloatSpec(
    val leftFraction: Float,
    val topFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float
)

internal data class ReplayFloatFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/** 悬浮窗地方小：藏掉速度、里程和轨迹滑块，离线按钮也可以不放，剩下的操作钮改成贴合文字的小尺寸。 */
internal data class ReplayMapChrome(
    val showTrackText: Boolean,
    val showSpeedCaption: Boolean,
    val showTrackSlider: Boolean,
    val showOfflineButton: Boolean,
    val showLayoutButton: Boolean,
    val showScaleControls: Boolean,
    val compactActions: Boolean
)

/**
 * 录像回放的两种地图摆法。默认上下分区；悬浮时地图盖在画面上，位置和大小记成相对比例。
 */
internal object ReplayMapLayout {
    const val MODE_SPLIT = "split"
    const val MODE_FLOAT = "float"
    const val FLOAT_DRAG_HOLD_MS = 1_000L
    const val FLOAT_EDGE_DP = 24
    const val PREF_LAYOUT_MODE = "video_replay_layout_mode"
    const val PREF_TRACK_ALPHA = "video_replay_track_alpha_percent"
    const val PREF_FLOAT_LEFT = "video_replay_float_left"
    const val PREF_FLOAT_TOP = "video_replay_float_top"
    const val PREF_FLOAT_WIDTH = "video_replay_float_width"
    const val PREF_FLOAT_HEIGHT = "video_replay_float_height"

    fun normalizeMode(stored: String?): String {
        return if (stored == MODE_FLOAT) MODE_FLOAT else MODE_SPLIT
    }

    fun trackAlphaPercent(value: Int): Int = value.coerceIn(0, 100)

    fun chrome(mode: String, mapFullscreen: Boolean, trackOnly: Boolean): ReplayMapChrome {
        val floating = mode == MODE_FLOAT && !trackOnly && !mapFullscreen
        return ReplayMapChrome(
            showTrackText = !floating && !mapFullscreen,
            showSpeedCaption = !floating,
            showTrackSlider = !floating && !mapFullscreen,
            showOfflineButton = !floating,
            showLayoutButton = !floating && !mapFullscreen && !trackOnly,
            showScaleControls = !floating && !mapFullscreen,
            compactActions = floating
        )
    }

    fun defaultFloatSpec(): ReplayFloatSpec {
        return ReplayFloatSpec(
            leftFraction = 0.03f,
            topFraction = 0.06f,
            widthFraction = 0.46f,
            heightFraction = 0.36f
        )
    }

    fun frame(
        spec: ReplayFloatSpec,
        parentWidth: Int,
        parentHeight: Int,
        minWidth: Int,
        minHeight: Int
    ): ReplayFloatFrame {
        if (parentWidth <= 0 || parentHeight <= 0) return ReplayFloatFrame(0, 0, 0, 0)
        val minW = minWidth.coerceIn(1, parentWidth)
        val minH = minHeight.coerceIn(1, parentHeight)
        val width = (parentWidth * spec.widthFraction).roundToInt().coerceIn(minW, parentWidth)
        val height = (parentHeight * spec.heightFraction).roundToInt().coerceIn(minH, parentHeight)
        val left = (parentWidth * spec.leftFraction).roundToInt()
            .coerceIn(0, parentWidth - width)
        val top = (parentHeight * spec.topFraction).roundToInt()
            .coerceIn(0, parentHeight - height)
        return ReplayFloatFrame(left, top, width, height)
    }

    fun spec(frame: ReplayFloatFrame, parentWidth: Int, parentHeight: Int): ReplayFloatSpec {
        if (parentWidth <= 0 || parentHeight <= 0) return defaultFloatSpec()
        return ReplayFloatSpec(
            leftFraction = frame.left / parentWidth.toFloat(),
            topFraction = frame.top / parentHeight.toFloat(),
            widthFraction = frame.width / parentWidth.toFloat(),
            heightFraction = frame.height / parentHeight.toFloat()
        )
    }

    fun moved(
        frame: ReplayFloatFrame,
        dx: Int,
        dy: Int,
        parentWidth: Int,
        parentHeight: Int
    ): ReplayFloatFrame {
        val left = (frame.left + dx).coerceIn(0, (parentWidth - frame.width).coerceAtLeast(0))
        val top = (frame.top + dy).coerceIn(0, (parentHeight - frame.height).coerceAtLeast(0))
        return frame.copy(left = left, top = top)
    }

    fun edgesAt(x: Float, y: Float, width: Int, height: Int, edgePx: Int): Set<FloatEdge> {
        if (width <= 0 || height <= 0 || edgePx <= 0) return emptySet()
        val edges = mutableSetOf<FloatEdge>()
        val onLeft = x < edgePx
        val onRight = x > width - edgePx
        if (onLeft && onRight) {
            edges += if (x < width / 2f) FloatEdge.LEFT else FloatEdge.RIGHT
        } else if (onLeft) {
            edges += FloatEdge.LEFT
        } else if (onRight) {
            edges += FloatEdge.RIGHT
        }
        val onTop = y < edgePx
        val onBottom = y > height - edgePx
        if (onTop && onBottom) {
            edges += if (y < height / 2f) FloatEdge.TOP else FloatEdge.BOTTOM
        } else if (onTop) {
            edges += FloatEdge.TOP
        } else if (onBottom) {
            edges += FloatEdge.BOTTOM
        }
        return edges
    }

    /** 四条边各自拉动，宽和高互不影响，所以长宽比可以任意变。 */
    fun resizedEdges(
        frame: ReplayFloatFrame,
        dx: Int,
        dy: Int,
        edges: Set<FloatEdge>,
        parentWidth: Int,
        parentHeight: Int,
        minWidth: Int,
        minHeight: Int
    ): ReplayFloatFrame {
        val parentW = parentWidth.coerceAtLeast(1)
        val parentH = parentHeight.coerceAtLeast(1)
        val minW = minWidth.coerceIn(1, parentW)
        val minH = minHeight.coerceIn(1, parentH)
        val anchorRight = frame.left + frame.width
        val anchorBottom = frame.top + frame.height
        var left = frame.left
        var top = frame.top
        var right = anchorRight
        var bottom = anchorBottom
        if (FloatEdge.LEFT in edges) {
            val limit = (anchorRight - minW).coerceAtLeast(0)
            left = (frame.left + dx).coerceIn(0, limit)
        }
        if (FloatEdge.RIGHT in edges) {
            val start = (left + minW).coerceAtMost(parentW)
            right = (anchorRight + dx).coerceIn(start, parentW)
        }
        if (FloatEdge.TOP in edges) {
            val limit = (anchorBottom - minH).coerceAtLeast(0)
            top = (frame.top + dy).coerceIn(0, limit)
        }
        if (FloatEdge.BOTTOM in edges) {
            val start = (top + minH).coerceAtMost(parentH)
            bottom = (anchorBottom + dy).coerceIn(start, parentH)
        }
        return ReplayFloatFrame(
            left = left,
            top = top,
            width = (right - left).coerceAtLeast(1),
            height = (bottom - top).coerceAtLeast(1)
        )
    }

    fun resized(
        frame: ReplayFloatFrame,
        dw: Int,
        dh: Int,
        parentWidth: Int,
        parentHeight: Int,
        minWidth: Int,
        minHeight: Int
    ): ReplayFloatFrame {
        val maxWidth = (parentWidth - frame.left).coerceAtLeast(1)
        val maxHeight = (parentHeight - frame.top).coerceAtLeast(1)
        val minW = minWidth.coerceIn(1, maxWidth)
        val minH = minHeight.coerceIn(1, maxHeight)
        return frame.copy(
            width = (frame.width + dw).coerceIn(minW, maxWidth),
            height = (frame.height + dh).coerceIn(minH, maxHeight)
        )
    }
}

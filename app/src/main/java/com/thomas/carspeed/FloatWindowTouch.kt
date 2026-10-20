package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮窗中间按住超过 1 秒后进入拖动；贴着边缘则直接改那一条边的宽或高。
 */
internal class FloatWindowTouch(
    private val edgePx: Int,
    private val touchSlop: Int,
    private val holdMs: Long = ReplayMapLayout.FLOAT_DRAG_HOLD_MS
) {
    private enum class Mode { IDLE, PENDING, RESIZE, DRAG }

    private var mode = Mode.IDLE
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var edges: Set<FloatEdge> = emptySet()
    private var origin = ReplayFloatFrame(0, 0, 0, 0)
    private var dragEntered = false

    /** 地图中间按下：先不吃掉事件，满 1 秒再拖。 */
    fun downBody(rawX: Float, rawY: Float, frame: ReplayFloatFrame, timeMs: Long): Boolean {
        origin = frame
        downRawX = rawX
        downRawY = rawY
        downTime = timeMs
        edges = emptySet()
        mode = Mode.PENDING
        dragEntered = false
        return false
    }

    fun down(
        localX: Float,
        localY: Float,
        rawX: Float,
        rawY: Float,
        viewWidth: Int,
        viewHeight: Int,
        frame: ReplayFloatFrame,
        timeMs: Long
    ): Boolean {
        origin = frame
        downRawX = rawX
        downRawY = rawY
        downTime = timeMs
        edges = ReplayMapLayout.edgesAt(localX, localY, viewWidth, viewHeight, edgePx)
        mode = if (edges.isEmpty()) Mode.PENDING else Mode.RESIZE
        dragEntered = false
        return mode == Mode.RESIZE
    }

    fun move(
        rawX: Float,
        rawY: Float,
        timeMs: Long,
        parentWidth: Int,
        parentHeight: Int,
        minWidth: Int,
        minHeight: Int
    ): ReplayFloatFrame? {
        val dx = (rawX - downRawX).toInt()
        val dy = (rawY - downRawY).toInt()
        val moved = max(abs(rawX - downRawX), abs(rawY - downRawY))
        if (mode == Mode.PENDING) {
            val held = timeMs - downTime >= holdMs
            if (!held && moved > touchSlop) {
                mode = Mode.IDLE
                return null
            }
            if (!held) return null
            mode = Mode.DRAG
            dragEntered = true
        }
        return when (mode) {
            Mode.DRAG -> ReplayMapLayout.moved(origin, dx, dy, parentWidth, parentHeight)
            Mode.RESIZE -> ReplayMapLayout.resizedEdges(
                origin,
                dx,
                dy,
                edges,
                parentWidth,
                parentHeight,
                minWidth,
                minHeight
            )
            Mode.IDLE, Mode.PENDING -> null
        }
    }

    fun consuming(): Boolean = mode == Mode.RESIZE || mode == Mode.DRAG

    fun takeDragEntry(): Boolean {
        val entered = dragEntered
        dragEntered = false
        return entered
    }

    fun up(timeMs: Long): Boolean {
        val heldStill = mode == Mode.PENDING && timeMs - downTime >= holdMs
        val consumed = heldStill || consuming()
        mode = Mode.IDLE
        edges = emptySet()
        return consumed
    }
}

package com.thomas.carspeed

internal object RecordingBubblePlacement {
    fun clamp(
        x: Int,
        y: Int,
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Int, Int> {
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    fun defaultPosition(
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        marginPx: Int,
        topInsetPx: Int
    ): Pair<Int, Int> {
        return clamp(
            x = screenWidth - viewWidth - marginPx,
            y = topInsetPx + marginPx,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }
}

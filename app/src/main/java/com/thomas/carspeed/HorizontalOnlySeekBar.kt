package com.thomas.carspeed

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatSeekBar
import kotlin.math.abs
import kotlin.math.roundToInt

class HorizontalOnlySeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var externalListener: OnSeekBarChangeListener? = null
    private var downX = 0f
    private var downY = 0f
    private var downProgress = 0
    private var horizontalTracking = false
    private var verticalGesture = false

    override fun setOnSeekBarChangeListener(listener: OnSeekBarChangeListener?) {
        externalListener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downProgress = progress
                horizontalTracking = false
                verticalGesture = false
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (verticalGesture) return false
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (!horizontalTracking) {
                    if (dy > touchSlop && dy > dx) {
                        verticalGesture = true
                        progress = downProgress
                        isPressed = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    if (dx > touchSlop && dx >= dy) {
                        horizontalTracking = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        externalListener?.onStartTrackingTouch(this)
                    } else {
                        return true
                    }
                }
                updateProgressFromX(event.x, fromUser = true)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!verticalGesture) {
                    if (!horizontalTracking) {
                        externalListener?.onStartTrackingTouch(this)
                    }
                    updateProgressFromX(event.x, fromUser = true)
                    externalListener?.onStopTrackingTouch(this)
                }
                isPressed = false
                horizontalTracking = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (horizontalTracking) externalListener?.onStopTrackingTouch(this)
                isPressed = false
                horizontalTracking = false
                verticalGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateProgressFromX(x: Float, fromUser: Boolean) {
        val available = width - paddingLeft - paddingRight
        if (available <= 0) return
        val ratio = ((x - paddingLeft) / available.toFloat()).coerceIn(0f, 1f)
        val next = min + ((max - min) * ratio).roundToInt()
        if (progress != next) {
            progress = next
            externalListener?.onProgressChanged(this, next, fromUser)
        }
    }
}

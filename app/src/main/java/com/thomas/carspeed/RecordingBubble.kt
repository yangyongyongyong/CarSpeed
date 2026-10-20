package com.thomas.carspeed

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.util.TypedValue
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

// 录像开始后挂在导航上面。分段滚动不重建；录像结束或进程退出后窗口消失。
internal object RecordingBubble {
    private const val PREF_X = "recording_bubble_x"
    private const val PREF_Y = "recording_bubble_y"
    private const val PREF_SCALE_PERCENT = "recording_bubble_scale_percent"

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var label: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var elapsedMs: (() -> Long)? = null
    private var ticker: Runnable? = null
    private var scalePercent = RecordingBubbleScale.DEFAULT_PERCENT

    fun show(context: Context, elapsedMs: () -> Long) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMain(appContext, elapsedMs)
        } else {
            handler.post { showOnMain(appContext, elapsedMs) }
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            hideOnMain(appContext)
        } else {
            handler.post { hideOnMain(appContext) }
        }
    }

    private fun showOnMain(context: Context, elapsedMs: () -> Long) {
        if (view != null) {
            this.elapsedMs = elapsedMs
            publishElapsed()
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        val inflated = LayoutInflater.from(context).inflate(R.layout.overlay_recording_bubble, null)
        val text = inflated.findViewById<TextView>(R.id.tvRecordingElapsed)
        val dot = inflated.findViewById<View>(R.id.recordingBubbleDot)
        scalePercent = savedScalePercent(context)
        applyScale(inflated, text, dot, scalePercent)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            inflated.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val metrics = context.resources.displayMetrics
            val margin = (12f * metrics.density).toInt()
            val saved = savedPosition(context)
            val (startX, startY) = if (saved == null) {
                RecordingBubblePlacement.defaultPosition(
                    viewWidth = inflated.measuredWidth,
                    viewHeight = inflated.measuredHeight,
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels,
                    marginPx = margin,
                    topInsetPx = statusBarHeight(context)
                )
            } else {
                RecordingBubblePlacement.clamp(
                    x = saved.first,
                    y = saved.second,
                    viewWidth = inflated.measuredWidth,
                    viewHeight = inflated.measuredHeight,
                    screenWidth = metrics.widthPixels,
                    screenHeight = metrics.heightPixels
                )
            }
            x = startX
            y = startY
        }
        attachDrag(context, inflated, layoutParams, manager)
        try {
            manager.addView(inflated, layoutParams)
        } catch (_: RuntimeException) {
            return
        }
        view = inflated
        label = text
        params = layoutParams
        this.elapsedMs = elapsedMs
        publishElapsed()
        scheduleNextTick()
    }

    private fun hideOnMain(context: Context) {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        elapsedMs = null
        val shown = view
        view = null
        label = null
        params = null
        if (shown != null) {
            val manager = context.getSystemService(WindowManager::class.java)
            try {
                manager?.removeView(shown)
            } catch (_: RuntimeException) {
                // 窗口可能已经被系统拆掉。
            }
        }
    }

    private fun publishElapsed() {
        val text = label ?: return
        val elapsed = elapsedMs?.invoke() ?: 0L
        val formatted = RecordingBubbleClock.format(elapsed)
        text.text = formatted
        text.contentDescription = text.context.getString(R.string.recording_bubble_desc, formatted)
    }

    private fun scheduleNextTick() {
        val elapsed = elapsedMs?.invoke() ?: return
        val delay = (1_000L - (elapsed % 1_000L)).coerceIn(200L, 1_000L)
        val next = Runnable {
            if (view == null) return@Runnable
            publishElapsed()
            scheduleNextTick()
        }
        ticker = next
        handler.postDelayed(next, delay)
    }

    private fun attachDrag(
        context: Context,
        target: View,
        layoutParams: WindowManager.LayoutParams,
        manager: WindowManager
    ) {
        var downX = 0
        var downY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = layoutParams.x
                    downY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    val metrics = context.resources.displayMetrics
                    val (x, y) = RecordingBubblePlacement.clamp(
                        x = downX + dx,
                        y = downY + dy,
                        viewWidth = target.width,
                        viewHeight = target.height,
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels
                    )
                    layoutParams.x = x
                    layoutParams.y = y
                    try {
                        manager.updateViewLayout(target, layoutParams)
                    } catch (_: RuntimeException) {
                        return@setOnTouchListener false
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePosition(context, layoutParams.x, layoutParams.y)
                    } else {
                        // 点按换下一档，拖动只改位置。
                        cycleScale(context, target, manager)
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (moved) savePosition(context, layoutParams.x, layoutParams.y)
                    true
                }

                else -> false
            }
        }
    }

    private fun cycleScale(context: Context, target: View, manager: WindowManager) {
        val text = label ?: return
        val dot = target.findViewById<View>(R.id.recordingBubbleDot) ?: return
        scalePercent = RecordingBubbleScale.next(scalePercent)
        saveScalePercent(context, scalePercent)
        applyScale(target, text, dot, scalePercent)
        val layoutParams = params ?: return
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        try {
            manager.updateViewLayout(target, layoutParams)
        } catch (_: RuntimeException) {
            return
        }
        target.post { clampToScreen(context, manager) }
    }

    private fun clampToScreen(context: Context, manager: WindowManager) {
        val shown = view ?: return
        val layoutParams = params ?: return
        val metrics = context.resources.displayMetrics
        val (x, y) = RecordingBubblePlacement.clamp(
            x = layoutParams.x,
            y = layoutParams.y,
            viewWidth = shown.width,
            viewHeight = shown.height,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )
        if (x == layoutParams.x && y == layoutParams.y) return
        layoutParams.x = x
        layoutParams.y = y
        try {
            manager.updateViewLayout(shown, layoutParams)
        } catch (_: RuntimeException) {
            // 窗口已经不在了。
        }
    }

    private fun applyScale(root: View, text: TextView, dot: View, percent: Int) {
        val scale = percent / 100f
        val density = root.resources.displayMetrics.density
        fun dp(value: Float) = (value * scale * density).toInt()
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * scale)
        root.setPadding(dp(12f), dp(8f), dp(16f), dp(11f))
        dot.layoutParams = dot.layoutParams.apply {
            width = dp(14f).coerceAtLeast(1)
            height = dp(14f).coerceAtLeast(1)
        }
        (text.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = dp(10f)
    }

    private fun savedScalePercent(context: Context): Int {
        val stored = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_SCALE_PERCENT, RecordingBubbleScale.DEFAULT_PERCENT)
        return RecordingBubbleScale.coerce(stored)
    }

    private fun saveScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_SCALE_PERCENT, percent)
            .apply()
    }

    private fun savedPosition(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_X) || !prefs.contains(PREF_Y)) return null
        return prefs.getInt(PREF_X, 0) to prefs.getInt(PREF_Y, 0)
    }

    private fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_X, x)
            .putInt(PREF_Y, y)
            .apply()
    }

    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}

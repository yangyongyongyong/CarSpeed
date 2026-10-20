package com.thomas.carspeed

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.card.MaterialCardView

/**
 * 悬浮地图的边缘手势。贴边拖动改大小；按在按钮上仍交给按钮。
 */
class FloatMapCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {
    var edgePx: Int = 0
    var onEdgeTouch: ((MotionEvent) -> Boolean)? = null
    private var trackingEdge = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val edges = ReplayMapLayout.edgesAt(ev.x, ev.y, width, height, edgePx)
            trackingEdge = edges.isNotEmpty() && onEdgeTouch != null && !hitsButton(this, ev.x, ev.y)
        }
        if (trackingEdge) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!trackingEdge) return super.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        onEdgeTouch?.invoke(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            trackingEdge = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun hitsButton(group: ViewGroup, x: Float, y: Float): Boolean {
        for (index in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            val localX = x - child.left
            val localY = y - child.top
            if (localX < 0f || localY < 0f || localX >= child.width || localY >= child.height) continue
            if (child is Button) return true
            if (child is ViewGroup) return hitsButton(child, localX, localY)
            return false
        }
        return false
    }
}

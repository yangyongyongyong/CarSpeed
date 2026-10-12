package com.thomas.carspeed

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.roundToInt

class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {
    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (ratioWidth == width && ratioHeight == height) return
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0 || width <= 0 || height <= 0) {
            setMeasuredDimension(width, height)
            return
        }
        if (width < height * ratioWidth / ratioHeight) {
            setMeasuredDimension(width, (width.toFloat() * ratioHeight / ratioWidth).roundToInt())
        } else {
            setMeasuredDimension((height.toFloat() * ratioWidth / ratioHeight).roundToInt(), height)
        }
    }
}

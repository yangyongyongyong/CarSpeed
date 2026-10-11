package com.thomas.carspeed

import android.content.Context
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager

object CameraRotation {
    fun displayRotation(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull() ?: run {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.rotation
            }
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    fun rotationFromOrientationDegrees(degrees: Int): Int? {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return null
        return when (degrees) {
            in 45..134 -> Surface.ROTATION_270
            in 135..224 -> Surface.ROTATION_180
            in 225..314 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
    }

    fun label(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_90 -> "横屏"
            Surface.ROTATION_270 -> "横屏"
            Surface.ROTATION_180 -> "倒置竖屏"
            else -> "竖屏"
        }
    }
}

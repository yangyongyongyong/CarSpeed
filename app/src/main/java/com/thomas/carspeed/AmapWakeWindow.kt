package com.thomas.carspeed

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

// Android 15 起，仅有悬浮窗权限还不能从后台启动前台服务，窗口必须已经可见。
internal object AmapWakeWindow {
    private var anchor: View? = null

    fun show(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (anchor != null) return true
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(WindowManager::class.java) ?: return false
        val view = View(appContext)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        return try {
            manager.addView(view, params)
            anchor = view
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun hide(context: Context) {
        val view = anchor ?: return
        val manager = context.applicationContext.getSystemService(WindowManager::class.java)
        anchor = null
        try {
            manager?.removeView(view)
        } catch (_: RuntimeException) {
            // 窗口可能已经被系统拆掉。
        }
    }
}

package com.thomas.carspeed

import android.content.Context
import android.content.Intent

/**
 * 摄像头前台服务不能从纯后台拉起。规则决定要录像时，先亮一个透明页取得前台，
 * 开始录像后立刻关掉，用户留在原来的应用里。
 */
object BackgroundRecordingStart {
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (DashcamService.isRecording(appContext)) return
        // 未同意隐私政策前不自动开录（录像会用到高德定位/逆地理）。
        if (!PrivacyConsent.isAgreed(appContext)) return
        if (!OverlayController.hasCameraPermission(appContext)) return
        // Android 15 要求悬浮窗已经可见才允许从后台拉前台服务，所以先挂上透明窗口再启动悬浮窗服务。
        AmapWakeWindow.show(appContext)
        if (OverlayController.canStart(appContext) && !OverlayController.isRunning(appContext)) {
            runCatching { OverlayController.start(appContext) }
        }
        val intent = Intent(appContext, DashcamWakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val started = runCatching { appContext.startActivity(intent) }.isSuccess
        if (!started) {
            AmapWakeWindow.hide(appContext)
            AmapWatchNotifier.showManualStart(appContext)
        }
    }
}

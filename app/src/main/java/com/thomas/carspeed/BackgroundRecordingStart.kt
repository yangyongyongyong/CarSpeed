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
        if (!OverlayController.hasCameraPermission(appContext)) return
        if (OverlayController.canStart(appContext) && !OverlayController.isRunning(appContext)) {
            OverlayController.start(appContext)
        }
        AmapWakeWindow.show(appContext)
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

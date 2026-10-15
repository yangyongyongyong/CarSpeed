package com.thomas.carspeed

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class AmapWatchService : AccessibilityService() {
    private var lastWakeAtMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val settings = TriggerSettings.load(this)
        val now = SystemClock.elapsedRealtime()
        if (!AmapWatchRules.shouldWake(
                enabled = settings.startOnAmap,
                foregroundPackage = event.packageName?.toString(),
                recording = DashcamService.isRecording(this),
                nowMs = now,
                lastWakeAtMs = lastWakeAtMs
            )
        ) {
            return
        }
        if (!OverlayController.hasCameraPermission(this)) return
        lastWakeAtMs = now
        // 先放一个可见的悬浮窗，系统才允许接下来从后台拉起录像页面。
        AmapWakeWindow.show(this)
        val intent = Intent(this, DashcamWakeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val started = runCatching { startActivity(intent) }.isSuccess
        if (!started) {
            AmapWakeWindow.hide(this)
            AmapWatchNotifier.showManualStart(this)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AmapWakeWindow.hide(this)
        super.onDestroy()
    }
}

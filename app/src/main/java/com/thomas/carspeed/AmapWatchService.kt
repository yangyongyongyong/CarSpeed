package com.thomas.carspeed

import android.accessibilityservice.AccessibilityService
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
        lastWakeAtMs = now
        // 只把进程唤醒并跑已有的开始录像规则，不因为高德在前台就直接开录。
        AutoTriggerController.sync(this)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AmapWakeWindow.hide(this)
        super.onDestroy()
    }
}

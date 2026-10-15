package com.thomas.carspeed

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity

// 透明页只为取得“应用正在前台”的状态。摄像头前台服务必须在这时启动，随后立刻回到高德。
class DashcamWakeActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var startedAtMs = 0L

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        startedAtMs = SystemClock.elapsedRealtime()
        if (!OverlayController.hasCameraPermission(this)) {
            closeWake()
            return
        }
        runCatching { DashcamService.startRecording(this) }
        handler.post(waitForRecording)
    }

    private val waitForRecording = object : Runnable {
        override fun run() {
            val recording = DashcamService.isRecording(this@DashcamWakeActivity)
            val timedOut = SystemClock.elapsedRealtime() - startedAtMs > WAKE_TIMEOUT_MS
            if (!recording && !timedOut) {
                handler.postDelayed(this, 100L)
                return
            }
            if (!recording) AmapWatchNotifier.showManualStart(this@DashcamWakeActivity)
            closeWake()
        }
    }

    private fun closeWake() {
        handler.removeCallbacksAndMessages(null)
        AmapWakeWindow.hide(applicationContext)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val WAKE_TIMEOUT_MS = 2_500L
    }
}

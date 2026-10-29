package com.thomas.carspeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

// 断电是隐式广播，Android 8 起送不到清单里静态注册的接收器；由 DashcamService 在录像期间动态注册。
class PowerTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_DISCONNECTED) return
        handlePowerDisconnected(context)
    }

    companion object {
        fun handlePowerDisconnected(context: Context) {
            if (!DashcamService.isRecording(context)) return
            if (!TriggerSettings.load(context).stopOnPowerDisconnected) return
            Toast.makeText(context, R.string.trigger_power_disconnected_stop, Toast.LENGTH_SHORT).show()
            DashcamService.stopRecording(context)
        }
    }
}

package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils

object AmapWatchAccess {
    enum class Grant {
        ACCESSIBILITY,
        OVERLAY,
        BATTERY
    }

    fun serviceId(context: Context): String {
        return "${context.packageName}/${AmapWatchService::class.java.name}"
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val enabled = runCatching {
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!enabled) return false
        val services = runCatching {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        if (services.isNullOrBlank()) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(services)
        val mine = serviceId(context)
        while (splitter.hasNext()) {
            if (splitter.next().equals(mine, ignoreCase = true)) return true
        }
        return false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun missing(context: Context): Grant? {
        if (!isAccessibilityEnabled(context)) return Grant.ACCESSIBILITY
        if (!Settings.canDrawOverlays(context)) return Grant.OVERLAY
        if (!isIgnoringBatteryOptimizations(context)) return Grant.BATTERY
        return null
    }

    fun openNextGrant(context: Context): Boolean {
        val intent = when (missing(context)) {
            Grant.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Grant.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            Grant.BATTERY -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            null -> return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

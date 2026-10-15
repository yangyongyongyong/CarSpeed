package com.thomas.carspeed

object AmapWatchRules {
    const val COOLDOWN_MS = 20_000L

    val PACKAGES = setOf(
        "com.autonavi.minimap",
        "com.autonavi.amapauto"
    )

    fun shouldWake(
        enabled: Boolean,
        foregroundPackage: String?,
        recording: Boolean,
        nowMs: Long,
        lastWakeAtMs: Long,
        cooldownMs: Long = COOLDOWN_MS
    ): Boolean {
        if (!enabled || recording) return false
        if (foregroundPackage !in PACKAGES) return false
        return nowMs - lastWakeAtMs >= cooldownMs
    }
}

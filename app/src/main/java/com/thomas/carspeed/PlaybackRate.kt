package com.thomas.carspeed

/**
 * 有的手机播不到 8 倍、10 倍。先试请求的倍率，不行再降到播放器肯接受的档，
 * 差额用跳帧补上，预览和回放仍按选中的倍率往前走。
 */
internal object PlaybackRate {
    fun fallbackSpeeds(requested: Float): List<Float> {
        if (requested <= 1f) return listOf(1f)
        return listOf(requested, 8f, 4f, 2f, 1f)
            .distinct()
            .filter { it in 1f..requested + 0.01f }
    }

    fun extraSeekMs(elapsedWallMs: Long, requestedSpeed: Float, appliedSpeed: Float): Long {
        if (elapsedWallMs <= 0L) return 0L
        val gap = requestedSpeed - appliedSpeed
        if (gap <= 0.05f) return 0L
        return (elapsedWallMs * gap).toLong()
    }

    /** 播放器已经接近目标倍率时不再跳帧，避免把正在快放的画面反复打断。 */
    fun shouldCatchUp(requestedSpeed: Float, appliedSpeed: Float): Boolean {
        return requestedSpeed > appliedSpeed + 0.05f && appliedSpeed < requestedSpeed * 0.5f
    }
}

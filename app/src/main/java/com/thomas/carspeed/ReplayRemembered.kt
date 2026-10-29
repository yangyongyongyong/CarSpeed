package com.thomas.carspeed

import android.content.Context
import android.content.SharedPreferences

internal data class ReplayPreviewMemory(
    val rotationDegrees: Int,
    val satellite: Boolean,
    val wholeRoute: Boolean,
    val scaleIndex: Int
)

internal data class ReplayExportSettings(
    val speedIndex: Int,
    val fps: Int,
    val fpsCustomized: Boolean,
    val includeMap: Boolean,
    val fields: ShareSubtitleFields
)

/**
 * 历史回放的画面布局，以及导出参数。下次打开沿用上次的选择。
 * 某一段视频的起止时间不记，换一段录像时仍然导出整段。
 */
internal object ReplayRemembered {
    const val SCALE_INDEX_MAX = 11

    private const val KEY_ROTATION = "video_replay_rotation_degrees"
    private const val KEY_SATELLITE = "video_replay_map_satellite"
    private const val KEY_WHOLE_ROUTE = "video_replay_map_whole_route"
    private const val KEY_SCALE = "video_replay_map_scale_index"
    private const val KEY_SPEED = "video_replay_export_speed_index"
    private const val KEY_FPS = "video_replay_export_fps"
    private const val KEY_FPS_CUSTOM = "video_replay_export_fps_custom"
    private const val KEY_INCLUDE_MAP = "video_replay_export_include_map"

    fun preview(context: Context): ReplayPreviewMemory {
        val prefs = prefs(context)
        return sanitizePreview(
            rotationDegrees = prefs.getInt(KEY_ROTATION, 0),
            satellite = prefs.getBoolean(KEY_SATELLITE, false),
            wholeRoute = prefs.getBoolean(KEY_WHOLE_ROUTE, false),
            scaleIndex = prefs.getInt(KEY_SCALE, 6)
        )
    }

    fun savePreview(context: Context, memory: ReplayPreviewMemory) {
        val safe = sanitizePreview(memory.rotationDegrees, memory.satellite, memory.wholeRoute, memory.scaleIndex)
        prefs(context).edit()
            .putInt(KEY_ROTATION, safe.rotationDegrees)
            .putBoolean(KEY_SATELLITE, safe.satellite)
            .putBoolean(KEY_WHOLE_ROUTE, safe.wholeRoute)
            .putInt(KEY_SCALE, safe.scaleIndex)
            .apply()
    }

    fun exportSettings(context: Context): ReplayExportSettings {
        val prefs = prefs(context)
        val speedIndex = prefs.getInt(KEY_SPEED, 0)
        val speed = ExportFps.SPEEDS[speedIndex.coerceIn(0, ExportFps.SPEEDS.lastIndex)]
        return sanitizeExport(
            speedIndex = speedIndex,
            fps = prefs.getInt(KEY_FPS, ExportFps.defaultFor(speed)),
            fpsCustomized = prefs.getBoolean(KEY_FPS_CUSTOM, false),
            includeMap = prefs.getBoolean(KEY_INCLUDE_MAP, true),
            // 字幕开关只有设置页一份，回放和导出都读它，避免两处各改各的。
            fields = DashcamSettings.subtitleFields(context)
        )
    }

    fun saveExport(context: Context, settings: ReplayExportSettings) {
        val safe = sanitizeExport(
            settings.speedIndex,
            settings.fps,
            settings.fpsCustomized,
            settings.includeMap,
            settings.fields
        )
        prefs(context).edit()
            .putInt(KEY_SPEED, safe.speedIndex)
            .putInt(KEY_FPS, safe.fps)
            .putBoolean(KEY_FPS_CUSTOM, safe.fpsCustomized)
            .putBoolean(KEY_INCLUDE_MAP, safe.includeMap)
            .apply()
    }

    fun saveSpeed(context: Context, speedIndex: Int) {
        val current = exportSettings(context)
        val speed = ExportFps.SPEEDS[speedIndex.coerceIn(0, ExportFps.SPEEDS.lastIndex)]
        saveExport(
            context,
            current.copy(
                speedIndex = speedIndex,
                fps = if (current.fpsCustomized) current.fps else ExportFps.defaultFor(speed)
            )
        )
    }

    fun sanitizePreview(
        rotationDegrees: Int,
        satellite: Boolean,
        wholeRoute: Boolean,
        scaleIndex: Int
    ): ReplayPreviewMemory {
        return ReplayPreviewMemory(
            rotationDegrees = VideoReplayRotation.normalize(rotationDegrees),
            satellite = satellite,
            wholeRoute = wholeRoute,
            scaleIndex = scaleIndex.coerceIn(0, SCALE_INDEX_MAX)
        )
    }

    fun sanitizeExport(
        speedIndex: Int,
        fps: Int,
        fpsCustomized: Boolean,
        includeMap: Boolean,
        fields: ShareSubtitleFields
    ): ReplayExportSettings {
        val speed = speedIndex.coerceIn(0, ExportFps.SPEEDS.lastIndex)
        val chosenFps = if (fps in ExportFps.CHOICES) {
            fps
        } else {
            ExportFps.defaultFor(ExportFps.SPEEDS[speed])
        }
        return ReplayExportSettings(speed, chosenFps, fpsCustomized, includeMap, fields)
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
    }
}

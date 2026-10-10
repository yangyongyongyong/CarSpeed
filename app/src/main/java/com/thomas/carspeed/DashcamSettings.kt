package com.thomas.carspeed

import android.content.Context

data class WatermarkSettings(
    val showTime: Boolean,
    val showSpeed: Boolean,
    val showGps: Boolean,
    val showAddress: Boolean,
    val showVideoMode: Boolean,
    val recordAudio: Boolean,
    val voicePhotoEnabled: Boolean,
    val autoStartRecording: Boolean,
    val parkingStopEnabled: Boolean,
    val parkingStopMinutes: Int
)

object DashcamSettings {
    const val PREF_WATERMARK_TIME = "dashcam_watermark_time"
    const val PREF_WATERMARK_SPEED = "dashcam_watermark_speed"
    const val PREF_WATERMARK_GPS = "dashcam_watermark_gps"
    const val PREF_WATERMARK_ADDRESS = "dashcam_watermark_address"
    const val PREF_WATERMARK_VIDEO_MODE = "dashcam_watermark_video_mode"
    const val PREF_RECORD_AUDIO = "dashcam_record_audio"
    const val PREF_VOICE_PHOTO = "dashcam_voice_photo"
    const val PREF_AUTO_START_RECORDING = "dashcam_auto_start_recording"
    const val PREF_VIDEO_QUALITY = "dashcam_video_quality"
    const val PREF_VIDEO_WIDTH = "dashcam_video_width"
    const val PREF_VIDEO_HEIGHT = "dashcam_video_height"
    const val PREF_VIDEO_FPS = "dashcam_video_fps"
    const val PREF_LOOP_STORAGE_GB = "dashcam_loop_storage_gb"
    const val PREF_ZOOM_PERCENT = "dashcam_zoom_percent"
    const val PREF_SEGMENT_MINUTES = "dashcam_segment_minutes"
    const val PREF_PARKING_STOP_ENABLED = "dashcam_parking_stop_enabled"
    const val PREF_PARKING_STOP_MINUTES = "dashcam_parking_stop_minutes"

    const val DEFAULT_LOOP_STORAGE_GB = 10
    const val MIN_LOOP_STORAGE_GB = 1
    const val MAX_LOOP_STORAGE_GB = 100
    const val DEFAULT_ZOOM_PERCENT = 10
    const val DEFAULT_SEGMENT_MINUTES = 5
    const val MIN_SEGMENT_MINUTES = 1
    const val MAX_SEGMENT_MINUTES = 10
    const val DEFAULT_PARKING_STOP_MINUTES = 3
    const val MIN_PARKING_STOP_MINUTES = 1
    const val MAX_PARKING_STOP_MINUTES = 30

    fun load(context: Context): WatermarkSettings {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        return WatermarkSettings(
            showTime = prefs.getBoolean(PREF_WATERMARK_TIME, true),
            showSpeed = prefs.getBoolean(PREF_WATERMARK_SPEED, true),
            showGps = prefs.getBoolean(PREF_WATERMARK_GPS, true),
            showAddress = prefs.getBoolean(PREF_WATERMARK_ADDRESS, false),
            showVideoMode = prefs.getBoolean(PREF_WATERMARK_VIDEO_MODE, true),
            recordAudio = prefs.getBoolean(PREF_RECORD_AUDIO, false),
            voicePhotoEnabled = prefs.getBoolean(PREF_VOICE_PHOTO, false),
            autoStartRecording = prefs.getBoolean(PREF_AUTO_START_RECORDING, false),
            parkingStopEnabled = prefs.getBoolean(PREF_PARKING_STOP_ENABLED, false),
            parkingStopMinutes = prefs.getInt(PREF_PARKING_STOP_MINUTES, DEFAULT_PARKING_STOP_MINUTES)
                .coerceIn(MIN_PARKING_STOP_MINUTES, MAX_PARKING_STOP_MINUTES)
        )
    }

    fun segmentDurationMs(context: Context): Long {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val minutes = prefs.getInt(PREF_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES)
            .coerceIn(MIN_SEGMENT_MINUTES, MAX_SEGMENT_MINUTES)
        return minutes * 60_000L
    }
}

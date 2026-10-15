package com.thomas.carspeed

import android.content.Context

data class WatermarkSettings(
    val showTime: Boolean,
    val showSpeed: Boolean,
    val showStationaryWait: Boolean,
    val showGps: Boolean,
    val showSatellites: Boolean,
    val showAddress: Boolean,
    val showRoadLayer: Boolean,
    val showVideoMode: Boolean,
    val showFocus: Boolean,
    val plateRecognitionEnabled: Boolean,
    val recordAudio: Boolean,
    val voicePhotoEnabled: Boolean,
    val autoStartRecording: Boolean,
    val parkingStopEnabled: Boolean,
    val parkingStopMinutes: Int
)

object DashcamSettings {
    const val PREF_WATERMARK_TIME = "dashcam_watermark_time"
    const val PREF_WATERMARK_SPEED = "dashcam_watermark_speed"
    const val PREF_WATERMARK_STATIONARY_WAIT = "dashcam_watermark_stationary_wait"
    const val PREF_WATERMARK_GPS = "dashcam_watermark_gps"
    const val PREF_WATERMARK_SATELLITES = "dashcam_watermark_satellites"
    const val PREF_WATERMARK_ADDRESS = "dashcam_watermark_address"
    const val PREF_WATERMARK_ROAD_LAYER = "dashcam_watermark_road_layer"
    const val PREF_WATERMARK_VIDEO_MODE = "dashcam_watermark_video_mode"
    const val PREF_WATERMARK_FOCUS = "dashcam_watermark_focus"
    const val PREF_PLATE_RECOGNITION_ENABLED = "dashcam_plate_recognition_enabled"
    const val PREF_RECORD_AUDIO = "dashcam_record_audio"
    const val PREF_VOICE_PHOTO = "dashcam_voice_photo"
    const val PREF_AUTO_START_RECORDING = "dashcam_auto_start_recording"
    const val PREF_VIDEO_QUALITY = "dashcam_video_quality"
    const val PREF_VIDEO_WIDTH = "dashcam_video_width"
    const val PREF_VIDEO_HEIGHT = "dashcam_video_height"
    const val PREF_VIDEO_FPS = "dashcam_video_fps"
    const val PREF_HDR_MODE = "dashcam_hdr_mode"
    const val PREF_HDR_START_MINUTE = "dashcam_hdr_start_minute"
    const val PREF_HDR_END_MINUTE = "dashcam_hdr_end_minute"
    const val PREF_MANUAL_FOCUS_ENABLED = "dashcam_manual_focus_enabled"
    const val PREF_FOCUS_PERCENT = "dashcam_focus_percent"
    const val PREF_LOOP_STORAGE_GB = "dashcam_loop_storage_gb"
    const val PREF_SEGMENT_MINUTES = "dashcam_segment_minutes"
    const val PREF_PARKING_STOP_ENABLED = "dashcam_parking_stop_enabled"
    const val PREF_PARKING_STOP_MINUTES = "dashcam_parking_stop_minutes"

    const val DEFAULT_LOOP_STORAGE_GB = 10
    const val MIN_LOOP_STORAGE_GB = 1
    const val MAX_LOOP_STORAGE_GB = 100
    const val DEFAULT_FOCUS_PERCENT = 0
    const val DEFAULT_SEGMENT_MINUTES = 5
    const val MIN_SEGMENT_MINUTES = 1
    const val MAX_SEGMENT_MINUTES = 10
    const val DEFAULT_PARKING_STOP_MINUTES = 3
    const val MIN_PARKING_STOP_MINUTES = 1
    const val MAX_PARKING_STOP_MINUTES = 30

    const val HDR_MODE_OFF = 0
    const val HDR_MODE_ALWAYS = 1
    const val HDR_MODE_SCHEDULE = 2
    const val DEFAULT_HDR_START_MINUTE = 18 * 60
    const val DEFAULT_HDR_END_MINUTE = 6 * 60

    fun load(context: Context): WatermarkSettings {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        return WatermarkSettings(
            showTime = prefs.getBoolean(PREF_WATERMARK_TIME, true),
            showSpeed = prefs.getBoolean(PREF_WATERMARK_SPEED, true),
            showStationaryWait = prefs.getBoolean(PREF_WATERMARK_STATIONARY_WAIT, true),
            showGps = prefs.getBoolean(PREF_WATERMARK_GPS, true),
            showSatellites = prefs.getBoolean(PREF_WATERMARK_SATELLITES, true),
            showAddress = prefs.getBoolean(PREF_WATERMARK_ADDRESS, false),
            showRoadLayer = prefs.getBoolean(PREF_WATERMARK_ROAD_LAYER, false),
            showVideoMode = prefs.getBoolean(PREF_WATERMARK_VIDEO_MODE, true),
            showFocus = prefs.getBoolean(PREF_WATERMARK_FOCUS, false),
            plateRecognitionEnabled = prefs.getBoolean(PREF_PLATE_RECOGNITION_ENABLED, false),
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

data class HdrSettings(
    val mode: Int,
    val startMinute: Int,
    val endMinute: Int
)

object DashcamHdrSettings {
    fun load(context: Context): HdrSettings {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        return HdrSettings(
            mode = prefs.getInt(DashcamSettings.PREF_HDR_MODE, DashcamSettings.HDR_MODE_OFF)
                .coerceIn(DashcamSettings.HDR_MODE_OFF, DashcamSettings.HDR_MODE_SCHEDULE),
            startMinute = prefs.getInt(DashcamSettings.PREF_HDR_START_MINUTE, DashcamSettings.DEFAULT_HDR_START_MINUTE)
                .coerceIn(0, MINUTES_PER_DAY - 1),
            endMinute = prefs.getInt(DashcamSettings.PREF_HDR_END_MINUTE, DashcamSettings.DEFAULT_HDR_END_MINUTE)
                .coerceIn(0, MINUTES_PER_DAY - 1)
        )
    }

    fun save(context: Context, settings: HdrSettings) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(
                DashcamSettings.PREF_HDR_MODE,
                settings.mode.coerceIn(DashcamSettings.HDR_MODE_OFF, DashcamSettings.HDR_MODE_SCHEDULE)
            )
            .putInt(DashcamSettings.PREF_HDR_START_MINUTE, settings.startMinute.coerceIn(0, MINUTES_PER_DAY - 1))
            .putInt(DashcamSettings.PREF_HDR_END_MINUTE, settings.endMinute.coerceIn(0, MINUTES_PER_DAY - 1))
            .apply()
    }

    fun shouldEnableAt(settings: HdrSettings, minuteOfDay: Int): Boolean {
        return when (settings.mode) {
            DashcamSettings.HDR_MODE_ALWAYS -> true
            DashcamSettings.HDR_MODE_SCHEDULE -> isInSchedule(
                minuteOfDay = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1),
                startMinute = settings.startMinute,
                endMinute = settings.endMinute
            )
            else -> false
        }
    }

    fun shouldEnableNow(context: Context): Boolean {
        val now = java.util.Calendar.getInstance()
        val minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return shouldEnableAt(load(context), minuteOfDay)
    }

    fun formatMinute(minuteOfDay: Int): String {
        val fixed = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return String.format(java.util.Locale.US, "%02d:%02d", fixed / 60, fixed % 60)
    }

    private fun isInSchedule(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean {
        val start = startMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    private const val MINUTES_PER_DAY = 24 * 60
}

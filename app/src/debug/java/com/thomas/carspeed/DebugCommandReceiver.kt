package com.thomas.carspeed

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty().ifBlank { COMMAND_HELP }
        val result = try {
            handleCommand(context, intent, command)
        } catch (e: RuntimeException) {
            resultJson(command, ok = false).put("error", e.message ?: e.javaClass.simpleName)
        }
        writeDebugJson(context, "last-command.json", result)
        Log.i(TAG, result.toString())
        setResultCode(if (result.optBoolean("ok")) 0 else 1)
        setResultData(result.toString())
    }

    private fun handleCommand(context: Context, intent: Intent, command: String): JSONObject {
        return when (command) {
            COMMAND_HELP -> debugHelp(command)
            COMMAND_OPEN_MAIN -> openActivity(context, MainActivity::class.java, command)
            COMMAND_OPEN_CAMERA -> openActivity(context, CameraRecordActivity::class.java, command)
            COMMAND_OPEN_SETTINGS -> openActivity(context, DashcamSettingsActivity::class.java, command)
            COMMAND_OPEN_CAMERA_FULLSCREEN -> openActivity(context, CameraFullscreenActivity::class.java, command)
            COMMAND_OPEN_HISTORY -> openActivity(context, HistoryActivity::class.java, command)
            COMMAND_OPEN_LATEST_VIDEO -> openLatestVideo(context, command)
            COMMAND_OPEN_STATUS -> openActivity(context, StatusActivity::class.java, command)
            COMMAND_OPEN_HUD -> openActivity(context, SpeedHudActivity::class.java, command)
            COMMAND_BACKGROUND_APP -> backgroundApp(context, command)
            COMMAND_START_RECORDING -> runAction(command) { DashcamService.startRecording(context) }
            COMMAND_STOP_RECORDING -> runAction(command) { DashcamService.stopRecording(context) }
            COMMAND_TAKE_PHOTO -> runAction(command) { DashcamService.takePhoto(context) }
            COMMAND_START_HUD -> runAction(command) { OverlayController.start(context) }
            COMMAND_STOP_HUD -> runAction(command) { OverlayController.stop(context) }
            COMMAND_SET_SETTINGS -> setSettings(context, intent, command)
            COMMAND_DUMP_STATE -> dumpState(context, command)
            COMMAND_LIST_VIDEOS -> listVideos(context, command)
            COMMAND_LIST_CAMERA_MODES -> listCameraModes(context, command)
            COMMAND_CLEAR_VIDEOS -> clearVideos(context, command)
            else -> resultJson(command, ok = false).put("error", "未知命令：$command")
        }
    }

    private fun debugHelp(command: String): JSONObject {
        return resultJson(command).put(
            "commands",
            JSONArray(
                listOf(
                    COMMAND_HELP,
                    COMMAND_OPEN_MAIN,
                    COMMAND_OPEN_CAMERA,
                    COMMAND_OPEN_SETTINGS,
                    COMMAND_OPEN_CAMERA_FULLSCREEN,
                    COMMAND_OPEN_HISTORY,
                    COMMAND_OPEN_LATEST_VIDEO,
                    COMMAND_OPEN_STATUS,
                    COMMAND_OPEN_HUD,
                    COMMAND_BACKGROUND_APP,
                    COMMAND_START_RECORDING,
                    COMMAND_STOP_RECORDING,
                    COMMAND_TAKE_PHOTO,
                    COMMAND_START_HUD,
                    COMMAND_STOP_HUD,
                    COMMAND_SET_SETTINGS,
                    COMMAND_DUMP_STATE,
                    COMMAND_LIST_VIDEOS,
                    COMMAND_LIST_CAMERA_MODES,
                    COMMAND_CLEAR_VIDEOS
                )
            )
        ).put("example", "adb shell am broadcast -a $ACTION_COMMAND --es $EXTRA_COMMAND $COMMAND_DUMP_STATE")
    }

    private fun openActivity(
        context: Context,
        activityClass: Class<*>,
        command: String
    ): JSONObject {
        context.startActivity(
            Intent(context, activityClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        return resultJson(command).put("activity", activityClass.simpleName)
    }

    private fun openLatestVideo(context: Context, command: String): JSONObject {
        val video = VideoStorage.latestVideoFile(context)
            ?: return resultJson(command, ok = false).put("error", context.getString(R.string.video_replay_no_video))
        context.startActivity(
            Intent(context, VideoReplayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(VideoReplayActivity.EXTRA_VIDEO_PATH, video.absolutePath)
            }
        )
        return resultJson(command)
            .put("activity", VideoReplayActivity::class.java.simpleName)
            .put("video", video.absolutePath)
    }

    private fun backgroundApp(context: Context, command: String): JSONObject {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return resultJson(command)
    }

    private fun runAction(command: String, action: () -> Unit): JSONObject {
        action()
        return resultJson(command)
    }

    private fun setSettings(context: Context, intent: Intent, command: String): JSONObject {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        if (intent.hasExtra("segment_minutes")) {
            editor.putInt(
                DashcamSettings.PREF_SEGMENT_MINUTES,
                intent.getIntExtra("segment_minutes", DashcamSettings.DEFAULT_SEGMENT_MINUTES)
                    .coerceIn(DashcamSettings.MIN_SEGMENT_MINUTES, DashcamSettings.MAX_SEGMENT_MINUTES)
            )
        }
        if (intent.hasExtra("loop_storage_gb")) {
            editor.putInt(
                DashcamSettings.PREF_LOOP_STORAGE_GB,
                intent.getIntExtra("loop_storage_gb", DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
                    .coerceIn(DashcamSettings.MIN_LOOP_STORAGE_GB, DashcamSettings.MAX_LOOP_STORAGE_GB)
            )
        }
        if (intent.hasExtra("parking_stop_enabled")) {
            editor.putBoolean(DashcamSettings.PREF_PARKING_STOP_ENABLED, intent.getBooleanExtra("parking_stop_enabled", false))
        }
        if (intent.hasExtra("parking_stop_minutes")) {
            editor.putInt(
                DashcamSettings.PREF_PARKING_STOP_MINUTES,
                intent.getIntExtra("parking_stop_minutes", DashcamSettings.DEFAULT_PARKING_STOP_MINUTES)
                    .coerceIn(DashcamSettings.MIN_PARKING_STOP_MINUTES, DashcamSettings.MAX_PARKING_STOP_MINUTES)
            )
        }
        if (intent.hasExtra("record_audio")) {
            editor.putBoolean(DashcamSettings.PREF_RECORD_AUDIO, intent.getBooleanExtra("record_audio", false))
        }
        if (intent.hasExtra("voice_photo")) {
            editor.putBoolean(DashcamSettings.PREF_VOICE_PHOTO, intent.getBooleanExtra("voice_photo", false))
        }
        if (intent.hasExtra("auto_start_recording")) {
            editor.putBoolean(
                DashcamSettings.PREF_AUTO_START_RECORDING,
                intent.getBooleanExtra("auto_start_recording", false)
            )
        }
        if (intent.hasExtra("watermark_address")) {
            editor.putBoolean(DashcamSettings.PREF_WATERMARK_ADDRESS, intent.getBooleanExtra("watermark_address", false))
        }
        editor.apply()
        if (DashcamService.instance != null) {
            DashcamService.refreshSettings(context)
        }
        return dumpState(context, command)
    }

    private fun dumpState(context: Context, command: String): JSONObject {
        val version = versionJson(context)
        val state = resultJson(command)
            .put("package", context.packageName)
            .put("versionCode", version.getLong("versionCode"))
            .put("versionName", version.getString("versionName"))
            .put("recording", DashcamService.isRecording(context))
            .put("dashcamServiceAlive", DashcamService.instance != null)
            .put("hudRunning", OverlayController.isRunning(context))
            .put("permissions", permissionsJson(context))
            .put("settings", settingsJson(context))
            .put("videoSummary", videoSummaryJson(context))
            .put("debugDir", debugDir(context).absolutePath)
        writeDebugJson(context, "state.json", state)
        return state
    }

    @Suppress("DEPRECATION")
    private fun versionJson(context: Context): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return JSONObject()
            .put("versionCode", versionCode)
            .put("versionName", info.versionName ?: "")
    }

    private fun listVideos(context: Context, command: String): JSONObject {
        val videos = videosJson(context)
        val result = resultJson(command)
            .put("count", videos.length())
            .put("videos", videos)
        writeDebugJson(context, "videos.json", result)
        return result
    }

    private fun listCameraModes(context: Context, command: String): JSONObject {
        val modes = JSONArray()
        CameraCapabilities.debugModes(context).forEach { mode ->
            modes.put(
                JSONObject()
                    .put("label", mode.label)
                    .put("qualityKey", mode.qualityKey)
                    .put("width", mode.width)
                    .put("height", mode.height)
                    .put("fps", mode.fps)
                    .put("source", mode.source)
            )
        }
        val result = resultJson(command)
            .put("count", modes.length())
            .put("modes", modes)
        writeDebugJson(context, "camera-modes.json", result)
        return result
    }

    private fun clearVideos(context: Context, command: String): JSONObject {
        val before = VideoStorage.videoFiles(context).size
        val deletedFiles = VideoStorage.deleteAllVideos(context)
        return resultJson(command)
            .put("videosBefore", before)
            .put("deletedFiles", deletedFiles)
            .put("videosAfter", VideoStorage.videoFiles(context).size)
    }

    private fun permissionsJson(context: Context): JSONObject {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("camera", hasPermission(context, Manifest.permission.CAMERA))
            .put("recordAudio", hasPermission(context, Manifest.permission.RECORD_AUDIO))
            .put("fineLocation", hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION))
            .put("coarseLocation", hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION))
            .put("notifications", DashcamNotificationGuard.canShowRecordingNotification(context))
            .put("overlay", Settings.canDrawOverlays(context))
            .put("ignoreBatteryOptimizations", powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }

    private fun settingsJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val watermarks = DashcamSettings.load(context)
        val triggerSettings = TriggerSettings.load(context)
        return JSONObject()
            .put("segmentMinutes", prefs.getInt(DashcamSettings.PREF_SEGMENT_MINUTES, DashcamSettings.DEFAULT_SEGMENT_MINUTES))
            .put("loopStorageGb", prefs.getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB))
            .put("recordAudio", watermarks.recordAudio)
            .put("voicePhoto", watermarks.voicePhotoEnabled)
            .put("autoStartRecording", watermarks.autoStartRecording)
            .put("autoTriggerEnabled", triggerSettings.enabled)
            .put("autoTriggerBluetoothAddresses", JSONArray(triggerSettings.bluetoothAddresses.toList()))
            .put("parkingStopEnabled", watermarks.parkingStopEnabled)
            .put("parkingStopMinutes", watermarks.parkingStopMinutes)
            .put("watermarkAddress", watermarks.showAddress)
    }

    private fun videoSummaryJson(context: Context): JSONObject {
        val videos = VideoStorage.videoFiles(context)
        val totalBytes = videos.sumOf { video -> VideoStorage.companionFilesFor(video).sumOf { it.length() } }
        return JSONObject()
            .put("count", videos.size)
            .put("totalBytes", totalBytes)
            .put("dir", VideoStorage.videoDir(context).absolutePath)
    }

    private fun videosJson(context: Context): JSONArray {
        val array = JSONArray()
        VideoStorage.videoFiles(context).sortedByDescending { it.lastModified() }.forEach { video ->
            val companions = VideoStorage.companionFilesFor(video)
            array.put(
                JSONObject()
                    .put("name", video.name)
                    .put("path", video.absolutePath)
                    .put("bytes", video.length())
                    .put("modified", formatTime(video.lastModified()))
                    .put("hasSubtitle", VideoStorage.subtitleFileFor(video).exists())
                    .put("hasTrack", VideoStorage.trackFileFor(video).exists())
                    .put("favorite", VideoStorage.isFavorite(video))
                    .put("autoCleanupManaged", VideoStorage.isAutoGeneratedVideoFile(video) && !VideoStorage.isFavorite(video))
                    .put("groupBytes", companions.sumOf { it.length() })
            )
        }
        return array
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun resultJson(command: String, ok: Boolean = true): JSONObject {
        return JSONObject()
            .put("ok", ok)
            .put("command", command)
            .put("time", formatTime(System.currentTimeMillis()))
    }

    private fun writeDebugJson(context: Context, name: String, json: JSONObject) {
        File(debugDir(context), name).writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun debugDir(context: Context): File {
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "debug").apply { mkdirs() }
    }

    private fun formatTime(timeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timeMs))
    }

    companion object {
        const val ACTION_COMMAND = "com.thomas.carspeed.debug.COMMAND"
        const val EXTRA_COMMAND = "cmd"
        private const val TAG = "CarSpeedDebug"

        private const val COMMAND_HELP = "help"
        private const val COMMAND_OPEN_MAIN = "open_main"
        private const val COMMAND_OPEN_CAMERA = "open_camera"
        private const val COMMAND_OPEN_SETTINGS = "open_settings"
        private const val COMMAND_OPEN_CAMERA_FULLSCREEN = "open_camera_fullscreen"
        private const val COMMAND_OPEN_HISTORY = "open_history"
        private const val COMMAND_OPEN_LATEST_VIDEO = "open_latest_video"
        private const val COMMAND_OPEN_STATUS = "open_status"
        private const val COMMAND_OPEN_HUD = "open_hud"
        private const val COMMAND_BACKGROUND_APP = "background_app"
        private const val COMMAND_START_RECORDING = "start_recording"
        private const val COMMAND_STOP_RECORDING = "stop_recording"
        private const val COMMAND_TAKE_PHOTO = "take_photo"
        private const val COMMAND_START_HUD = "start_hud"
        private const val COMMAND_STOP_HUD = "stop_hud"
        private const val COMMAND_SET_SETTINGS = "set_settings"
        private const val COMMAND_DUMP_STATE = "dump_state"
        private const val COMMAND_LIST_VIDEOS = "list_videos"
        private const val COMMAND_LIST_CAMERA_MODES = "list_camera_modes"
        private const val COMMAND_CLEAR_VIDEOS = "clear_videos"
    }
}

package com.thomas.carspeed

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
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
            COMMAND_OPEN_PHOTOS -> openActivity(context, PhotoGalleryActivity::class.java, command)
            COMMAND_OPEN_LATEST_VIDEO -> openLatestVideo(context, command)
            COMMAND_OPEN_STATUS -> openActivity(context, StatusActivity::class.java, command)
            COMMAND_OPEN_HUD -> openActivity(context, SpeedHudActivity::class.java, command)
            COMMAND_BACKGROUND_APP -> backgroundApp(context, command)
            COMMAND_START_RECORDING -> runAction(command) { DashcamService.startRecording(context) }
            COMMAND_STOP_RECORDING -> runAction(command) { DashcamService.stopRecording(context) }
            COMMAND_TAKE_PHOTO -> runAction(command) { CameraRecordActivity.takePhoto(context) }
            COMMAND_SYNC_AUTO_TRIGGER -> syncAutoTrigger(context, command)
            COMMAND_START_HUD -> runAction(command) { OverlayController.start(context) }
            COMMAND_STOP_HUD -> runAction(command) { OverlayController.stop(context) }
            COMMAND_SET_SETTINGS -> setSettings(context, intent, command)
            COMMAND_DUMP_STATE -> dumpState(context, command)
            COMMAND_LIST_VIDEOS -> listVideos(context, command)
            COMMAND_LIST_PHOTOS -> listPhotos(context, command)
            COMMAND_LIST_BLUETOOTH -> listBluetooth(context, command)
            COMMAND_LIST_CAMERA_MODES -> listCameraModes(context, command)
            COMMAND_CLEAR_VIDEOS -> clearVideos(context, command)
            COMMAND_SIMULATE_POWER_DISCONNECTED -> runAction(command) { PowerTriggerReceiver.handlePowerDisconnected(context) }
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
                    COMMAND_OPEN_PHOTOS,
                    COMMAND_OPEN_LATEST_VIDEO,
                    COMMAND_OPEN_STATUS,
                    COMMAND_OPEN_HUD,
                    COMMAND_BACKGROUND_APP,
                    COMMAND_START_RECORDING,
                    COMMAND_STOP_RECORDING,
                    COMMAND_TAKE_PHOTO,
                    COMMAND_SYNC_AUTO_TRIGGER,
                    COMMAND_START_HUD,
                    COMMAND_STOP_HUD,
                    COMMAND_SET_SETTINGS,
                    COMMAND_DUMP_STATE,
                    COMMAND_LIST_VIDEOS,
                    COMMAND_LIST_PHOTOS,
                    COMMAND_LIST_BLUETOOTH,
                    COMMAND_LIST_CAMERA_MODES,
                    COMMAND_CLEAR_VIDEOS,
                    COMMAND_SIMULATE_POWER_DISCONNECTED
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
        val trip = VideoStorage.videoTrips(context).maxByOrNull { it.lastModified }
            ?: return resultJson(command, ok = false).put("error", context.getString(R.string.video_replay_no_video))
        context.startActivity(
            Intent(context, VideoReplayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putStringArrayListExtra(
                    VideoReplayActivity.EXTRA_VIDEO_PATHS,
                    ArrayList(trip.segments.map { it.absolutePath })
                )
            }
        )
        return resultJson(command)
            .put("activity", VideoReplayActivity::class.java.simpleName)
            .put("trip", trip.title)
            .put("segments", JSONArray(trip.segments.map { it.absolutePath }))
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
            val enabled = intent.getBooleanExtra("auto_start_recording", false)
            editor.putBoolean(
                DashcamSettings.PREF_AUTO_START_RECORDING,
                enabled
            )
            editor.putBoolean(TriggerSettings.PREF_START_ON_APP_OPEN, enabled)
        }
        if (intent.hasExtra("auto_trigger_start_on_connect")) {
            editor.putBoolean(
                TriggerSettings.PREF_START_ON_BT_CONNECTED,
                intent.getBooleanExtra("auto_trigger_start_on_connect", false)
            )
            editor.putBoolean(
                TriggerSettings.PREF_AUTO_TRIGGER_START_ON_CONNECT,
                intent.getBooleanExtra("auto_trigger_start_on_connect", false)
            )
        }
        if (intent.hasExtra("auto_trigger_start_on_bluetooth_speed")) {
            editor.putBoolean(
                TriggerSettings.PREF_START_ON_BT_CONNECTED_AND_SPEED,
                intent.getBooleanExtra("auto_trigger_start_on_bluetooth_speed", false)
            )
        }
        if (intent.hasExtra("auto_trigger_stop_on_power_disconnected")) {
            editor.putBoolean(
                TriggerSettings.PREF_STOP_ON_POWER_DISCONNECTED,
                intent.getBooleanExtra("auto_trigger_stop_on_power_disconnected", false)
            )
        }
        if (intent.hasExtra("auto_trigger_stop_on_bluetooth_disconnected")) {
            editor.putBoolean(
                TriggerSettings.PREF_STOP_ON_BT_DISCONNECTED,
                intent.getBooleanExtra("auto_trigger_stop_on_bluetooth_disconnected", false)
            )
        }
        if (intent.hasExtra("auto_trigger_stop_on_bluetooth_stationary")) {
            editor.putBoolean(
                TriggerSettings.PREF_STOP_ON_BT_DISCONNECTED_AND_STATIONARY,
                intent.getBooleanExtra("auto_trigger_stop_on_bluetooth_stationary", false)
            )
        }
        if (intent.hasExtra("manual_focus_enabled")) {
            editor.putBoolean(DashcamSettings.PREF_MANUAL_FOCUS_ENABLED, intent.getBooleanExtra("manual_focus_enabled", false))
        }
        if (intent.hasExtra("focus_percent")) {
            editor.putInt(
                DashcamSettings.PREF_FOCUS_PERCENT,
                intent.getIntExtra("focus_percent", DashcamSettings.DEFAULT_FOCUS_PERCENT)
                    .coerceIn(0, 100)
            )
        }
        if (intent.hasExtra("hdr_mode")) {
            editor.putInt(
                DashcamSettings.PREF_HDR_MODE,
                intent.getIntExtra("hdr_mode", DashcamSettings.HDR_MODE_OFF)
                    .coerceIn(DashcamSettings.HDR_MODE_OFF, DashcamSettings.HDR_MODE_SCHEDULE)
            )
        }
        if (intent.hasExtra("hdr_start_hour")) {
            editor.putInt(
                DashcamSettings.PREF_HDR_START_MINUTE,
                intent.getIntExtra("hdr_start_hour", DashcamSettings.DEFAULT_HDR_START_MINUTE / 60).coerceIn(0, 23) * 60
            )
        }
        if (intent.hasExtra("hdr_end_hour")) {
            editor.putInt(
                DashcamSettings.PREF_HDR_END_MINUTE,
                intent.getIntExtra("hdr_end_hour", DashcamSettings.DEFAULT_HDR_END_MINUTE / 60).coerceIn(0, 23) * 60
            )
        }
        if (intent.hasExtra("watermark_address")) {
            editor.putBoolean(DashcamSettings.PREF_WATERMARK_ADDRESS, intent.getBooleanExtra("watermark_address", false))
        }
        if (intent.hasExtra("watermark_focus")) {
            editor.putBoolean(DashcamSettings.PREF_WATERMARK_FOCUS, intent.getBooleanExtra("watermark_focus", false))
        }
        if (intent.hasExtra("plate_recognition_enabled")) {
            editor.putBoolean(
                DashcamSettings.PREF_PLATE_RECOGNITION_ENABLED,
                intent.getBooleanExtra("plate_recognition_enabled", false)
            )
        }
        if (intent.hasExtra("radar_alert_enabled")) {
            editor.putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, intent.getBooleanExtra("radar_alert_enabled", true))
        }
        if (intent.hasExtra("radar_thresholds")) {
            editor.putString(
                RadarAlertSettingsStore.PREF_RADAR_ALERT_THRESHOLDS,
                RadarAlertSettingsStore.formatThresholds(
                    RadarAlertSettingsStore.parseThresholds(intent.getStringExtra("radar_thresholds").orEmpty())
                )
            )
        }
        if (intent.hasExtra("radar_lead_seconds")) {
            editor.putInt(
                RadarAlertSettingsStore.PREF_RADAR_ALERT_LEAD_SECONDS,
                intent.getIntExtra("radar_lead_seconds", RadarAlertSettingsStore.DEFAULT_LEAD_SECONDS)
                    .coerceIn(RadarAlertSettingsStore.MIN_LEAD_SECONDS, RadarAlertSettingsStore.MAX_LEAD_SECONDS)
            )
        }
        if (intent.hasExtra("radar_speech_rate_percent")) {
            editor.putInt(
                RadarAlertSettingsStore.PREF_RADAR_ALERT_SPEECH_RATE_PERCENT,
                intent.getIntExtra("radar_speech_rate_percent", RadarAlertSettingsStore.DEFAULT_SPEECH_RATE_PERCENT)
                    .coerceIn(RadarAlertSettingsStore.MIN_SPEECH_RATE_PERCENT, RadarAlertSettingsStore.MAX_SPEECH_RATE_PERCENT)
            )
        }
        editor.commit()
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
            .put("telemetry", telemetryJson())
            .put("videoSummary", videoSummaryJson(context))
            .put("photoSummary", photoSummaryJson(context))
            .put("radarAlert", radarAlertJson())
            .put("plateRecognition", plateRecognitionJson(context))
            .put("voicePhoto", voicePhotoJson(context))
            .put("power", powerJson(context))
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

    private fun listPhotos(context: Context, command: String): JSONObject {
        val photos = photosJson(context)
        val result = resultJson(command)
            .put("count", photos.length())
            .put("photos", photos)
        writeDebugJson(context, "photos.json", result)
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

    private fun listBluetooth(context: Context, command: String): JSONObject {
        val settings = TriggerSettings.load(context)
        val devices = JSONArray()
        BluetoothDeviceList.debugDevices(context, settings).forEach { device ->
            devices.put(
                JSONObject()
                    .put("name", device.name)
                    .put("address", device.address)
                    .put("selected", device.selected)
                    .put("bonded", device.bonded)
                    .put("connected", device.connected)
            )
        }
        val result = resultJson(command)
            .put("permission", BluetoothDeviceList.hasPermission(context))
            .put("autoTriggerEnabled", settings.enabled)
            .put("selectedAddresses", JSONArray(settings.bluetoothAddresses.toList()))
            .put("connectedSelectedAddresses", JSONArray(TriggerConnectionState.connectedSelectedAddresses(context, settings).toList()))
            .put("devices", devices)
        writeDebugJson(context, "bluetooth.json", result)
        return result
    }

    private fun clearVideos(context: Context, command: String): JSONObject {
        val before = VideoStorage.videoTrips(context).size
        val deletedFiles = VideoStorage.deleteAllVideos(context)
        return resultJson(command)
            .put("tripsBefore", before)
            .put("deletedFiles", deletedFiles)
            .put("tripsAfter", VideoStorage.videoTrips(context).size)
    }

    private fun syncAutoTrigger(context: Context, command: String): JSONObject {
        AutoTriggerController.sync(context, stopUnavailable = true)
        return dumpState(context, command)
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
        val hdrSettings = DashcamHdrSettings.load(context)
        val radarSettings = RadarAlertSettingsStore.load(context)
        return JSONObject()
            .put("segmentMinutes", prefs.getInt(DashcamSettings.PREF_SEGMENT_MINUTES, DashcamSettings.DEFAULT_SEGMENT_MINUTES))
            .put("loopStorageGb", prefs.getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB))
            .put("recordAudio", watermarks.recordAudio)
            .put("voicePhoto", watermarks.voicePhotoEnabled)
            .put("autoStartRecording", watermarks.autoStartRecording)
            .put("manualFocusEnabled", CameraFocus.isManualEnabled(context))
            .put("focusPercent", CameraFocus.percent(context))
            .put("hdrMode", hdrSettings.mode)
            .put("hdrStart", DashcamHdrSettings.formatMinute(hdrSettings.startMinute))
            .put("hdrEnd", DashcamHdrSettings.formatMinute(hdrSettings.endMinute))
            .put("hdrRequestedNow", DashcamHdrSettings.shouldEnableNow(context))
            .put("hdrActive", DashcamService.isHdrActive())
            .put("autoTriggerEnabled", triggerSettings.enabled)
            .put("autoTriggerStartOnAppOpen", triggerSettings.startOnAppOpen)
            .put("autoTriggerStartOnConnect", triggerSettings.startOnBluetoothConnected)
            .put("autoTriggerStartOnBluetoothSpeed", triggerSettings.startOnBluetoothConnectedAndSpeed)
            .put("autoTriggerStopOnPowerDisconnected", triggerSettings.stopOnPowerDisconnected)
            .put("autoTriggerStopOnBluetoothDisconnected", triggerSettings.stopOnBluetoothDisconnected)
            .put("autoTriggerStopOnBluetoothStationary", triggerSettings.stopOnBluetoothDisconnectedAndStationary)
            .put("radarAlertEnabled", radarSettings.enabled)
            .put("radarAlertThresholds", JSONArray(radarSettings.thresholdsSeconds))
            .put("radarAlertLeadSeconds", radarSettings.leadSeconds)
            .put("radarAlertSpeechRatePercent", radarSettings.speechRatePercent)
            .put("autoTriggerBluetoothPermission", BluetoothDeviceList.hasPermission(context))
            .put("autoTriggerBluetoothAddresses", JSONArray(triggerSettings.bluetoothAddresses.toList()))
            .put(
                "autoTriggerConnectedBluetoothAddresses",
                JSONArray(TriggerConnectionState.connectedSelectedAddresses(context, triggerSettings).toList())
            )
            .put("parkingStopEnabled", watermarks.parkingStopEnabled)
            .put("parkingStopMinutes", watermarks.parkingStopMinutes)
            .put("watermarkAddress", watermarks.showAddress)
            .put("watermarkFocus", watermarks.showFocus)
            .put("plateRecognitionEnabled", watermarks.plateRecognitionEnabled)
    }

    private fun telemetryJson(): JSONObject {
        val telemetry = DashcamService.currentTelemetry()
        return JSONObject()
            .put("speedKmh", telemetry.speedKmh.toDouble())
            .putNullable("latitude", telemetry.latitude)
            .putNullable("longitude", telemetry.longitude)
            .putNullable("focusDistanceDiopters", telemetry.focusDistanceDiopters)
            .putNullable("autofocusState", telemetry.autofocusState)
    }

    private fun radarAlertJson(): JSONObject {
        val state = RadarAlertController.currentState()
        return JSONObject()
            .put("enabled", state.enabled)
            .put("active", state.active)
            .put("thresholdsSeconds", JSONArray(state.thresholdsSeconds))
            .put("leadSeconds", state.leadSeconds)
            .put("speechRatePercent", state.speechRatePercent)
            .putNullable("lastError", state.lastError)
            .putNullable("lastCameraType", state.lastCameraType)
            .putNullable("lastCameraDistanceMeters", state.lastCameraDistanceMeters)
            .putNullable("lastCameraEtaSeconds", state.lastCameraEtaSeconds)
            .putNullable("lastCameraSpeedLimit", state.lastCameraSpeedLimit)
            .putNullable("lastSpokenText", state.lastSpokenText)
            .put("lastSpokenAtMs", state.lastSpokenAtMs)
    }

    private fun plateRecognitionJson(context: Context): JSONObject {
        val settings = DashcamSettings.load(context)
        val state = PlateRecognitionMonitor.currentState()
        return JSONObject()
            .put("enabled", settings.plateRecognitionEnabled)
            .put("active", state.active)
            .put("analyzerBound", state.analyzerBound)
            .put("engine", state.engine)
            .put("engineReady", state.engineReady)
            .put("analyzedFrames", state.analyzedFrames)
            .put("recordedSamples", state.recordedSamples)
            .put("lastAnalyzedAtMs", state.lastAnalyzedAtMs)
            .putNullable("lastFrameWidth", state.lastFrameWidth)
            .putNullable("lastFrameHeight", state.lastFrameHeight)
            .putNullable("lastRotationDegrees", state.lastRotationDegrees)
            .putNullable("lastPlateCode", state.lastPlateCode)
            .put("lastPlateCount", state.lastPlateCount)
            .put("recognizedPlates", state.recognizedPlates)
            .put("smartFocusTriggeredCount", state.smartFocusTriggeredCount)
            .put("lastSmartFocusAtMs", state.lastSmartFocusAtMs)
            .putNullable("lastSidecarPath", state.lastSidecarPath)
            .putNullable("lastError", state.lastError)
    }

    private fun voicePhotoJson(context: Context): JSONObject {
        val settings = DashcamSettings.load(context)
        val state = VoicePhotoMonitor.currentState()
        val recognizerState = VoicePhotoRecognizer.currentState()
        return JSONObject()
            .put("enabled", settings.voicePhotoEnabled)
            .put("recordAudioRequested", settings.recordAudio)
            .put("audioPermission", hasPermission(context, Manifest.permission.RECORD_AUDIO))
            .put("recordAudioEffective", settings.recordAudio && !settings.voicePhotoEnabled && hasPermission(context, Manifest.permission.RECORD_AUDIO))
            .put("preferredEngine", "vosk")
            .putNullable("lastEngine", state.lastEngine)
            .put("modelState", recognizerState.modelState)
            .put("modelReady", recognizerState.modelReady)
            .putNullable("modelPath", recognizerState.modelPath)
            .putNullable("recognizerOwner", recognizerState.activeOwner)
            .put("recognizerListening", recognizerState.listening)
            .putNullable("recognizerLastText", recognizerState.lastText)
            .putNullable("recognizerLastCommand", recognizerState.lastCommand)
            .putNullable("recognizerLastError", recognizerState.lastError)
            .put("previewListening", state.previewListening)
            .put("serviceListening", state.serviceListening)
            .putNullable("lastOwner", state.lastOwner)
            .putNullable("lastErrorCode", state.lastErrorCode)
            .putNullable("lastErrorLabel", state.lastErrorLabel)
            .putNullable("lastHeardText", state.lastHeardText)
            .put("lastTriggeredAtMs", state.lastTriggeredAtMs)
            .put("lastStartedAtMs", state.lastStartedAtMs)
    }

    private fun powerJson(context: Context): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        return JSONObject()
            .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("batteryPercent", manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
    }

    private fun videoSummaryJson(context: Context): JSONObject {
        val trips = VideoStorage.videoTrips(context)
        val totalBytes = trips.sumOf { trip -> VideoStorage.companionFilesFor(trip).sumOf { it.length() } }
        return JSONObject()
            .put("count", trips.size)
            .put("totalBytes", totalBytes)
            .put("dir", VideoStorage.videoDir(context).absolutePath)
    }

    private fun photoSummaryJson(context: Context): JSONObject {
        val photos = VideoStorage.photoFiles(context)
        return JSONObject()
            .put("count", photos.size)
            .put("totalBytes", photos.sumOf { it.length() })
            .put("dir", VideoStorage.photoDir(context).absolutePath)
    }

    private fun videosJson(context: Context): JSONArray {
        val array = JSONArray()
        VideoStorage.videoTrips(context).sortedByDescending { it.lastModified }.forEach { trip ->
            val companions = VideoStorage.companionFilesFor(trip)
            array.put(
                JSONObject()
                    .put("name", trip.title)
                    .put("dir", trip.directory.absolutePath)
                    .put("segmentCount", trip.segments.size)
                    .put("segments", JSONArray(trip.segments.map { it.absolutePath }))
                    .put("bytes", companions.sumOf { it.length() })
                    .put("modified", formatTime(trip.lastModified))
                    .put("hasSubtitle", trip.segments.any { VideoStorage.subtitleFileFor(it).exists() })
                    .put("hasTrack", trip.segments.any { VideoStorage.trackFileFor(it).exists() })
                    .put("favorite", VideoStorage.isFavorite(trip))
                    .put("hasPlateRecognition", trip.segments.any { VideoStorage.plateFileFor(it).exists() })
                    .put("groupBytes", companions.sumOf { it.length() })
            )
        }
        return array
    }

    private fun photosJson(context: Context): JSONArray {
        val array = JSONArray()
        VideoStorage.photoFiles(context).sortedByDescending { it.lastModified() }.forEach { photo ->
            array.put(
                JSONObject()
                    .put("name", photo.name)
                    .put("path", photo.absolutePath)
                    .put("bytes", photo.length())
                    .put("modified", formatTime(photo.lastModified()))
            )
        }
        return array
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
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
        private const val COMMAND_OPEN_PHOTOS = "open_photos"
        private const val COMMAND_OPEN_LATEST_VIDEO = "open_latest_video"
        private const val COMMAND_OPEN_STATUS = "open_status"
        private const val COMMAND_OPEN_HUD = "open_hud"
        private const val COMMAND_BACKGROUND_APP = "background_app"
        private const val COMMAND_START_RECORDING = "start_recording"
        private const val COMMAND_STOP_RECORDING = "stop_recording"
        private const val COMMAND_TAKE_PHOTO = "take_photo"
        private const val COMMAND_SYNC_AUTO_TRIGGER = "sync_auto_trigger"
        private const val COMMAND_START_HUD = "start_hud"
        private const val COMMAND_STOP_HUD = "stop_hud"
        private const val COMMAND_SET_SETTINGS = "set_settings"
        private const val COMMAND_DUMP_STATE = "dump_state"
        private const val COMMAND_LIST_VIDEOS = "list_videos"
        private const val COMMAND_LIST_PHOTOS = "list_photos"
        private const val COMMAND_LIST_BLUETOOTH = "list_bluetooth"
        private const val COMMAND_LIST_CAMERA_MODES = "list_camera_modes"
        private const val COMMAND_CLEAR_VIDEOS = "clear_videos"
        private const val COMMAND_SIMULATE_POWER_DISCONNECTED = "simulate_power_disconnected"
    }
}

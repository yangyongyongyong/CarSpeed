package com.thomas.carspeed

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AimlessModeListener
import com.amap.api.navi.enums.AimLessMode
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import java.util.Locale
import kotlin.math.roundToInt

data class RadarAlertState(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val thresholdsSeconds: List<Int> = listOf(20, 10, 5),
    val leadSeconds: Int = 2,
    val speechRatePercent: Int = 150,
    val lastError: String? = null,
    val lastCameraType: Int? = null,
    val lastCameraDistanceMeters: Int? = null,
    val lastCameraEtaSeconds: Int? = null,
    val lastCameraSpeedLimit: Int? = null,
    val lastRadarDataAtMs: Long = 0L,
    val radarDataNetworkAvailable: Boolean? = null,
    val lastDataWarningText: String? = null,
    val lastDataWarningAtMs: Long = 0L,
    val lastSpokenText: String? = null,
    val lastSpokenAtMs: Long = 0L
)

object RadarAlertController {
    private const val MIN_SPEED_FOR_ETA_KMH = 5f
    private const val SPEAK_MIN_GAP_MS = 2_500L
    private const val UNKNOWN_CAMERA_TYPE = 0
    private const val SPEED_CAMERA_TYPE = 4
    private const val SPEEDING_VIOLATION_TYPE = 102

    @Volatile private var state = RadarAlertState()
    @Volatile private var latestSpeedKmh = 0f
    private var appContext: Context? = null
    private var navi: AMapNavi? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var activeCameraLock: RadarCameraLock? = null
    private var dataHealth = RadarDataHealthState(serviceStartedAtMs = 0L, lastDataAtMs = 0L)
    private val firedThresholdsByCamera = mutableMapOf<String, MutableSet<Int>>()

    private val aimlessListener = object : AimlessModeListener {
        override fun onUpdateTrafficFacility(infos: Array<AMapNaviTrafficFacilityInfo>?) = Unit

        override fun onUpdateAimlessModeElecCameraInfo(cameraInfo: Array<AMapNaviTrafficFacilityInfo>?) {
            handleCameraInfo(cameraInfo.orEmpty())
        }

        override fun updateAimlessModeStatistics(aimLessModeStat: AimLessModeStat?) = Unit

        override fun updateAimlessModeCongestionInfo(aimLessModeCongestionInfo: AimLessModeCongestionInfo?) = Unit
    }

    fun start(context: Context) {
        val settings = RadarAlertSettingsStore.load(context)
        appContext = context.applicationContext
        state = state.copy(
            enabled = settings.enabled,
            thresholdsSeconds = settings.thresholdsSeconds,
            leadSeconds = settings.leadSeconds,
            speechRatePercent = settings.speechRatePercent
        )
        if (!settings.enabled || state.active) return
        try {
            ensureTts(context.applicationContext, settings)
            val activeNavi = AMapNavi.getInstance(context.applicationContext)
            activeNavi.setUseInnerVoice(false, true)
            activeNavi.addAimlessModeListener(aimlessListener)
            activeNavi.startGPS(1_000L)
            activeNavi.startAimlessMode(AimLessMode.CAMERA_DETECTED)
            navi = activeNavi
            val now = System.currentTimeMillis()
            activeCameraLock = null
            dataHealth = RadarDataHealthState(serviceStartedAtMs = now, lastDataAtMs = now)
            firedThresholdsByCamera.clear()
            state = state.copy(active = true, lastError = null)
        } catch (error: Exception) {
            state = state.copy(active = false, lastError = error.message ?: error.javaClass.simpleName)
        }
    }

    fun refresh(context: Context) {
        val settings = RadarAlertSettingsStore.load(context)
        state = state.copy(
            enabled = settings.enabled,
            thresholdsSeconds = settings.thresholdsSeconds,
            leadSeconds = settings.leadSeconds,
            speechRatePercent = settings.speechRatePercent
        )
        tts?.setSpeechRate(settings.speechRate)
        if (settings.enabled) {
            start(context)
        } else {
            stop()
        }
    }

    fun stop() {
        runCatching { navi?.stopAimlessMode() }
        runCatching { navi?.stopGPS() }
        runCatching { navi?.removeAimlessModeListener(aimlessListener) }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        navi = null
        tts = null
        ttsReady = false
        pendingText = null
        activeCameraLock = null
        dataHealth = RadarDataHealthState(serviceStartedAtMs = 0L, lastDataAtMs = 0L)
        firedThresholdsByCamera.clear()
        AMapNavi.setTtsPlaying(false)
        state = state.copy(active = false)
    }

    fun updateSpeed(speedKmh: Float) {
        latestSpeedKmh = speedKmh.coerceAtLeast(0f)
    }

    fun checkDataAvailability(networkAvailable: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val settings = appContext?.let { RadarAlertSettingsStore.load(it) } ?: return
        if (!settings.enabled || !state.active) return
        val warningType = RadarAlertPlanner.dataWarningType(
            networkAvailable = networkAvailable,
            speedKmh = latestSpeedKmh,
            nowMs = nowMs,
            health = dataHealth
        )
        if (warningType == null) {
            state = state.copy(radarDataNetworkAvailable = networkAvailable)
            return
        }
        val text = when (warningType) {
            RadarDataWarningType.NETWORK_UNAVAILABLE -> "网络不可用，测速雷达数据可能无法更新，请注意观察路况"
            RadarDataWarningType.DATA_STALE -> "测速雷达数据暂未更新，请注意观察路况"
        }
        if (speak(text, nowMs)) {
            dataHealth = RadarAlertPlanner.markDataWarning(dataHealth, warningType, nowMs)
            state = state.copy(
                radarDataNetworkAvailable = networkAvailable,
                lastDataWarningText = text,
                lastDataWarningAtMs = nowMs,
                lastSpokenText = text,
                lastSpokenAtMs = nowMs
            )
        }
    }

    fun currentState(): RadarAlertState = state

    private fun ensureTts(context: Context, settings: RadarAlertSettings) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val activeTts = tts ?: return@TextToSpeech
                activeTts.language = Locale.CHINA
                activeTts.setSpeechRate(settings.speechRate)
                activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        AMapNavi.setTtsPlaying(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        AMapNavi.setTtsPlaying(false)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        AMapNavi.setTtsPlaying(false)
                    }
                })
                pendingText?.let { text ->
                    pendingText = null
                    speakNow(text)
                }
            } else {
                state = state.copy(lastError = "系统 TTS 初始化失败")
            }
        }
    }

    private fun handleCameraInfo(cameraInfo: Array<out AMapNaviTrafficFacilityInfo>) {
        val settings = appContext?.let { RadarAlertSettingsStore.load(it) } ?: return
        if (!settings.enabled || !state.active) return
        val now = System.currentTimeMillis()
        dataHealth = dataHealth.copy(lastDataAtMs = now)
        state = state.copy(lastRadarDataAtMs = now)
        val speedKmh = latestSpeedKmh.takeIf { it >= MIN_SPEED_FOR_ETA_KMH } ?: return
        val cameras = cameraInfo
            .filter { it.getDistance() > 0 && isSpeedCamera(it.getBroadcastType()) }
            .map { info ->
                RadarCameraSnapshot(
                    key = cameraKey(info),
                    distanceMeters = info.getDistance(),
                    cameraType = info.getBroadcastType(),
                    speedLimit = info.getLimitSpeed().takeIf { it > 0 }
                )
            }
        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = cameras,
            currentLock = activeCameraLock,
            nowMs = now,
            speedKmh = speedKmh,
            settings = settings
        )
        activeCameraLock = selection.lock
        val nearest = selection.camera ?: return

        val distance = nearest.distanceMeters
        val etaSeconds = RadarAlertPlanner.etaSeconds(distance, speedKmh) ?: return
        val fired = firedThresholdsByCamera[nearest.key].orEmpty()
        if (RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(nearest, fired)) {
            val text = RadarAlertPlanner.farUnknownLimitAlertText(distance)
            val spoke = speak(text)
            if (spoke) {
                firedThresholdsByCamera.getOrPut(nearest.key) { mutableSetOf() } += RadarAlertPlanner.FAR_UNKNOWN_LIMIT_ALERT_MARKER
            }
            state = state.copy(
                enabled = settings.enabled,
                active = true,
                thresholdsSeconds = settings.thresholdsSeconds,
                leadSeconds = settings.leadSeconds,
                speechRatePercent = settings.speechRatePercent,
                lastError = null,
                lastCameraType = nearest.cameraType,
                lastCameraDistanceMeters = distance,
                lastCameraEtaSeconds = etaSeconds,
                lastCameraSpeedLimit = nearest.speedLimit,
                lastRadarDataAtMs = now,
                lastSpokenText = if (spoke) text else state.lastSpokenText,
                lastSpokenAtMs = if (spoke) now else state.lastSpokenAtMs
            )
            if (spoke) return
        }
        val dueThresholds = RadarAlertPlanner.dueThresholds(etaSeconds, settings, fired)
        val threshold = RadarAlertPlanner.thresholdToSpeak(dueThresholds, etaSeconds, settings) ?: run {
            state = state.copy(
                enabled = settings.enabled,
                active = true,
                thresholdsSeconds = settings.thresholdsSeconds,
                leadSeconds = settings.leadSeconds,
                speechRatePercent = settings.speechRatePercent,
                lastCameraType = nearest.cameraType,
                lastCameraDistanceMeters = distance,
                lastCameraEtaSeconds = etaSeconds,
                lastCameraSpeedLimit = nearest.speedLimit,
                lastRadarDataAtMs = now
            )
            return
        }

        firedThresholdsByCamera.getOrPut(nearest.key) { mutableSetOf() } +=
            RadarAlertPlanner.thresholdsToMark(dueThresholds, threshold)
        val speedLimit = nearest.speedLimit
        val text = buildAlertText(threshold, distance, speedLimit)
        val spoke = speak(text)
        state = state.copy(
            enabled = settings.enabled,
            active = true,
            thresholdsSeconds = settings.thresholdsSeconds,
            leadSeconds = settings.leadSeconds,
            speechRatePercent = settings.speechRatePercent,
            lastError = null,
            lastCameraType = nearest.cameraType,
            lastCameraDistanceMeters = distance,
            lastCameraEtaSeconds = etaSeconds,
            lastCameraSpeedLimit = speedLimit,
            lastRadarDataAtMs = now,
            lastSpokenText = if (spoke) text else state.lastSpokenText,
            lastSpokenAtMs = if (spoke) now else state.lastSpokenAtMs
        )
    }

    private fun isSpeedCamera(type: Int): Boolean {
        return type == SPEED_CAMERA_TYPE || type == SPEEDING_VIOLATION_TYPE || type == UNKNOWN_CAMERA_TYPE
    }

    private fun cameraKey(info: AMapNaviTrafficFacilityInfo): String {
        val longitude = (info.getCoorX() * 100_000).roundToInt()
        val latitude = (info.getCoorY() * 100_000).roundToInt()
        return "$longitude:$latitude:${info.getBroadcastType()}:${info.getLimitSpeed()}"
    }

    private fun buildAlertText(thresholdSeconds: Int, distanceMeters: Int, speedLimit: Int?): String {
        val limit = speedLimit?.let { "限速${it}" }.orEmpty()
        val distance = RadarAlertPlanner.distanceText(distanceMeters)
        return "测速雷达，${thresholdSeconds}秒，${distance}，$limit".trimEnd('，')
    }

    private fun speak(text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs - state.lastSpokenAtMs < SPEAK_MIN_GAP_MS) return false
        if (!ttsReady) {
            pendingText = text
            return true
        }
        speakNow(text)
        return true
    }

    private fun speakNow(text: String) {
        val utteranceId = "radar-${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        AppSpeechGuard.markSpeechStarted()
        AMapNavi.setTtsPlaying(true)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }
}

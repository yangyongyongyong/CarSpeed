package com.thomas.carspeed

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.AimlessModeListener
import com.amap.api.navi.ParallelRoadListener
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.AimLessMode
import com.amap.api.navi.model.AMapNaviLocation
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
    val lastSpokenAtMs: Long = 0L,
    val attitudeSensorsAvailable: Boolean = true,
    val alertRoadLayerElevated: Boolean = true,
    val alertRoadInTunnel: Boolean = false,
    val roadLayerUsingDefault: Boolean = true,
    val roadLayerCorrectedByDivergence: Boolean = false
)

object RadarAlertController {
    private const val SPEAK_MIN_GAP_MS = 2_500L

    @Volatile private var state = RadarAlertState()
    @Volatile private var latestSpeedKmh = 0f
    @Volatile private var naviSpeedKmh = 0f
    @Volatile private var naviSpeedAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var navi: AMapNavi? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingText: String? = null
    private var activeCameraLock: RadarCameraLock? = null
    private var dataHealth = RadarDataHealthState(serviceStartedAtMs = 0L, lastDataAtMs = 0L)
    private val firedThresholdsByCamera = mutableMapOf<String, MutableSet<Int>>()
    private val etaAnchorByCamera = mutableMapOf<String, EtaAnchor>()
    private val passedCameraKeys = mutableSetOf<String>()
    private val sightingLock = Any()
    private var sightings = emptyList<RadarSighting>()
    private val roadLock = Any()
    private var alertLayerElevated = true
    private var alertRoadInTunnel = false
    private var roadLayerUsingDefault = true
    private var roadLayerCorrectedByDivergence = false
    private var elevatedFlag = RoadLayerAlertGate.ELEVATED_NONE
    private var pendingSwitchSinceMs = 0L
    private var lastSwitchAtMs = 0L

    private val aimlessListener = object : AimlessModeListener {
        override fun onUpdateTrafficFacility(infos: Array<AMapNaviTrafficFacilityInfo>?) = Unit

        override fun onUpdateAimlessModeElecCameraInfo(cameraInfo: Array<AMapNaviTrafficFacilityInfo>?) {
            handleCameraInfo(cameraInfo.orEmpty())
        }

        override fun updateAimlessModeStatistics(aimLessModeStat: AimLessModeStat?) = Unit

        override fun updateAimlessModeCongestionInfo(aimLessModeCongestionInfo: AimLessModeCongestionInfo?) = Unit
    }

    private val parallelRoadListener = ParallelRoadListener { status ->
        onParallelRoad(status?.getmElevatedRoadStatusFlag() ?: RoadLayerAlertGate.ELEVATED_NONE)
    }

    private val naviListener = object : SimpleNaviListener() {
        override fun onLocationChange(location: AMapNaviLocation?) {
            val speed = location?.speed ?: return
            if (speed <= 0f) return
            naviSpeedKmh = speed
            naviSpeedAtMs = System.currentTimeMillis()
        }
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
        var activeNavi: AMapNavi? = null
        try {
            ensureTts(context.applicationContext, settings)
            activeNavi = AMapNavi.getInstance(context.applicationContext)
            activeNavi?.setUseInnerVoice(false, true)
            naviSpeedKmh = 0f
            naviSpeedAtMs = 0L
            activeNavi?.addAMapNaviListener(naviListener)
            activeNavi?.addAimlessModeListener(aimlessListener)
            activeNavi?.addParallelRoadListener(parallelRoadListener)
            activeNavi?.startGPS(1_000L)
            activeNavi?.startAimlessMode(AimLessMode.CAMERA_DETECTED)
            navi = activeNavi
            resetRoadLayer(settings.unknownRoadLayerElevated)
            val now = System.currentTimeMillis()
            activeCameraLock = null
            dataHealth = RadarDataHealthState(serviceStartedAtMs = now, lastDataAtMs = now)
            firedThresholdsByCamera.clear()
            etaAnchorByCamera.clear()
            passedCameraKeys.clear()
            state = state.copy(
                active = true,
                lastError = null,
                alertRoadLayerElevated = settings.unknownRoadLayerElevated,
                alertRoadInTunnel = false,
                roadLayerUsingDefault = true,
                roadLayerCorrectedByDivergence = false
            )
        } catch (error: Exception) {
            runCatching { activeNavi?.stopAimlessMode() }
            runCatching { activeNavi?.stopGPS() }
            runCatching { activeNavi?.removeAMapNaviListener(naviListener) }
            runCatching { activeNavi?.removeAimlessModeListener(aimlessListener) }
            runCatching { activeNavi?.removeParallelRoadListener(parallelRoadListener) }
            navi = null
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
        runCatching { navi?.removeAMapNaviListener(naviListener) }
        runCatching { navi?.removeAimlessModeListener(aimlessListener) }
        runCatching { navi?.removeParallelRoadListener(parallelRoadListener) }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        navi = null
        tts = null
        ttsReady = false
        pendingText = null
        activeCameraLock = null
        naviSpeedKmh = 0f
        naviSpeedAtMs = 0L
        dataHealth = RadarDataHealthState(serviceStartedAtMs = 0L, lastDataAtMs = 0L)
        firedThresholdsByCamera.clear()
        etaAnchorByCamera.clear()
        passedCameraKeys.clear()
        AMapNavi.setTtsPlaying(false)
        state = state.copy(active = false)
    }

    fun updateSpeed(speedKmh: Float) {
        latestSpeedKmh = speedKmh.coerceAtLeast(0f)
        if (state.active) maybeSwitchElevated()
    }

    fun setAttitudeSensorsAvailable(available: Boolean) {
        state = state.copy(attitudeSensorsAvailable = available)
    }

    internal fun updateRoadLayer(decision: RoadLayerDecision) {
        val changed = synchronized(roadLock) {
            val elevated = decision.alertLayer == RoadLayer.ELEVATED
            val tunnel = decision.alertLayer == RoadLayer.TUNNEL
            val usingDefault = decision.usingDefault
            val corrected = decision.correctedByDivergence
            val same = elevated == alertLayerElevated &&
                tunnel == alertRoadInTunnel &&
                usingDefault == roadLayerUsingDefault &&
                corrected == roadLayerCorrectedByDivergence
            alertLayerElevated = elevated
            alertRoadInTunnel = tunnel
            roadLayerUsingDefault = usingDefault
            roadLayerCorrectedByDivergence = corrected
            !same
        }
        if (!changed) return
        publishRoadLayer()
        maybeSwitchElevated()
    }

    fun onUnknownRoadDefaultChanged(elevated: Boolean) {
        val shouldSwitch = synchronized(roadLock) {
            if (!roadLayerUsingDefault) return@synchronized false
            alertLayerElevated = elevated
            alertRoadInTunnel = false
            true
        }
        publishRoadLayer()
        if (shouldSwitch) maybeSwitchElevated()
    }

    private fun onParallelRoad(flag: Int) {
        synchronized(roadLock) { elevatedFlag = flag }
        RoadLayerEngine.setAmapElevatedFlag(flag)
        maybeSwitchElevated()
    }

    private fun resetRoadLayer(unknownDefaultsToElevated: Boolean) {
        synchronized(roadLock) {
            alertLayerElevated = unknownDefaultsToElevated
            alertRoadInTunnel = false
            roadLayerUsingDefault = true
            roadLayerCorrectedByDivergence = false
            elevatedFlag = RoadLayerAlertGate.ELEVATED_NONE
            pendingSwitchSinceMs = 0L
            lastSwitchAtMs = 0L
        }
    }

    private fun publishRoadLayer() {
        val snapshot = synchronized(roadLock) {
            LayerPublish(
                elevated = alertLayerElevated,
                tunnel = alertRoadInTunnel,
                usingDefault = roadLayerUsingDefault,
                corrected = roadLayerCorrectedByDivergence
            )
        }
        state = state.copy(
            alertRoadLayerElevated = snapshot.elevated,
            alertRoadInTunnel = snapshot.tunnel,
            roadLayerUsingDefault = snapshot.usingDefault,
            roadLayerCorrectedByDivergence = snapshot.corrected
        )
    }

    private fun maybeSwitchElevated(nowMs: Long = System.currentTimeMillis()) {
        val activeNavi = navi ?: return
        val doSwitch = synchronized(roadLock) {
            if (!RoadLayerAlertGate.needsSwitch(alertLayerElevated, elevatedFlag)) {
                pendingSwitchSinceMs = 0L
                false
            } else if (nowMs - lastSwitchAtMs < RoadLayerAlertGate.SWITCH_COOLDOWN_MS) {
                false
            } else {
                lastSwitchAtMs = nowMs
                pendingSwitchSinceMs = nowMs
                true
            }
        }
        if (doSwitch) {
            RoadLayerEngine.noteOwnParallelSwitch()
            runCatching { activeNavi.switchParallelRoad(RoadLayerAlertGate.SWITCH_TYPE_ELEVATED) }
        }
    }

    private fun roadSnapshot(): RoadSnapshot {
        return synchronized(roadLock) {
            RoadSnapshot(
                alertElevated = alertLayerElevated,
                tunnel = alertRoadInTunnel,
                usingDefault = roadLayerUsingDefault,
                corrected = roadLayerCorrectedByDivergence,
                elevatedFlag = elevatedFlag,
                pendingSwitchSinceMs = pendingSwitchSinceMs
            )
        }
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
            RadarDataWarningType.DATA_STALE -> "高德暂未回调测速雷达数据"
        }
        val shouldSpeak = RadarAlertPlanner.shouldSpeakDataWarning(warningType)
        val spoke = shouldSpeak && speak(text, nowMs)
        if (spoke || !shouldSpeak) {
            dataHealth = RadarAlertPlanner.markDataWarning(dataHealth, warningType, nowMs)
            state = state.copy(
                radarDataNetworkAvailable = networkAvailable,
                lastDataWarningText = text,
                lastDataWarningAtMs = nowMs,
                lastSpokenText = if (spoke) text else state.lastSpokenText,
                lastSpokenAtMs = if (spoke) nowMs else state.lastSpokenAtMs
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
                activeTts.setAudioAttributes(AppPlaybackDuck.speechAttributes())
                activeTts.setSpeechRate(settings.speechRate)
                activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        AMapNavi.setTtsPlaying(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        AMapNavi.setTtsPlaying(false)
                        mainHandler.post { finishSpeech(utteranceId) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        AMapNavi.setTtsPlaying(false)
                        mainHandler.post { finishSpeech(utteranceId) }
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
        rememberSpeedCameras(cameraInfo, now)
        val road = roadSnapshot()
        val overlapMode = RoadLayerAlertGate.mode(
            alertElevated = road.alertElevated,
            elevatedFlag = road.elevatedFlag,
            pendingSwitchSinceMs = road.pendingSwitchSinceMs,
            nowMs = now
        )
        if (overlapMode == OverlapAlertMode.SUPPRESS) return
        val layerPrefix = if (overlapMode == OverlapAlertMode.LAYER) {
            if (road.alertElevated) "高架" else "地面"
        } else {
            null
        }
        val speedKmh = RadarAlertPlanner.etaSpeedKmh(
            naviSpeedKmh = naviSpeedKmh,
            naviSpeedAtMs = naviSpeedAtMs,
            vehicleSpeedKmh = latestSpeedKmh,
            nowMs = now
        ).takeIf { it >= RadarAlertPlanner.MIN_SPEED_FOR_ETA_KMH } ?: return
        val cameras = cameraInfo
            .filter { it.getDistance() > 0 && RadarAlertPlanner.isSpeedCamera(it.getBroadcastType()) }
            .mapNotNull { info ->
                val key = cameraKey(info)
                if (key in passedCameraKeys) return@mapNotNull null
                RadarCameraSnapshot(
                    key = key,
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
        if (selection.passedCameraKey?.let { passedCameraKeys.add(it) } == true) {
            playPassTone()
            return
        }
        val nearest = selection.camera ?: return

        val distance = nearest.distanceMeters
        val rawEtaSeconds = RadarAlertPlanner.etaSeconds(distance, speedKmh) ?: return
        val reconciled = RadarAlertPlanner.reconcileEta(etaAnchorByCamera[nearest.key], rawEtaSeconds, now)
        val etaSeconds = reconciled.etaSeconds
        etaAnchorByCamera[nearest.key] = reconciled.anchor
        val fired = firedThresholdsByCamera[nearest.key].orEmpty()
        if (RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(nearest, fired)) {
            val text = withRoadLayer(layerPrefix, RadarAlertPlanner.farUnknownLimitAlertText(distance))
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
        val text = withRoadLayer(layerPrefix, buildAlertText(threshold, speedKmh, speedLimit))
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

    private fun cameraKey(info: AMapNaviTrafficFacilityInfo): String {
        val longitude = (info.getCoorX() * 100_000).roundToInt()
        val latitude = (info.getCoorY() * 100_000).roundToInt()
        return "$longitude:$latitude:${info.getBroadcastType()}:${info.getLimitSpeed()}"
    }

    private fun withRoadLayer(prefix: String?, text: String): String {
        return if (prefix.isNullOrEmpty()) text else "$prefix，$text"
    }

    private fun buildAlertText(thresholdSeconds: Int, speedKmh: Float, speedLimit: Int?): String {
        val limit = speedLimit?.let { "限速${it}" }.orEmpty()
        val distance = RadarAlertPlanner.distanceText(
            RadarAlertPlanner.spokenDistanceMeters(thresholdSeconds, speedKmh)
        )
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
            putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
            putString(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0")
        }
        AppSpeechGuard.markSpeechStarted()
        AMapNavi.setTtsPlaying(true)
        holdForSpeech(utteranceId)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private val speechDucks = mutableMapOf<String, DuckToken>()

    private fun holdForSpeech(utteranceId: String) {
        val context = appContext ?: return
        speechDucks.remove(utteranceId)?.release()
        speechDucks[utteranceId] = AppPlaybackDuck.acquire(context)
        mainHandler.postDelayed({ finishSpeech(utteranceId) }, 12_000L)
    }

    private fun finishSpeech(utteranceId: String?) {
        if (utteranceId == null) return
        speechDucks.remove(utteranceId)?.release()
    }

    private fun playPassTone() {
        val ducked = appContext?.let { AppPlaybackDuck.acquire(it) }
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        if (tone == null) {
            ducked?.release()
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 140)
        mainHandler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_ACK, 140) }, 220L)
        mainHandler.postDelayed({
            tone.release()
            ducked?.release()
        }, 520L)
    }

    private data class LayerPublish(
        val elevated: Boolean,
        val tunnel: Boolean,
        val usingDefault: Boolean,
        val corrected: Boolean
    )

    private data class RoadSnapshot(
        val alertElevated: Boolean,
        val tunnel: Boolean,
        val usingDefault: Boolean,
        val corrected: Boolean,
        val elevatedFlag: Int,
        val pendingSwitchSinceMs: Long
    )

    internal fun sightingsBetween(startMs: Long, endMs: Long): List<RadarSighting> {
        return synchronized(sightingLock) {
            sightings.filter { it.timeMs in startMs..endMs }
        }
    }

    private fun rememberSpeedCameras(cameraInfo: Array<out AMapNaviTrafficFacilityInfo>, nowMs: Long) {
        val road = roadSnapshot()
        val layer = RouteRadarMarks.layer(tunnel = road.tunnel, elevated = road.alertElevated)
        cameraInfo.forEach { info ->
            if (info.getDistance() <= 0 || !RadarAlertPlanner.isSpeedCamera(info.getBroadcastType())) return@forEach
            val latitude = info.getCoorY()
            val longitude = info.getCoorX()
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@forEach
            if (kotlin.math.abs(latitude) < 0.01 && kotlin.math.abs(longitude) < 0.01) return@forEach
            val incoming = RadarSighting(
                timeMs = nowMs,
                latitude = latitude,
                longitude = longitude,
                cameraType = info.getBroadcastType(),
                speedLimit = info.getLimitSpeed().takeIf { it > 0 },
                roadLayer = layer,
                usingDefault = road.usingDefault,
                correctedByDivergence = road.corrected
            )
            synchronized(sightingLock) {
                sightings = RouteRadarMarks.remember(sightings, incoming, nowMs)
            }
        }
    }

}

package com.thomas.carspeed

import android.content.Context
import android.media.AudioManager
import android.util.Log
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
import com.amap.api.navi.model.AMapNaviCameraInfo
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
    private const val ROUTE_CAMERA_FRESH_MS = 3_000L
    /** 空路播报的检查节拍。间隔本来是秒级，1 秒足够准，又不至于太费电。 */
    private const val CLEAR_STRETCH_TICK_MS = 1_000L

    /**
     * 预警留档。默认跟着 debug 版本打开：正式版不往用户手机上写这种日志。
     * 设置里手动关掉也能生效，因为有时只想安静跑一段。
     */
    @Volatile private var traceEnabled = BuildConfig.DEBUG

    @Volatile private var state = RadarAlertState()
    @Volatile private var latestSpeedKmh = 0f
    @Volatile private var naviSpeedKmh = 0f
    @Volatile private var naviSpeedAtMs = 0L
    @Volatile private var lastCarLatitude = Double.NaN
    @Volatile private var lastCarLongitude = Double.NaN
    @Volatile private var lastCarBearing = Float.NaN
    @Volatile private var lastRoadBearing = Float.NaN
    @Volatile private var lastCarAccuracyMeters = Float.NaN
    @Volatile private var lastCarFixAtMs = 0L
    @Volatile private var lastCarGpsTimeMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var navi: AMapNavi? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingText: String? = null
    private var activeCameraLock: RadarCameraLock? = null
    private var dataHealth = RadarDataHealthState(serviceStartedAtMs = 0L, lastDataAtMs = 0L)
    private val firedThresholdsByCamera = mutableMapOf<String, MutableSet<Int>>()
    private val etaAnchorByCamera = mutableMapOf<String, EtaAnchor>()
    private val passedCameraKeys = mutableSetOf<String>()
    private val cameraFixByKey = mutableMapOf<String, Pair<Double, Double>>()
    /** 留档用：高德原始上报的电子眼坐标，和 cameraFixByKey 对比能看出我们改了多少。 */
    private val reportedRadarByKey = mutableMapOf<String, Pair<Double, Double>>()
    private val heldFixByKey = mutableMapOf<String, RadarBroadcastFix.Held>()
    private var shownRouteCameras = emptyList<RadarBroadcastFix.Candidate>()
    private var shownRouteCamerasAtMs = 0L
    private var intervalRouteCameras = emptyList<RadarBroadcastFix.Candidate>()
    private var intervalRouteCamerasAtMs = 0L
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
    /** 正在进行的「前方多少米无测速」循环播报，null 表示没有。 */
    private var clearStretch: RadarClearStretch.Session? = null
    /** 上一次播报循环时的车速，用来按实际行驶扣减剩余空路。 */
    private var clearStretchSpeedKmh = 0f
    private var clearStretchUpdatedAtMs = 0L
    /** 已规划路线的行进判断，见 [PlannedRouteTracker]。 */
    private val routeTracker = PlannedRouteTracker()
    /** 「车正好开到雷达点」的判定，见 [RadarPassCrossing]。 */
    private val passCrossing = RadarPassCrossing()
    /** 精确落点定时器：到点就在雷达跟前响，而不是等高德下一次回调。 */
    private val passToneRunnable = object : Runnable {
        override fun run() {
            passToneAtMs = 0L
            fireAccuratePassTone()
        }
    }
    /** 上一次真正响提示音的那条雷达，一条只响一次。 */
    private var passToneFiredKey: String? = null
    /** 已经排上、还没到点的定时器时刻，0 表示没排。 */
    private var passToneAtMs = 0L
    /**
     * 空路播报的驱动定时器。不能等高德回调来驱动：一整段空路可能压根没有雷达回调，
     * 那样设了「5 公里无测速」也永远不会响。
     */
    private val clearStretchTick = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            advanceClearStretch(
                settings = RadarAlertSettingsStore.load(context),
                nowMs = System.currentTimeMillis(),
                camerasAhead = 0
            )
            mainHandler.postDelayed(this, CLEAR_STRETCH_TICK_MS)
        }
    }

    private val aimlessListener = object : AimlessModeListener {
        override fun onUpdateTrafficFacility(infos: Array<AMapNaviTrafficFacilityInfo>?) = Unit

        override fun onUpdateAimlessModeElecCameraInfo(cameraInfo: Array<AMapNaviTrafficFacilityInfo>?) {
            handleCameraInfo(cameraInfo.orEmpty())
            // 这条回调顺便告诉我们「前方是哪部雷达、雷达点在哪」。落点还是按 GPS 算，
            // 不能等它来触发：它下一次开口时车已经开过去几十米了。
            advancePassTone(System.currentTimeMillis())
        }

        override fun updateAimlessModeStatistics(aimLessModeStat: AimLessModeStat?) = Unit

        override fun updateAimlessModeCongestionInfo(aimLessModeCongestionInfo: AimLessModeCongestionInfo?) = Unit
    }

    private val parallelRoadListener = ParallelRoadListener { status ->
        onParallelRoad(status?.getmElevatedRoadStatusFlag() ?: RoadLayerAlertGate.ELEVATED_NONE)
    }

    private val naviListener = object : SimpleNaviListener() {
        override fun onLocationChange(location: AMapNaviLocation?) {
            if (location == null) return
            val now = System.currentTimeMillis()
            location.coord?.let { coord ->
                lastCarLatitude = coord.latitude
                lastCarLongitude = coord.longitude
            }
            lastCarBearing = location.bearing
            lastRoadBearing = location.roadBearing
            lastCarAccuracyMeters = location.accuracy
            lastCarFixAtMs = now
            lastCarGpsTimeMs = location.time ?: 0L
            // 有规划路线时，顺手判断一下现在跑在哪条上。判不出来就沿用上一条，
            // 等轨迹攒够再切——不拿单帧 GPS 乱跳。
            if (routeTracker.currentState() != null &&
                RadarBroadcastFix.isPosition(lastCarLatitude, lastCarLongitude)
            ) {
                routeTracker.onFix(RouteProgress.Fix(lastCarLatitude, lastCarLongitude))
            }
            val speed = location.speed
            if (speed > 0f) {
                // getSpeed() 是米/秒（和系统 Location 一致），必须换算，
                // 否则 ETA 会被放大 3.6 倍，20 秒阈值在一公里外就开始喊。
                naviSpeedKmh = RadarAlertPlanner.cruiseSpeedKmh(speed)
                naviSpeedAtMs = now
            }
            // 每帧 GPS 都重算「车离雷达还有多远」，把提示音排到真正到点那一刻。
            // 这一步必须在 speed 的早退之外：车停下时也要把定时器收掉。
            advancePassTone(now)
        }

        override fun updateCameraInfo(info: Array<out AMapNaviCameraInfo>?) {
            shownRouteCameras = routeStyleCameras(info.orEmpty().toList())
            shownRouteCamerasAtMs = System.currentTimeMillis()
        }

        override fun updateIntervalCameraInfo(
            startCameraInfo: AMapNaviCameraInfo?,
            endCameraInfo: AMapNaviCameraInfo?,
            status: Int
        ) {
            intervalRouteCameras = routeStyleCameras(listOf(startCameraInfo, endCameraInfo))
            intervalRouteCamerasAtMs = System.currentTimeMillis()
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
        if (!PrivacyConsent.isAgreed(context)) {
            // 用户还没同意隐私政策前不能初始化高德导航 SDK。
            state = state.copy(active = false, lastError = "未同意隐私政策，测速雷达暂不可用")
            return
        }
        var activeNavi: AMapNavi? = null
        try {
            ensureTts(context.applicationContext, settings)
            activeNavi = AMapNavi.getInstance(context.applicationContext)
            activeNavi?.setUseInnerVoice(false, true)
            clearCarFix()
            activeNavi?.addAMapNaviListener(naviListener)
            activeNavi?.addAimlessModeListener(aimlessListener)
            activeNavi?.addParallelRoadListener(parallelRoadListener)
            activeNavi?.startGPS(1_000L)
            activeNavi?.startAimlessMode(AimLessMode.CAMERA_DETECTED)
            navi = activeNavi
            resetRoadLayer(settings.unknownRoadLayerElevated)
            routeTracker.bind(PlannedRouteStore.load(context.applicationContext))
            clearStretch = null
            mainHandler.removeCallbacks(clearStretchTick)
            mainHandler.postDelayed(clearStretchTick, CLEAR_STRETCH_TICK_MS)
            val now = System.currentTimeMillis()
            activeCameraLock = null
            dataHealth = RadarDataHealthState(serviceStartedAtMs = now, lastDataAtMs = now)
            firedThresholdsByCamera.clear()
            etaAnchorByCamera.clear()
            passedCameraKeys.clear()
            cameraFixByKey.clear()
            reportedRadarByKey.clear()
            heldFixByKey.clear()
            clearPassTone()
            clearRouteCameras()
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
        mainHandler.removeCallbacks(clearStretchTick)
        clearStretch = null
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
        ttsFailed = false
        pendingText = null
        activeCameraLock = null
        clearCarFix()
        dataHealth = RadarDataHealthState(serviceStartedAtMs = 0L, lastDataAtMs = 0L)
        firedThresholdsByCamera.clear()
        etaAnchorByCamera.clear()
        passedCameraKeys.clear()
        cameraFixByKey.clear()
        heldFixByKey.clear()
        clearPassTone()
        clearRouteCameras()
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

    /** 启停时把精确落点的状态全部清干净，不把上一条雷达的「响过了」带过来。 */
    private fun clearPassTone() {
        cancelPassToneTick()
        mainHandler.removeCallbacks(passToneRunnable)
        passToneFiredKey = null
        passCrossing.reset()
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

    /**
     * 留档开关。debug 版默认开着，正式版默认关。
     * 开着时每次预警往 app 私有目录写一条，见 [RadarAlertTrace]。
     */
    fun setTraceEnabled(enabled: Boolean) {
        traceEnabled = enabled
    }

    fun isTraceEnabled(): Boolean = traceEnabled

    /** 清空留档。不想带着上一次的记录开始新一轮时用。 */
    fun clearTrace(context: Context) = RadarAlertTrace.clear(context)

    /**
     * 用户在查路线页换了目的地，重新读一次规划路线。
     * 不重启雷达服务也能立刻用上新路线——否则要等下一次服务启动才生效。
     */
    fun reloadPlannedRoute(context: Context) {
        routeTracker.bind(PlannedRouteStore.load(context.applicationContext))
    }

    /** 当前跑在规划路线的第几条、下一个雷达还有多远，给界面显示用。 */
    fun plannedRouteSummary(): String? {
        val route = routeTracker.currentRoute() ?: return null
        val next = routeTracker.nextRadarMeters()
        val distance = if (next == null) "之后没有测速" else "下一个 ${RadarAlertPlanner.distanceText(next)}"
        return "按规划路线：${route.label.ifBlank { "路线 ${route.id}" }} · $distance"
    }

    fun traceSummary(context: Context): String = RadarAlertTrace.summary(context)

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
                ttsFailed = true
                state = state.copy(lastError = "系统 TTS 初始化失败，改用提示音")
                // TTS 初始化要几百毫秒，这期间攒下的播报要补一声提示音，
                // 否则那一次提醒就彻底没了，用户看到的就是「漏报」。
                if (pendingText != null) {
                    pendingText = null
                    mainHandler.post { playAlertTone() }
                }
            }
        }
    }

    private fun handleCameraInfo(cameraInfo: Array<out AMapNaviTrafficFacilityInfo>) {
        val settings = appContext?.let { RadarAlertSettingsStore.load(it) } ?: return
        if (!settings.enabled || !state.active) return
        val now = System.currentTimeMillis()
        dataHealth = dataHealth.copy(lastDataAtMs = now)
        state = state.copy(lastRadarDataAtMs = now)
        val placed = placeCameras(cameraInfo, now)
        rememberSpeedCameras(placed, now)
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
        val cameras = placed.mapNotNull { item ->
            if (item.key in passedCameraKeys) return@mapNotNull null
            val info = item.info
            RadarCameraSnapshot(
                key = item.key,
                distanceMeters = info.getDistance(),
                cameraType = info.getBroadcastType(),
                speedLimit = info.getLimitSpeed().takeIf { it > 0 },
                latitude = item.held.latitude,
                longitude = item.held.longitude
            )
        }
        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = cameras,
            currentLock = activeCameraLock,
            nowMs = now,
            speedKmh = speedKmh,
            settings = settings
        )
        val previousLock = activeCameraLock
        activeCameraLock = selection.lock
        val passedKey = selection.passedCameraKey
        if (passedKey != null && passedCameraKeys.add(passedKey)) {
            val passedCamera = cameras.firstOrNull { it.key == passedKey }
            val rememberedFix = cameraFixByKey[passedKey]
            logRadarAlert(
                kind = "经过",
                speech = "提示音",
                text = "",
                nowMs = now,
                thresholdSeconds = null,
                leadSeconds = settings.leadSeconds,
                rawEtaSeconds = null,
                etaSeconds = null,
                speedKmh = speedKmh,
                amapDistanceMeters = passedCamera?.distanceMeters ?: previousLock?.lastDistanceMeters,
                radarLatitude = passedCamera?.latitude ?: rememberedFix?.first ?: Double.NaN,
                radarLongitude = passedCamera?.longitude ?: rememberedFix?.second ?: Double.NaN,
                reportedRadarLatitude = reportedRadarByKey[passedKey]?.first ?: Double.NaN,
                reportedRadarLongitude = reportedRadarByKey[passedKey]?.second ?: Double.NaN,
                cameraType = passedCamera?.cameraType ?: -1,
                speedLimit = passedCamera?.speedLimit,
                road = road,
                cameraKey = passedKey,
                coordinateFix = heldFixByKey[passedKey]?.source ?: RadarBroadcastFix.SOURCE_CRUISE
            )
            // 提示音本来要在这里响，但高德认「经过」的时候车已经开过去几十米了，
            // 听感就是「响的地方不是雷达的地方」。所以正常情况已经由 GPS 定时器在雷达跟前响过，
            // 这里只补一刀：GPS 判不出（没定位/刚启动）时才退回来，免得一次都不响。
            if (passToneFiredKey != passedKey) {
                playPassTone()
            }
            cancelPassToneTick()
            passCrossing.reset()
            // 刚过去的那部要从候选里排除，看真正的「下一个」有多远。
            // 有规划路线时优先用路线上的下一个雷达：它算的是沿路距离，
            // 而且知道整条路上有几个，比只看巡航回调这一个准。
            val nextOnRoute = routeTracker.nextRadarMeters()
            val nextRadarMeters = nextOnRoute ?: cameras
                .filter { it.key != passedKey }
                .minOfOrNull { it.distanceMeters }
            startClearStretch(settings, now, nextRadarMeters)
            return
        }
        // 前方又冒出一部测速雷达：不管是不是刚锁上的，空路都到头了，循环播报停掉。
        if (cameras.isNotEmpty()) clearStretch = null
        val nearest = selection.camera ?: return

        val distance = nearest.distanceMeters
        val rawEtaSeconds = RadarAlertPlanner.etaSeconds(distance, speedKmh) ?: return
        val reconciled = RadarAlertPlanner.reconcileEta(etaAnchorByCamera[nearest.key], rawEtaSeconds, now)
        val etaSeconds = reconciled.etaSeconds
        etaAnchorByCamera[nearest.key] = reconciled.anchor
        val fired = firedThresholdsByCamera[nearest.key].orEmpty()
        if (RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(nearest, fired)) {
            val text = withRoadLayer(layerPrefix, RadarAlertPlanner.farUnknownLimitAlertText(distance))
            val speech = describeSpeech(now)
            val spoke = speak(text, now)
            logRadarAlert(
                kind = "提前",
                speech = speech,
                text = text,
                nowMs = now,
                thresholdSeconds = null,
                leadSeconds = settings.leadSeconds,
                rawEtaSeconds = rawEtaSeconds,
                etaSeconds = etaSeconds,
                speedKmh = speedKmh,
                amapDistanceMeters = distance,
                radarLatitude = nearest.latitude,
                radarLongitude = nearest.longitude,
                reportedRadarLatitude = reportedRadarByKey[nearest.key]?.first ?: Double.NaN,
                reportedRadarLongitude = reportedRadarByKey[nearest.key]?.second ?: Double.NaN,
                cameraType = nearest.cameraType,
                speedLimit = nearest.speedLimit,
                road = road,
                cameraKey = nearest.key,
                coordinateFix = heldFixByKey[nearest.key]?.source ?: RadarBroadcastFix.SOURCE_CRUISE
            )
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
        val speech = describeSpeech(now)
        val spoke = speak(text, now)
        logRadarAlert(
            kind = "预警",
            speech = speech,
            text = text,
            nowMs = now,
            thresholdSeconds = threshold,
            leadSeconds = settings.leadSeconds,
            rawEtaSeconds = rawEtaSeconds,
            etaSeconds = etaSeconds,
            speedKmh = speedKmh,
            amapDistanceMeters = distance,
            radarLatitude = nearest.latitude,
            radarLongitude = nearest.longitude,
            reportedRadarLatitude = reportedRadarByKey[nearest.key]?.first ?: Double.NaN,
            reportedRadarLongitude = reportedRadarByKey[nearest.key]?.second ?: Double.NaN,
            cameraType = nearest.cameraType,
            speedLimit = speedLimit,
            road = road,
            cameraKey = nearest.key,
            coordinateFix = heldFixByKey[nearest.key]?.source ?: RadarBroadcastFix.SOURCE_CRUISE
        )
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
        return RadarAlertPlanner.cameraKey(
            longitude = info.getCoorX(),
            latitude = info.getCoorY(),
            broadcastType = info.getBroadcastType(),
            speedLimit = info.getLimitSpeed()
        )
    }

    /**
     * 刚经过一个雷达，看看后面这一段值不值得开循环播报。
     *
     * 判据是「到下一个雷达还有多远」：超过设定跨度就说明这一段够长，值得报。
     * 后面压根没查到雷达时按满额算——那正是「连续 N 公里没有测速」的情形。
     *
     * @param nextRadarMeters 本次回调里除刚经过那部以外，前方还有没有雷达、最近的多远。
     */
    private fun startClearStretch(
        settings: RadarAlertSettings,
        nowMs: Long,
        nextRadarMeters: Int?
    ) {
        val config = RadarClearStretch.Settings(
            enabled = settings.clearStretchEnabled,
            spanMeters = settings.clearStretchSpanMeters,
            intervalSeconds = settings.clearStretchIntervalSeconds,
            referenceSpeedKmh = settings.clearStretchReferenceSpeedKmh
        )
        if (!RadarClearStretch.shouldStart(nextRadarMeters, config)) return
        clearStretchSpeedKmh = latestSpeedKmh
        clearStretchUpdatedAtMs = nowMs
        // 有规划路线且知道下一个雷达在哪时，空路的真实尽头是路线上的下一个雷达，
        // 不是拍脑袋的设定跨度。这样「还剩 4.3 公里没测速」是真的 4.3 公里。
        val spanMeters = nextRadarMeters
            ?.takeIf { it in 0..config.spanMeters }
            ?: config.spanMeters
        clearStretch = RadarClearStretch.Session(
            startedAtMs = nowMs,
            spanMeters = spanMeters,
            lastSpokenAtMs = 0L,
            lastSpokenMeters = spanMeters
        )
    }

    /**
     * 推进循环播报。挂在独立定时器上，不依赖高德回调——
     * 一段 5 公里的空路大概率压根不会有雷达回调，等回调来驱动就永远播不出去。
     */
    private fun advanceClearStretch(
        settings: RadarAlertSettings,
        nowMs: Long,
        camerasAhead: Int
    ) {
        val session = clearStretch ?: return
        val config = RadarClearStretch.Settings(
            enabled = settings.clearStretchEnabled,
            spanMeters = settings.clearStretchSpanMeters,
            intervalSeconds = settings.clearStretchIntervalSeconds,
            referenceSpeedKmh = settings.clearStretchReferenceSpeedKmh
        )
        if (!config.enabled || camerasAhead > 0) {
            clearStretch = null
            return
        }
        // 按速度×时间扣减：空路段没有雷达回调可等，用时间推是唯一稳的办法。
        val elapsedMs = (nowMs - clearStretchUpdatedAtMs).coerceAtLeast(0L)
        val travelled = clearStretchSpeedKmh / 3.6f * (elapsedMs / 1000f)
        clearStretchUpdatedAtMs = nowMs
        clearStretchSpeedKmh = latestSpeedKmh
        val remaining = (session.lastSpokenMeters - travelled.toInt()).coerceAtLeast(0)
        if (RadarClearStretch.shouldStop(session, remaining, nowMs)) {
            clearStretch = null
            return
        }
        if (!RadarClearStretch.shouldSpeakNow(session, remaining, nowMs, config)) return
        speak(RadarClearStretch.text(remaining), nowMs)
        clearStretch = session.copy(lastSpokenAtMs = nowMs, lastSpokenMeters = remaining)
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

    private fun describeSpeech(nowMs: Long): String {
        if (nowMs - state.lastSpokenAtMs < SPEAK_MIN_GAP_MS) return "间隔跳过"
        if (ttsFailed) return "提示音"
        if (!ttsReady) return "排队"
        return "已播"
    }

    private fun clearCarFix() {
        naviSpeedKmh = 0f
        naviSpeedAtMs = 0L
        lastCarLatitude = Double.NaN
        lastCarLongitude = Double.NaN
        lastCarBearing = Float.NaN
        lastRoadBearing = Float.NaN
        lastCarAccuracyMeters = Float.NaN
        lastCarFixAtMs = 0L
        lastCarGpsTimeMs = 0L
    }

    private fun logRadarAlert(
        kind: String,
        speech: String,
        text: String,
        nowMs: Long,
        thresholdSeconds: Int?,
        leadSeconds: Int,
        rawEtaSeconds: Int?,
        etaSeconds: Int?,
        speedKmh: Float,
        amapDistanceMeters: Int?,
        radarLatitude: Double,
        radarLongitude: Double,
        /** 高德原本给的电子眼坐标。留档要和我们用过的点一起写下来，否则没法判断是不是我们改偏了。 */
        reportedRadarLatitude: Double = Double.NaN,
        reportedRadarLongitude: Double = Double.NaN,
        cameraType: Int,
        speedLimit: Int?,
        road: RoadSnapshot,
        cameraKey: String,
        coordinateFix: String
    ) {
        val naviAgeMs = if (naviSpeedAtMs > 0L) nowMs - naviSpeedAtMs else -1L
        val speedSource = if (
            naviSpeedKmh >= RadarAlertPlanner.MIN_SPEED_FOR_ETA_KMH &&
            naviSpeedAtMs > 0L &&
            naviAgeMs in 0..RadarAlertPlanner.NAVI_SPEED_FRESH_MS
        ) {
            "navi"
        } else {
            "gps"
        }
        val event = RadarAlertLog.Event(
                    kind = kind,
                    speech = speech,
                    text = text,
                    timeMs = nowMs,
                    gpsTimeMs = lastCarGpsTimeMs,
                    thresholdSeconds = thresholdSeconds,
                    leadSeconds = leadSeconds,
                    rawEtaSeconds = rawEtaSeconds,
                    etaSeconds = etaSeconds,
                    speedKmh = speedKmh,
                    speedSource = speedSource,
                    naviSpeedAgeMs = naviAgeMs,
                    gpsSpeedKmh = latestSpeedKmh,
                    amapDistanceMeters = amapDistanceMeters,
                    carLatitude = lastCarLatitude,
                    carLongitude = lastCarLongitude,
                    carBearing = lastCarBearing,
                    roadBearing = lastRoadBearing,
                    carAccuracyMeters = lastCarAccuracyMeters,
                    carAgeMs = if (lastCarFixAtMs > 0L) nowMs - lastCarFixAtMs else -1L,
                    radarLatitude = radarLatitude,
                    radarLongitude = radarLongitude,
                    reportedRadarLatitude = reportedRadarLatitude,
                    reportedRadarLongitude = reportedRadarLongitude,
                    cameraType = cameraType,
                    speedLimit = speedLimit,
                    roadLayer = RouteRadarMarks.layer(tunnel = road.tunnel, elevated = road.alertElevated),
                    usingDefault = road.usingDefault,
                    correctedByDivergence = road.corrected,
                    cameraKey = cameraKey,
                    coordinateFix = coordinateFix
                )
        Log.i(RadarAlertLog.TAG, RadarAlertLog.line(event))
        if (traceEnabled) {
            appContext?.let { RadarAlertTrace.record(it, event) }
        }
    }

    /**
     * 用最新一帧 GPS 重算「车离锁定雷达还有多远」，把提示音排到真正到点那一刻。
     *
     * 高德的巡航回调只负责告诉我们「前方有哪部雷达、雷达点在哪」，
     * 什么时候响由这里按位置决定：车开到雷达点才响，早一点晚一点都不行。
     */
    private fun advancePassTone(nowMs: Long) {
        val context = appContext ?: return
        if (!state.active || !RadarAlertSettingsStore.load(context).enabled) {
            cancelPassToneTick()
            passCrossing.reset()
            return
        }
        val lock = activeCameraLock
        val fix = lock?.let { cameraFixByKey[it.key] }
        if (lock == null || fix == null ||
            !RadarBroadcastFix.isPosition(lastCarLatitude, lastCarLongitude)
        ) {
            cancelPassToneTick()
            passCrossing.reset()
            return
        }
        if (passToneFiredKey == lock.key) {
            // 这条已经响过了，等它被换掉或者确认经过再管。
            cancelPassToneTick()
            return
        }
        val plan = passCrossing.onFix(
            key = lock.key,
            carLatitude = lastCarLatitude,
            carLongitude = lastCarLongitude,
            bearingDegrees = RadarBroadcastFix.bearing(lastRoadBearing, lastCarBearing),
            speedKmh = RadarAlertPlanner.etaSpeedKmh(
                naviSpeedKmh = naviSpeedKmh,
                naviSpeedAtMs = naviSpeedAtMs,
                vehicleSpeedKmh = latestSpeedKmh,
                nowMs = nowMs
            ),
            fixAgeMs = nowMs - lastCarFixAtMs,
            radarLatitude = fix.first,
            radarLongitude = fix.second
        )
        if (plan.fireNow) {
            fireAccuratePassTone()
            return
        }
        val delayMs = plan.delayMs
        if (delayMs == null) {
            // 还太远，等下一帧重新算；顺便把之前排的作废，免得按旧车速在错的地方响。
            cancelPassToneTick()
            return
        }
        schedulePassToneTick(nowMs + delayMs)
    }

    /**
     * 排定时器。下一帧 GPS 会覆盖它，所以这里只保留最近的那一次；
     * 同样的时刻不重复排，免得每秒 GPS 都往后推一点点、永远等不到。
     */
    private fun schedulePassToneTick(atMs: Long) {
        if (passToneAtMs == atMs) return
        cancelPassToneTick()
        passToneAtMs = atMs
        val delayMs = (atMs - System.currentTimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(passToneRunnable, delayMs)
    }

    private fun cancelPassToneTick() {
        if (passToneAtMs == 0L) return
        mainHandler.removeCallbacks(passToneRunnable)
        passToneAtMs = 0L
    }

    /** 到点了：在雷达跟前响一声，记下来别重复。 */
    private fun fireAccuratePassTone() {
        val lock = activeCameraLock ?: return
        if (passToneFiredKey == lock.key) return
        val context = appContext ?: return
        passToneFiredKey = lock.key
        cancelPassToneTick()
        val fix = cameraFixByKey[lock.key]
        logRadarAlert(
            kind = "经过",
            speech = "提示音",
            text = "",
            nowMs = System.currentTimeMillis(),
            thresholdSeconds = null,
            leadSeconds = RadarAlertSettingsStore.load(context).leadSeconds,
            rawEtaSeconds = null,
            etaSeconds = null,
            speedKmh = latestSpeedKmh,
            amapDistanceMeters = lock.lastDistanceMeters,
            radarLatitude = fix?.first ?: Double.NaN,
            radarLongitude = fix?.second ?: Double.NaN,
            reportedRadarLatitude = reportedRadarByKey[lock.key]?.first ?: Double.NaN,
            reportedRadarLongitude = reportedRadarByKey[lock.key]?.second ?: Double.NaN,
            cameraType = -1,
            speedLimit = null,
            road = roadSnapshot(),
            cameraKey = lock.key,
            coordinateFix = heldFixByKey[lock.key]?.source ?: RadarBroadcastFix.SOURCE_CRUISE
        )
        playPassTone()
    }

    private fun speak(text: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs - state.lastSpokenAtMs < SPEAK_MIN_GAP_MS) return false
        if (ttsFailed) {
            // 没有可用的语音引擎时至少响提示音，不能把提醒当作已播报后悄悄丢掉。
            playAlertTone()
            return true
        }
        if (!ttsReady) {
            // 引擎还在初始化：先排队，初始化成功后播出，失败则改响提示音。
            pendingText = text
            return true
        }
        speakNow(text)
        return true
    }

    private fun playAlertTone() {
        val ducked = appContext?.let { AppPlaybackDuck.acquire(it) }
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        if (tone == null) {
            ducked?.release()
            return
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
        mainHandler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }, 320L)
        mainHandler.postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) }, 640L)
        mainHandler.postDelayed({
            tone.release()
            ducked?.release()
        }, 1_000L)
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

    private data class PlacedCamera(
        val info: AMapNaviTrafficFacilityInfo,
        val key: String,
        val held: RadarBroadcastFix.Held,
        /** 高德原始上报坐标，留档专用：和 held 对比能看出我们改了多少。 */
        val reportedLatitude: Double,
        val reportedLongitude: Double
    )

    /**
     * 查路线页用的是导航算路电子眼。巡航设施坐标经常偏到路外面，
     * 这里先对上同一套电子眼；对不上剩余距离时，沿当前道路把这段距离量出去。
     */
    private fun placeCameras(
        cameraInfo: Array<out AMapNaviTrafficFacilityInfo>,
        nowMs: Long
    ): List<PlacedCamera> {
        val candidates = freshRouteCameras(nowMs)
        return cameraInfo.mapNotNull { info ->
            if (info.getDistance() <= 0 || !RadarAlertPlanner.isSpeedCamera(info.getBroadcastType())) {
                return@mapNotNull null
            }
            val key = cameraKey(info)
            val limit = info.getLimitSpeed().takeIf { it > 0 }
            val matched = RadarBroadcastFix.matchCandidate(
                candidates = candidates,
                ageMs = 0L,
                cameraType = info.getBroadcastType(),
                speedLimit = limit,
                distanceMeters = info.getDistance()
            )
            // 高德这一帧上报的原点。留档要存它：事后发现「播报点和现场雷达差得远」时，
            // 得能分清是它给错了，还是我们沿道路修正时改偏了。
            val reportedLat = matched?.latitude ?: info.getCoorY()
            val reportedLon = matched?.longitude ?: info.getCoorX()
            val fix = RadarBroadcastFix.resolve(
                carLatitude = lastCarLatitude,
                carLongitude = lastCarLongitude,
                bearingDegrees = RadarBroadcastFix.bearing(lastRoadBearing, lastCarBearing),
                distanceMeters = info.getDistance(),
                reportedLatitude = reportedLat,
                reportedLongitude = reportedLon,
                reportedSource = if (matched != null) {
                    RadarBroadcastFix.SOURCE_ROUTE
                } else {
                    RadarBroadcastFix.SOURCE_CRUISE
                }
            )
            val held = RadarBroadcastFix.hold(heldFixByKey[key], fix, info.getDistance())
            heldFixByKey[key] = held
            if (RadarBroadcastFix.isPosition(held.latitude, held.longitude)) {
                cameraFixByKey[key] = held.latitude to held.longitude
            }
            // 留档：把高德这一帧上报的原始点也存下来。
            // 事后发现「播报点和现场雷达差得远」时，要能分清是它给错了，还是我们修正错了。
            if (RadarBroadcastFix.isPosition(reportedLat, reportedLon)) {
                reportedRadarByKey[key] = reportedLat to reportedLon
            }
            PlacedCamera(
                info = info,
                key = key,
                held = held,
                reportedLatitude = info.getCoorY(),
                reportedLongitude = info.getCoorX()
            )
        }
    }

    private fun freshRouteCameras(nowMs: Long): List<RadarBroadcastFix.Candidate> {
        val cameras = mutableListOf<RadarBroadcastFix.Candidate>()
        if (nowMs - shownRouteCamerasAtMs in 0..ROUTE_CAMERA_FRESH_MS) cameras += shownRouteCameras
        if (nowMs - intervalRouteCamerasAtMs in 0..ROUTE_CAMERA_FRESH_MS) cameras += intervalRouteCameras
        return cameras
    }

    private fun routeStyleCameras(cameras: List<AMapNaviCameraInfo?>): List<RadarBroadcastFix.Candidate> {
        return cameras.mapNotNull { camera ->
            if (camera == null || camera.cameraDistance <= 0) return@mapNotNull null
            // 这里的类型是算路电子眼：0 测速、10 流动测速、8/9 区间。4 在这套编号里是公交车道，不能当成测速。
            val fromRoute = RouteRadarMarks.fromRouteCamera(
                routeCameraType = camera.cameraType,
                speedKmh = camera.cameraSpeed,
                latitude = camera.y,
                longitude = camera.x,
                elapsedMs = 0L
            ) ?: return@mapNotNull null
            RadarBroadcastFix.Candidate(
                latitude = fromRoute.latitude,
                longitude = fromRoute.longitude,
                distanceMeters = camera.cameraDistance,
                cameraType = fromRoute.cameraType,
                speedLimit = fromRoute.speedLimit?.takeIf { it > 0 }
            )
        }
    }

    private fun clearRouteCameras() {
        shownRouteCameras = emptyList()
        shownRouteCamerasAtMs = 0L
        intervalRouteCameras = emptyList()
        intervalRouteCamerasAtMs = 0L
    }

    private fun rememberSpeedCameras(placed: List<PlacedCamera>, nowMs: Long) {
        val road = roadSnapshot()
        val layer = RouteRadarMarks.layer(tunnel = road.tunnel, elevated = road.alertElevated)
        placed.forEach { item ->
            val held = item.held
            if (!RadarBroadcastFix.isPosition(held.latitude, held.longitude)) return@forEach
            val info = item.info
            val incoming = RadarSighting(
                timeMs = nowMs,
                latitude = held.latitude,
                longitude = held.longitude,
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

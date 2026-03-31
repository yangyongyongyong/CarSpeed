package com.thomas.carspeed

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class OverlayService : Service(), SensorEventListener, LocationListener {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var overlayPanel: FrameLayout? = null
    private var overlayContent: LinearLayout? = null
    private var overlayBubble: TextView? = null
    private var dividerView: View? = null

    private var tvMaxSpeedTime: TextView? = null
    private var tvMaxSpeedValue: TextView? = null
    private var tvCurrentSpeedValue: TextView? = null
    private var tvAccelValue: TextView? = null
    private var tvHeadingValue: TextView? = null
    private var tvOverlayTitle: TextView? = null
    private var tvMaxSpeedLabel: TextView? = null
    private var tvMaxSpeedUnit: TextView? = null
    private var tvCurrentSpeedLabel: TextView? = null
    private var tvCurrentSpeedUnit: TextView? = null
    private var tvGpsSatelliteCount: TextView? = null
    private var tvTrendLabel: TextView? = null
    private var tvWaitLabel: TextView? = null
    private var tvTripDurationLabel: TextView? = null
    private var btnClose: TextView? = null
    private var tvDragHandle: TextView? = null
    private var btnScaleDown: TextView? = null
    private var btnScaleUp: TextView? = null
    private var speedTrendView: SpeedTrendView? = null
    private var layoutDetailSection: LinearLayout? = null
    private var rowTopBar: LinearLayout? = null
    private var rowSpeedCards: LinearLayout? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var linearAccelerometer: Sensor? = null

    private var locationManager: LocationManager? = null

    private val gravityValues = FloatArray(3)
    private val geomagneticValues = FloatArray(3)

    private var accelDisplayMps2 = 0f
    private var headingDegrees: Float? = null
    private var speedMps = 0f
    private var speedFilterState = SpeedRules.SpeedFilterState()
    private var reacquireWarmupRemaining = 0
    private var maxSpeedKmh = 0f
    private var maxSpeedTimeMs = 0L
    private var lastLocation: Location? = null
    private var lastLocationTimeMs: Long = 0L
    private var lastElapsedRealtimeNs: Long = 0L
    private var lastReceiveRealtimeNs: Long = 0L
    private var lastGpsFixWallTimeMs: Long = 0L
    private var gpsSignalLost: Boolean = true
    private var lastSpeedForAccelMps: Float = 0f
    private var lastSpeedForAccelNs: Long = 0L

    private var overlayScale = 1f
    private var collapsed = false
    private val speedHistory = ArrayDeque<SpeedTrendView.SpeedPoint>()
    private val historyWindowMs = 60_000L

    private var tripStarted = false
    private var tripStartTimeMs = 0L
    private var waitAtZeroAccumMs = 0L
    private var waitAtZeroStartMs = 0L

    private val maxSpeedTimeFormatter = SimpleDateFormat("yyyyMMdd HH:mm:ss", Locale.getDefault())
    private var transparentTextMode = false
    private var currentEstimateActive = false
    private var gpsUsedSatelliteCount: Int? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        linearAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        createOverlay()
        registerSensors()
        requestLocationUpdates()
        registerGnssStatusUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishTripIfNeededAndSave()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_UPDATE_STYLE -> {
                applyOverlayStyle()
                refreshOverlay()
                return START_STICKY
            }


            ACTION_START, null -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        locationManager?.removeUpdates(this)
        unregisterGnssStatusUpdates()
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_sensor_v3a, null)

        overlayPanel = view.findViewById(R.id.overlayPanel)
        overlayContent = view.findViewById(R.id.overlayContent)
        overlayBubble = view.findViewById(R.id.overlayBubble)
        dividerView = view.findViewById(R.id.vDivider)

        tvMaxSpeedTime = view.findViewById(R.id.tvMaxSpeedTime)
        tvMaxSpeedValue = view.findViewById(R.id.tvMaxSpeedValue)
        tvCurrentSpeedValue = view.findViewById(R.id.tvCurrentSpeedValue)
        tvAccelValue = view.findViewById(R.id.tvAccelValue)
        tvHeadingValue = view.findViewById(R.id.tvHeadingValue)
        tvOverlayTitle = view.findViewById(R.id.tvOverlayTitle)
        tvMaxSpeedLabel = view.findViewById(R.id.tvMaxSpeedLabel)
        tvMaxSpeedUnit = view.findViewById(R.id.tvMaxSpeedUnit)
        tvCurrentSpeedLabel = view.findViewById(R.id.tvCurrentSpeedLabel)
        tvCurrentSpeedUnit = view.findViewById(R.id.tvCurrentSpeedUnit)
        tvGpsSatelliteCount = view.findViewById(R.id.tvGpsSatelliteCount)
        tvTrendLabel = view.findViewById(R.id.tvTrendLabel)
        tvWaitLabel = view.findViewById(R.id.tvWaitLabel)
        tvTripDurationLabel = view.findViewById(R.id.tvTripDurationLabel)
        btnClose = view.findViewById(R.id.tvClose)
        tvDragHandle = view.findViewById(R.id.tvDragHandle)
        btnScaleDown = view.findViewById(R.id.btnScaleDown)
        btnScaleUp = view.findViewById(R.id.btnScaleUp)
        speedTrendView = view.findViewById(R.id.speedTrendView)
        layoutDetailSection = view.findViewById(R.id.layoutDetailSection)
        rowTopBar = view.findViewById(R.id.rowTopBar)
        rowSpeedCards = view.findViewById(R.id.rowSpeedCards)

        btnScaleDown?.visibility = View.GONE
        btnScaleUp?.visibility = View.GONE


        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }

        overlayParams = params
        attachDragBehavior(params)
        attachCloseButton()

        windowManager.addView(view, params)
        overlayView = view

        applyOverlayScale()
        applyOverlayStyle()
        refreshOverlay()
    }

    private fun attachCloseButton() {
        btnClose?.setOnClickListener {
            finishTripIfNeededAndSave()
            stopSelf()
        }
    }

    private fun finishTripIfNeededAndSave() {
        if (!tripStarted) return
        val end = System.currentTimeMillis()
        val start = tripStartTimeMs
        if (start > 0L && end > start) {
            DriveRecordStore.addRecord(
                this,
                DriveRecord(
                    startTimeMs = start,
                    endTimeMs = end,
                    durationMs = end - start,
                    maxSpeedKmh = maxSpeedKmh
                )
            )
        }
        tripStarted = false
        tripStartTimeMs = 0L
        waitAtZeroAccumMs = 0L
        waitAtZeroStartMs = 0L
    }

    private fun applyOverlayStyle() {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        val alphaPercent = prefs.getInt(MainActivity.PREF_BG_ALPHA_PERCENT, 0).coerceIn(0, 100)
        val alpha = ((1f - alphaPercent / 100f) * 255).toInt()
        val bg = GradientDrawable().apply {
            cornerRadius = dpToPx(18f).toFloat()
            setColor(Color.argb(alpha, 0, 0, 0))
        }
        overlayContent?.background = bg

        val dividerAlpha = (alpha * 0.7f).toInt()
        dividerView?.setBackgroundColor((dividerAlpha shl 24) or 0x00D5E5FF)

        // 同步调整卡片/按钮等背景透明度，避免看起来“滑动条无效”
        applyBackgroundAlphaRecursively(overlayContent, alpha)
        applyBackgroundAlphaToView(overlayBubble, alpha)

        val useDarkText = alphaPercent >= 100
        if (useDarkText != transparentTextMode) {
            transparentTextMode = useDarkText
            applyDynamicTextColors(useDarkText)
        }
    }

    private fun applyOverlayScale() {
        val params = overlayParams ?: return
        if (collapsed) return

        overlayPanel?.layoutParams = overlayPanel?.layoutParams?.apply {
            width = dpToPx(340f * overlayScale)
        }

        overlayContent?.setPadding(
            dpToPx(14f * overlayScale),
            dpToPx(14f * overlayScale),
            dpToPx(14f * overlayScale),
            dpToPx(14f * overlayScale)
        )

        btnClose?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * overlayScale)
        tvMaxSpeedTime?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * overlayScale)
        tvMaxSpeedValue?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f * overlayScale)
        applyCurrentSpeedTextStyle(gpsSignalLost)
        tvGpsSatelliteCount?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * overlayScale)
        tvAccelValue?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * overlayScale)
        tvHeadingValue?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * overlayScale)

        speedTrendView?.layoutParams = speedTrendView?.layoutParams?.apply {
            height = dpToPx(126f * overlayScale)
        }

        // 缩放到较小尺寸时，仅保留第一行（最高速度 + 当前速度）
        val compact = overlayScale <= COMPACT_ROW_ONLY_SCALE
        layoutDetailSection?.visibility = if (compact) View.GONE else View.VISIBLE
        rowTopBar?.visibility = if (compact) View.GONE else View.VISIBLE
        rowSpeedCards?.layoutParams = rowSpeedCards?.layoutParams?.apply {
            if (this is android.view.ViewGroup.MarginLayoutParams) {
                topMargin = if (compact) 0 else dpToPx(10f)
            }
        }

        overlayView?.requestLayout()
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        overlayView?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun attachDragBehavior(params: WindowManager.LayoutParams) {
        fun bindDrag(
            target: View?,
            canCollapseOnUp: Boolean,
            canExpandOnTap: Boolean,
            enableResizeFromLeftBottom: Boolean
        ) {
            if (target == null) return
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var moved = false
            var resizing = false
            var startScale = overlayScale
            var liveScale = overlayScale
            var startScaleTouchX = 0f
            var startScaleTouchY = 0f
            val touchSlop = dpToPx(6f)

            target.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false

                        if (enableResizeFromLeftBottom && !collapsed) {
                            val hot = dpToPx(56f).toFloat()
                            val h = v.height.toFloat().coerceAtLeast(1f)
                            val inLeftBottomHotZone = event.x <= hot && event.y >= (h - hot)
                            if (inLeftBottomHotZone) {
                                resizing = true
                                startScale = overlayScale
                                liveScale = overlayScale
                                startScaleTouchX = event.rawX
                                startScaleTouchY = event.rawY
                                return@setOnTouchListener true
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (resizing) {
                            // 新方案：拖动中用变换预览，松手再一次性落地，避免每帧重排导致卡顿
                            val dx = startScaleTouchX - event.rawX
                            val dy = event.rawY - startScaleTouchY
                            val maxScale = calculateMaxScaleForBounds(params)
                            val targetScale = (startScale + (dx + dy) / 560f).coerceIn(0.70f, maxScale)
                            liveScale = (liveScale * 0.72f + targetScale * 0.28f).coerceIn(0.70f, maxScale)

                            val base = overlayScale.coerceAtLeast(0.01f)
                            val ratio = (liveScale / base).coerceIn(0.5f, 2.2f)
                            overlayPanel?.apply {
                                // 固定右上角，左下角拖动时仅左下方向变化
                                pivotX = width.toFloat().coerceAtLeast(1f)
                                pivotY = 0f
                                scaleX = ratio
                                scaleY = ratio
                            }
                            moved = true
                            return@setOnTouchListener true
                        }

                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        overlayView?.let { windowManager.updateViewLayout(it, params) }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (resizing) {
                            overlayPanel?.scaleX = 1f
                            overlayPanel?.scaleY = 1f

                            val oldScale = overlayScale
                            val maxScale = calculateMaxScaleForBounds(params)
                            val newScale = liveScale.coerceIn(0.70f, maxScale)
                            val oldWidth = dpToPx(340f * oldScale)
                            val newWidth = dpToPx(340f * newScale)

                            overlayScale = newScale
                            applyOverlayScale()

                            // 缩放后修正 x：保持“右上角”在屏幕中的位置不变
                            params.x += (oldWidth - newWidth)
                            clampOverlayPositionToScreen(params)
                            overlayView?.let { windowManager.updateViewLayout(it, params) }

                            resizing = false
                            return@setOnTouchListener true
                        }
                        if (!moved && canExpandOnTap && collapsed) {
                            expandPanel()
                            return@setOnTouchListener true
                        }
                        if (canCollapseOnUp && !collapsed) {
                            maybeCollapseToEdge()
                        }
                        true
                    }

                    else -> false
                }
            }
        }

        bindDrag(
            target = overlayPanel,
            canCollapseOnUp = true,
            canExpandOnTap = false,
            enableResizeFromLeftBottom = true
        )
        bindDrag(
            target = overlayBubble,
            canCollapseOnUp = false,
            canExpandOnTap = true,
            enableResizeFromLeftBottom = false
        )
    }

    private fun maybeCollapseToEdge() {
        val params = overlayParams ?: return

        val dm = resources.displayMetrics
        val panel = overlayPanel ?: return
        val panelWidth = panel.width.takeIf { it > 0 } ?: dpToPx(340f * overlayScale)
        val panelHeight = panel.height.takeIf { it > 0 } ?: dpToPx(320f * overlayScale)
        val shouldCollapse = OverlayRules.shouldCollapseToBubble(
            overlayLeftX = params.x,
            overlayWidth = panelWidth,
            overlayTopY = params.y,
            overlayHeight = panelHeight,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            edgeThresholdPx = dpToPx(24f)
        )

        if (shouldCollapse) {
            collapseToBubble()
            // 不再强制吸附到最右侧，保留用户释放时的位置，避免“吸住拖不走”
            overlayView?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun collapseToBubble() {
        collapsed = true
        overlayPanel?.visibility = View.GONE
        overlayBubble?.visibility = View.VISIBLE
        overlayView?.requestLayout()
    }

    private fun expandPanel() {
        collapsed = false
        overlayPanel?.visibility = View.VISIBLE
        overlayBubble?.visibility = View.GONE
        applyOverlayScale()
        applyOverlayStyle()
    }

    private fun registerSensors() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        linearAccelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun requestLocationUpdates() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return

        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            500L,
            0f,
            this,
            Looper.getMainLooper()
        )
    }

    private fun registerGnssStatusUpdates() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return
        if (locationManager == null || gnssStatusCallback != null) return

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var usedInFixCount = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usedInFixCount += 1
                }
                gpsUsedSatelliteCount = usedInFixCount
                refreshOverlay()
            }

            override fun onStopped() {
                gpsUsedSatelliteCount = null
                refreshOverlay()
            }
        }

        try {
            val registered = locationManager?.registerGnssStatusCallback(
                callback,
                Handler(Looper.getMainLooper())
            ) == true
            if (registered) {
                gnssStatusCallback = callback
            }
        } catch (_: SecurityException) {
            gpsUsedSatelliteCount = null
        } catch (_: RuntimeException) {
            gpsUsedSatelliteCount = null
        }
    }

    private fun unregisterGnssStatusUpdates() {
        val callback = gnssStatusCallback ?: return
        try {
            locationManager?.unregisterGnssStatusCallback(callback)
        } catch (_: RuntimeException) {
            // ignore teardown exception
        }
        gnssStatusCallback = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                accelDisplayMps2 = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagneticValues, 0, 3)
            }
        }

        val r = FloatArray(9)
        val i = FloatArray(9)
        if (SensorManager.getRotationMatrix(r, i, gravityValues, geomagneticValues)) {
            val orientation = FloatArray(3)
            SensorManager.getOrientation(r, orientation)
            headingDegrees = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
        } else {
            headingDegrees = null
        }

        refreshOverlay()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val gpsSpeed = if (location.hasSpeed()) location.speed else Float.NaN
        val receiveRealtimeNs = SystemClock.elapsedRealtimeNanos()
        gpsSignalLost = false
        lastGpsFixWallTimeMs = System.currentTimeMillis()

        val reacquiredAfterGap = SpeedRules.shouldEnterReacquireWarmup(
            lastReceiveRealtimeNs,
            receiveRealtimeNs,
            REACQUIRE_GAP_MS
        )
        if (reacquiredAfterGap) {
            // 重新获得 GPS 后先暖机，避免隧道前后两点直接算出离谱速度
            reacquireWarmupRemaining = REACQUIRE_WARMUP_POINTS
            speedMps = 0f
            speedFilterState = speedFilterState.copy(currentSpeedMps = 0f)
        }

        val computedMetrics = run {
            val prev = lastLocation
            val prevTimeMs = lastLocationTimeMs
            val prevElapsedNs = lastElapsedRealtimeNs
            val prevReceiveNs = lastReceiveRealtimeNs
            val nowTimeMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            val nowElapsedNs = location.elapsedRealtimeNanos.takeIf { it > 0L } ?: 0L

            if (reacquiredAfterGap || prev == null) {
                ComputedMotionMetrics(
                    speedMps = Float.NaN,
                    distanceMeters = Float.NaN,
                    deltaTimeSec = Float.NaN
                )
            } else {
                val dtSec = when {
                    nowElapsedNs > 0L && prevElapsedNs > 0L && nowElapsedNs > prevElapsedNs ->
                        (nowElapsedNs - prevElapsedNs) / 1_000_000_000f
                    prevReceiveNs > 0L && receiveRealtimeNs > prevReceiveNs ->
                        (receiveRealtimeNs - prevReceiveNs) / 1_000_000_000f
                    prevTimeMs > 0L && nowTimeMs > prevTimeMs ->
                        (nowTimeMs - prevTimeMs) / 1000f
                    else -> Float.NaN
                }

                val distanceM = prev.distanceTo(location)
                val rawMps = if (dtSec.isNaN() || dtSec <= 0f) Float.NaN else distanceM / dtSec

                ComputedMotionMetrics(
                    speedMps = if (dtSec in 0.02f..60f && rawMps in 0f..2777.8f) rawMps else Float.NaN,
                    distanceMeters = distanceM,
                    deltaTimeSec = dtSec
                )
            }
        }

        val nowMsForSpeed = System.currentTimeMillis()
        val inWarmup = reacquireWarmupRemaining > 0
        val filterResult = SpeedRules.processLocationSample(
            old = speedFilterState,
            sample = SpeedRules.LocationSample(
                gpsSpeedMps = gpsSpeed,
                hasGpsSpeed = location.hasSpeed(),
                computedSpeedMps = computedMetrics.speedMps,
                accuracyMeters = location.accuracy,
                speedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                distanceMeters = computedMetrics.distanceMeters.takeUnless { it.isNaN() },
                deltaTimeSec = computedMetrics.deltaTimeSec.takeUnless { it.isNaN() },
                linearAccelMps2 = accelDisplayMps2.takeUnless { it.isNaN() },
                inWarmup = inWarmup,
                nowMs = nowMsForSpeed
            )
        )
        if (inWarmup) {
            reacquireWarmupRemaining -= 1
        }
        speedFilterState = filterResult.nextFilterState
        speedMps = filterResult.finalSpeedMps

        Log.d(
            "OverlayService",
            "loc speed gps=$gpsSpeed computed=${computedMetrics.speedMps} final=$speedMps trusted=${filterResult.isSampleTrusted} stationary=${filterResult.isStationaryLocked} warmup=$reacquireWarmupRemaining reacquired=$reacquiredAfterGap provider=${location.provider} accuracy=${location.accuracy}"
        )

        val nowNsForAccel = receiveRealtimeNs
        if (linearAccelerometer == null && lastSpeedForAccelNs > 0L) {
            val dt = (nowNsForAccel - lastSpeedForAccelNs) / 1_000_000_000f
            if (dt > 0.05f) {
                val derivedAccel = ((speedMps - lastSpeedForAccelMps) / dt).coerceIn(-30f, 30f)
                accelDisplayMps2 = 0.75f * accelDisplayMps2 + 0.25f * derivedAccel
            }
        }
        lastSpeedForAccelMps = speedMps
        lastSpeedForAccelNs = nowNsForAccel

        lastLocation = location
        lastLocationTimeMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
        lastElapsedRealtimeNs = location.elapsedRealtimeNanos.takeIf { it > 0L } ?: 0L
        lastReceiveRealtimeNs = receiveRealtimeNs

        if (location.hasBearing()) {
            headingDegrees = ((location.bearing + 360f) % 360f)
        }

        val speedKmh = SpeedRules.toKmhClamped(speedMps)
        val nowMs = System.currentTimeMillis()

        if (filterResult.allowUpdateMaxSpeed) {
            val maxResult = SpeedRules.updateMaxSpeed(maxSpeedKmh, maxSpeedTimeMs, speedKmh, nowMs)
            maxSpeedKmh = maxResult.maxSpeedKmh
            maxSpeedTimeMs = maxResult.maxSpeedTimeMs
        }

        val waitNowMs = SpeedRules.resolveWaitClockNowMs(
            gpsSignalLost = gpsSignalLost,
            lastGpsFixWallTimeMs = lastGpsFixWallTimeMs,
            nowMs = nowMs
        )
        val waitState = SpeedRules.updateTripAndWait(
            old = SpeedRules.WaitState(
                tripStarted = tripStarted,
                tripStartTimeMs = tripStartTimeMs,
                waitAtZeroAccumMs = waitAtZeroAccumMs,
                waitAtZeroStartMs = waitAtZeroStartMs
            ),
            speedKmh = speedKmh,
            nowMs = waitNowMs
        )
        tripStarted = waitState.tripStarted
        tripStartTimeMs = waitState.tripStartTimeMs
        waitAtZeroAccumMs = waitState.waitAtZeroAccumMs
        waitAtZeroStartMs = waitState.waitAtZeroStartMs

        refreshOverlay()
    }

    private data class ComputedMotionMetrics(
        val speedMps: Float,
        val distanceMeters: Float,
        val deltaTimeSec: Float
    )

    private fun refreshOverlay() {
        val nowRealtimeNs = SystemClock.elapsedRealtimeNanos()
        gpsSignalLost = SpeedRules.shouldForceZeroOnStale(lastReceiveRealtimeNs, nowRealtimeNs, GPS_STALE_TIMEOUT_MS)
        if (gpsSignalLost) {
            val estimate = SpeedRules.estimateTunnelSpeed(
                old = speedFilterState,
                nowMs = System.currentTimeMillis(),
                linearAccelMps2 = accelDisplayMps2,
                gpsSignalLost = true
            )
            speedFilterState = estimate.nextFilterState
            speedMps = estimate.finalSpeedMps
            currentEstimateActive = estimate.isEstimated
        } else {
            currentEstimateActive = false
        }

        val speedKmh = SpeedRules.toKmhClamped(speedMps)
        tvMaxSpeedValue?.text = String.format("%.1f", maxSpeedKmh)
        tvMaxSpeedTime?.text = if (maxSpeedTimeMs > 0L) {
            "峰值时间 ${maxSpeedTimeFormatter.format(Date(maxSpeedTimeMs))}"
        } else {
            "峰值时间 --:--:--"
        }
        tvCurrentSpeedLabel?.text = if (currentEstimateActive) {
            getString(R.string.estimated_speed_label)
        } else {
            "当前速度"
        }
        tvCurrentSpeedValue?.text = if (currentEstimateActive) {
            String.format("%.1f", speedKmh)
        } else if (gpsSignalLost) {
            getString(R.string.no_gps_signal)
        } else {
            String.format("%.1f", speedKmh)
        }
        tvGpsSatelliteCount?.text = gpsUsedSatelliteCount?.let {
            getString(R.string.gps_satellite_label_fmt, it)
        } ?: getString(R.string.gps_satellite_unknown)
        applyCurrentSpeedTextStyle(gpsSignalLost)
        tvDragHandle?.text = if (currentEstimateActive) {
            getString(R.string.gps_estimate_hint)
        } else {
            "拖动移动 · 靠边收起"
        }
        val waitNowMs = SpeedRules.resolveWaitClockNowMs(
            gpsSignalLost = gpsSignalLost,
            lastGpsFixWallTimeMs = lastGpsFixWallTimeMs,
            nowMs = System.currentTimeMillis()
        )
        val waitMs = SpeedRules.currentWaitDurationMs(
            SpeedRules.WaitState(
                tripStarted = tripStarted,
                tripStartTimeMs = tripStartTimeMs,
                waitAtZeroAccumMs = waitAtZeroAccumMs,
                waitAtZeroStartMs = waitAtZeroStartMs
            ),
            waitNowMs
        )
        tvAccelValue?.text = formatDuration(waitMs)
        val elapsedMs = if (tripStarted && tripStartTimeMs > 0L) {
            System.currentTimeMillis() - tripStartTimeMs
        } else {
            0L
        }
        tvHeadingValue?.text = formatDuration(elapsedMs)

        val now = System.currentTimeMillis()
        speedHistory.addLast(SpeedTrendView.SpeedPoint(now, speedKmh))
        while (speedHistory.isNotEmpty() && now - speedHistory.first().timestampMs > historyWindowMs) {
            speedHistory.removeFirst()
        }

        val yMax = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .getInt(MainActivity.PREF_Y_AXIS_MAX_KMH, 300)
            .coerceIn(30, 2000)
            .toFloat()

        speedTrendView?.updateData(speedHistory.toList(), yMax, historyWindowMs)

        overlayBubble?.text = when {
            currentEstimateActive -> String.format("%.0f", speedKmh)
            gpsSignalLost -> getString(R.string.no_gps_short)
            else -> String.format("%.0f", speedKmh)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, sec)
    }

    private fun applyCurrentSpeedTextStyle(signalLost: Boolean) {
        tvCurrentSpeedValue?.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            when {
                currentEstimateActive -> 28f * overlayScale
                signalLost -> 16f * overlayScale
                else -> 34f * overlayScale
            }
        )
        tvCurrentSpeedValue?.setLineSpacing(0f, if (signalLost && !currentEstimateActive) 0.92f else 1f)
        tvCurrentSpeedUnit?.visibility = if (signalLost && !currentEstimateActive) View.INVISIBLE else View.VISIBLE
        tvCurrentSpeedValue?.alpha = if (currentEstimateActive) 0.92f else 1f
    }

    private fun calculateMaxScaleForBounds(params: WindowManager.LayoutParams): Float {
        val dm = resources.displayMetrics
        val safeMargin = dpToPx(8f)
        val baseWidth = dpToPx(340f).toFloat()
        val panelHeight = overlayPanel?.height?.takeIf { it > 0 }?.toFloat() ?: dpToPx(320f).toFloat()
        val baseHeight = (panelHeight / overlayScale.coerceAtLeast(0.01f)).coerceAtLeast(dpToPx(220f).toFloat())

        val availableWidth = (dm.widthPixels - safeMargin * 2).coerceAtLeast(dpToPx(240f))
        val availableHeight = (dm.heightPixels - params.y - safeMargin).coerceAtLeast(dpToPx(220f))

        val maxByWidth = availableWidth / baseWidth
        val maxByHeight = availableHeight / baseHeight
        return minOf(1.90f, maxByWidth, maxByHeight).coerceAtLeast(0.70f)
    }

    private fun clampOverlayPositionToScreen(params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val safeMargin = dpToPx(8f)
        val panelWidth = overlayPanel?.width?.takeIf { it > 0 } ?: dpToPx(340f * overlayScale)
        val panelHeight = overlayPanel?.height?.takeIf { it > 0 } ?: dpToPx(320f * overlayScale)

        val maxX = (dm.widthPixels - panelWidth - safeMargin).coerceAtLeast(safeMargin)
        val maxY = (dm.heightPixels - panelHeight - safeMargin).coerceAtLeast(safeMargin)

        params.x = params.x.coerceIn(safeMargin, maxX)
        params.y = params.y.coerceIn(safeMargin, maxY)
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).toInt()


    private fun applyBackgroundAlphaRecursively(root: View?, alpha: Int) {
        if (root == null) return
        applyBackgroundAlphaToView(root, alpha)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                applyBackgroundAlphaRecursively(root.getChildAt(i), alpha)
            }
        }
    }

    private fun applyBackgroundAlphaToView(view: View?, alpha: Int) {
        if (view == null) return
        val bg = view.background?.mutate() ?: return
        when (bg) {
            is GradientDrawable -> {
                bg.alpha = alpha
                view.background = bg
            }
            else -> {
                bg.alpha = alpha
                view.background = bg
            }
        }
    }

    private fun applyDynamicTextColors(useDarkText: Boolean) {
        val primary = if (useDarkText) Color.parseColor("#142033") else Color.parseColor("#F7FAFF")
        val secondary = if (useDarkText) Color.parseColor("#47617D") else Color.parseColor("#B8C8E6")
        val current = if (useDarkText) Color.parseColor("#0E5CC7") else Color.parseColor("#79C7FF")
        val success = if (useDarkText) Color.parseColor("#0F8F73") else Color.parseColor("#7BE0C3")

        tvOverlayTitle?.setTextColor(primary)
        tvMaxSpeedLabel?.setTextColor(primary)
        tvMaxSpeedUnit?.setTextColor(secondary)
        tvCurrentSpeedLabel?.setTextColor(primary)
        tvCurrentSpeedUnit?.setTextColor(secondary)
        tvGpsSatelliteCount?.setTextColor(secondary)
        tvTrendLabel?.setTextColor(primary)
        tvWaitLabel?.setTextColor(secondary)
        tvTripDurationLabel?.setTextColor(secondary)
        tvMaxSpeedValue?.setTextColor(success)
        tvMaxSpeedTime?.setTextColor(secondary)
        tvCurrentSpeedValue?.setTextColor(current)
        tvAccelValue?.setTextColor(primary)
        tvHeadingValue?.setTextColor(primary)
        tvDragHandle?.setTextColor(secondary)
        btnClose?.setTextColor(primary)
        btnScaleDown?.setTextColor(current)
        btnScaleUp?.setTextColor(current)
        overlayBubble?.setTextColor(if (useDarkText) current else Color.parseColor("#F7FAFF"))

        speedTrendView?.setTransparentMode(useDarkText)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.thomas.carspeed.action.START"
        const val ACTION_STOP = "com.thomas.carspeed.action.STOP"
        const val ACTION_UPDATE_STYLE = "com.thomas.carspeed.action.UPDATE_STYLE"

        private const val CHANNEL_ID = "overlay_sensor_channel"
        private const val NOTIFICATION_ID = 1001
        private const val GPS_STALE_TIMEOUT_MS = 3_000L
        private const val REACQUIRE_GAP_MS = 5_000L
        private const val REACQUIRE_WARMUP_POINTS = 2
        private const val COMPACT_ROW_ONLY_SCALE = 0.86f
    }
}

package com.thomas.carspeed

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoReplayActivity : AppCompatActivity() {
    private lateinit var replayRoot: FrameLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var videoCard: MaterialCardView
    private lateinit var infoCard: FloatMapCard
    private lateinit var infoContent: LinearLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var videoStage: FrameLayout
    private lateinit var videoTextureView: TextureView
    private lateinit var plateOverlayView: PlateOverlayView
    private lateinit var mapFrame: FrameLayout
    private lateinit var mapRouteView: MapRouteView
    private lateinit var videoMapDivider: View
    private lateinit var tvReplayTitle: TextView
    private lateinit var tvReplayStats: TextView
    private lateinit var tvReplayPosition: TextView
    private lateinit var tvSubtitleOverlay: DashcamInfoBar
    private lateinit var layoutReplayControls: LinearLayout
    private lateinit var layoutMapReplayControls: LinearLayout
    private lateinit var layoutMapBottomOverlay: LinearLayout
    private lateinit var layoutMapActionControls: LinearLayout
    private lateinit var layoutMapScaleControls: LinearLayout
    private lateinit var tvReplayPlayPause: TextView
    private lateinit var tvMapReplayPlayPause: TextView
    private lateinit var tvReplaySpeed: TextView
    private lateinit var tvMapReplaySpeed: TextView
    private lateinit var seekReplayProgress: SeekBar
    private lateinit var seekMapReplayProgress: SeekBar
    private lateinit var tvReplayTime: TextView
    private lateinit var tvMapReplayTime: TextView
    private lateinit var tvReplayFullscreen: TextView
    private lateinit var tvReplayRotate: TextView
    private lateinit var tvReplayExport: TextView
    private lateinit var tvMapScale: TextView
    private lateinit var btnMapViewportAll: MaterialButton
    private lateinit var btnMapViewportCurrent: MaterialButton
    private lateinit var btnMapType: MaterialButton
    private lateinit var btnOfflineMap: MaterialButton
    private lateinit var btnMapScaleOut: MaterialButton
    private lateinit var btnMapScaleIn: MaterialButton
    private lateinit var btnReplayLayout: MaterialButton
    private lateinit var tvReplayLayout: TextView
    private lateinit var floatTouch: FloatWindowTouch

    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoFile: File? = null
    private var videoFiles: List<File> = emptyList()
    private var playbackFiles: List<File> = emptyList()
    private var segmentDurationsMs: List<Long> = emptyList()
    private var segmentOffsetsMs: List<Long> = emptyList()
    private var currentSegmentIndex = 0
    private var pendingSeekGlobalMs: Long? = null
    private var track: VideoTrack? = null
    private var trackSummary: TrackSummary? = null
    private var subtitleCues: List<SrtCue> = emptyList()
    private var plateSamples: List<PlateRecognitionSample> = emptyList()
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var playerPrepared = false
    private var controlsVisible = true
    private var fullscreen = false
    private var videoRotationDegrees = 0
    private var flatVideoMapRatio: Float? = null
    private var mapFullscreen = false
    private var userSeeking = false
    private var trackOnly = false
    private var trackReplayPlaying = true
    private var trackReplayBaseMs = 0L
    private var trackReplayStartElapsedMs = 0L
    private var normalVideoRadius = 0f
    private var normalInfoRadius = 0f
    private var videoMapRatio = DEFAULT_VIDEO_MAP_RATIO
    private var layoutMode = ReplayMapLayout.MODE_SPLIT
    private var floatSpec = ReplayMapLayout.defaultFloatSpec()
    private var dividerDragStartY = 0f
    private var dividerDragStartVideoHeight = 0
    private var dividerDragTotalHeight = 0
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var playbackSpeedIndex = 0
    private var rememberedCameraApplied = false
    private var appliedPlaybackSpeed = 1f
    private var lastCatchUpElapsedMs = 0L
    private var exportPanel: ReplayExportPanel? = null
    private val exportWorker = Executors.newSingleThreadExecutor()
    private val loadWorker = Executors.newSingleThreadExecutor()
    @Volatile private var exportRunning = false
    @Volatile private var exportCancelled = false
    private var photoCandidates: List<RoutePhotoCandidate> = emptyList()
    private var radarLookup: HistoricalRadarLookup? = null
    private var mapActionPadStart = 0
    private var mapActionPadTop = 0
    private var mapActionPadEnd = 0
    private var mapActionPadBottom = 0

    private val syncTicker = object : Runnable {
        override fun run() {
            if (!exportRunning) {
                syncRouteToVideo()
                updateControls()
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    private val hideControlsRunnable = Runnable { hideControls() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_replay)

        replayRoot = findViewById(R.id.replayRoot)
        toolbar = findViewById(R.id.toolbar)
        videoCard = findViewById(R.id.videoCard)
        infoCard = findViewById(R.id.infoCard)
        infoContent = findViewById(R.id.infoContent)
        videoFrame = findViewById(R.id.videoFrame)
        videoStage = findViewById(R.id.videoStage)
        videoTextureView = findViewById(R.id.videoTextureView)
        plateOverlayView = findViewById(R.id.replayPlateOverlayView)
        mapFrame = findViewById(R.id.mapFrame)
        mapRouteView = findViewById(R.id.mapRouteView)
        videoMapDivider = findViewById(R.id.videoMapDivider)
        tvReplayTitle = findViewById(R.id.tvReplayTitle)
        tvReplayStats = findViewById(R.id.tvReplayStats)
        tvReplayPosition = findViewById(R.id.tvReplayPosition)
        tvSubtitleOverlay = findViewById(R.id.tvSubtitleOverlay)
        layoutReplayControls = findViewById(R.id.layoutReplayControls)
        layoutMapReplayControls = findViewById(R.id.layoutMapReplayControls)
        layoutMapBottomOverlay = findViewById(R.id.layoutMapBottomOverlay)
        layoutMapActionControls = findViewById(R.id.layoutMapActionControls)
        layoutMapScaleControls = findViewById(R.id.layoutMapScaleControls)
        tvReplayPlayPause = findViewById(R.id.tvReplayPlayPause)
        tvMapReplayPlayPause = findViewById(R.id.tvMapReplayPlayPause)
        tvReplaySpeed = findViewById(R.id.tvReplaySpeed)
        tvMapReplaySpeed = findViewById(R.id.tvMapReplaySpeed)
        seekReplayProgress = findViewById(R.id.seekReplayProgress)
        seekMapReplayProgress = findViewById(R.id.seekMapReplayProgress)
        tvReplayTime = findViewById(R.id.tvReplayTime)
        tvMapReplayTime = findViewById(R.id.tvMapReplayTime)
        tvReplayFullscreen = findViewById(R.id.tvReplayFullscreen)
        tvReplayRotate = findViewById(R.id.tvReplayRotate)
        tvReplayExport = findViewById(R.id.tvReplayExport)
        tvMapScale = findViewById(R.id.tvMapScale)
        btnMapViewportAll = findViewById(R.id.btnMapViewportAll)
        btnMapViewportCurrent = findViewById(R.id.btnMapViewportCurrent)
        btnMapType = findViewById(R.id.btnMapType)
        btnOfflineMap = findViewById(R.id.btnOfflineMap)
        btnMapScaleOut = findViewById(R.id.btnMapScaleOut)
        btnMapScaleIn = findViewById(R.id.btnMapScaleIn)
        btnReplayLayout = findViewById(R.id.btnReplayLayout)
        tvReplayLayout = findViewById(R.id.tvReplayLayout)
        mapActionPadStart = btnMapViewportAll.paddingStart
        mapActionPadTop = btnMapViewportAll.paddingTop
        mapActionPadEnd = btnMapViewportAll.paddingEnd
        mapActionPadBottom = btnMapViewportAll.paddingBottom
        videoCard.clipToOutline = false
        videoCard.clipChildren = false
        videoFrame.clipChildren = false
        infoCard.clipToOutline = false
        infoCard.clipChildren = false
        mapFrame.clipChildren = false
        normalVideoRadius = videoCard.radius
        normalInfoRadius = infoCard.radius
        videoMapRatio = loadVideoMapRatio()
        layoutMode = loadLayoutMode()
        floatSpec = loadFloatSpec()
        val rememberedPreview = ReplayRemembered.preview(this)
        videoRotationDegrees = rememberedPreview.rotationDegrees
        playbackSpeedIndex = ReplayRemembered.exportSettings(this).speedIndex
            .coerceIn(0, PLAYBACK_SPEEDS.lastIndex)
        mapRouteView.setContentRotation(videoRotationDegrees)
        applyVideoMapRatio(videoMapRatio)
        applyPlaybackSystemUi()

        toolbar.setNavigationOnClickListener { finish() }
        tvReplayPlayPause.setOnClickListener { togglePlayPause() }
        tvMapReplayPlayPause.setOnClickListener { togglePlayPause() }
        tvReplaySpeed.setOnClickListener { switchPlaybackSpeed() }
        tvMapReplaySpeed.setOnClickListener { switchPlaybackSpeed() }
        tvReplayFullscreen.setOnClickListener { setFullscreen(!fullscreen) }
        tvReplayLayout.setOnClickListener { toggleReplayLayout() }
        btnReplayLayout.setOnClickListener { toggleReplayLayout() }
        tvReplayRotate.setOnClickListener { rotatePlayback(90) }
        tvReplayExport.setOnClickListener { showExportPanel() }
        mapRouteView.onMapTap = { if (!mapFullscreen) setMapFullscreen(true) }
        mapRouteView.onPhotoClick = { path -> openRoutePhoto(path) }
        mapRouteView.onRadarClick = { radar -> showRadarDetail(radar) }
        mapRouteView.onMaxSpeedClick = { speedKmh -> showMaxSpeed(speedKmh) }
        mapRouteView.onScaleChanged = { updateMapControls() }
        mapRouteView.onCameraPreferenceChanged = { persistPreviewMemory() }
        mapRouteView.setOverlayControlsVisible(false)
        btnMapViewportAll.setOnClickListener { mapRouteView.showWholeRoute() }
        btnMapViewportCurrent.setOnClickListener { mapRouteView.showCurrentViewport() }
        btnMapType.setOnClickListener { mapRouteView.toggleMapType() }
        btnOfflineMap.setOnClickListener { mapRouteView.openOfflineMap() }
        btnMapScaleOut.setOnClickListener {
            mapRouteView.zoomOutScale()
            updateMapControls()
        }
        btnMapScaleIn.setOnClickListener {
            mapRouteView.zoomInScale()
            updateMapControls()
        }
        setupVideoMapDivider()
        setupFloatMapGestures()
        updateMapControls()
        setupSeekBar(seekReplayProgress)
        setupSeekBar(seekMapReplayProgress)
        if (intent.getBooleanExtra(EXTRA_TRACK_ONLY, false)) {
            setupTrackOnlyReplay()
            return
        }
        loadVideoReplay(intent.getBooleanExtra(EXTRA_OPEN_EXPORT, false))
    }

    /** 分段时长、轨迹、字幕和照片都要读文件，一小时的行程有几十段，放到后台读完再交给界面。 */
    private fun loadVideoReplay(openExport: Boolean) {
        val paths = intent.getStringArrayListExtra(EXTRA_VIDEO_PATHS)
        val singlePath = intent.getStringExtra(EXTRA_VIDEO_PATH)
        val appContext = applicationContext
        loadWorker.execute {
            val files = paths
                ?.map { File(it) }
                ?.filter { it.exists() }
                ?.takeIf { it.isNotEmpty() }
                ?: singlePath?.let { listOf(File(it)) }.orEmpty().filter { it.exists() }
            if (files.isEmpty()) {
                mainHandler.post {
                    if (isDestroyed) return@post
                    Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@execute
            }
            val sources = try {
                files.map { ReplayMediaSource.prepare(appContext, it) }
            } catch (_: Exception) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "无法读取录像时间轴，请检查文件和剩余存储空间", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                return@execute
            }
            val durations = sources.mapIndexed { index, source ->
                source.durationMs.takeIf { it > 0L } ?: segmentDurationMs(appContext, files[index])
            }
            val offsets = durations.runningFold(0L) { total, duration -> total + duration }.dropLast(1)
            val loadedTrack = VideoTrackStorage.readMergedTrack(appContext, files, offsets, durations)
            val summary = loadedTrack?.let { TrackMetrics.summary(it.points) }
            val cues = SrtSubtitle.readCues(files, offsets, durations)
            // 跨分段读进来后整趟再绑一次轨迹：同一辆车在整段行程里只认一个号。
            val plates = PlateTrackBinder.bindAll(
                PlateRecognitionStorage.readSamples(appContext, files, offsets, durations)
            )
            val photos = loadRoutePhotoCandidates(appContext)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                videoFiles = files
                playbackFiles = sources.map { it.file }
                videoFile = files.first()
                segmentDurationsMs = durations
                segmentOffsetsMs = offsets
                track = loadedTrack
                trackSummary = summary
                subtitleCues = cues
                plateSamples = plates
                photoCandidates = photos
                currentSegmentIndex = 0
                pendingSeekGlobalMs = 0L
                updateControls()
                setupVideo(playbackFiles)
                renderStaticInfo(files)
                if (openExport) replayRoot.post { if (!isDestroyed) showExportPanel() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mapRouteView.onHostResume()
        // 从设置页回来时字幕开关可能变了，白栏要重新按新开关决定显隐。
        if (lastVideoWidth > 0 && lastVideoHeight > 0) {
            applyVideoStage(lastVideoWidth, lastVideoHeight)
            applyReplayLayout()
        }
        mainHandler.post(syncTicker)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(syncTicker)
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (playerPrepared) {
            try {
                mediaPlayer?.pause()
            } catch (_: IllegalStateException) {
                // 播放器出错后没法暂停，忽略即可。
            }
        }
        // 导出还要靠地图截图，导出期间地图不能停。
        if (!exportRunning) mapRouteView.onHostPause()
        super.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapRouteView.onHostLowMemory()
    }

    override fun onDestroy() {
        exportCancelled = true
        exportWorker.shutdownNow()
        loadWorker.shutdownNow()
        radarLookup?.cancel()
        radarLookup = null
        mainHandler.removeCallbacks(syncTicker)
        mainHandler.removeCallbacks(hideControlsRunnable)
        releasePlayer()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (exportPanel?.isShowing() == true) {
            exportPanel?.hide()
        } else if (mapFullscreen) {
            setMapFullscreen(false)
        } else if (fullscreen) {
            setFullscreen(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun setupVideo(files: List<File>) {
        videoTextureView.setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
        }
        videoTextureView.setOnLongClickListener {
            showExportPanel()
            true
        }
        videoTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                prepareSegment(files, 0, Surface(surface), 0L)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer?.let { updateVideoLayout(it.videoWidth, it.videoHeight) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (videoTextureView.isAvailable) {
            prepareSegment(files, 0, Surface(videoTextureView.surfaceTexture), 0L)
        }
    }

    private fun setupTrackOnlyReplay() {
        val startMs = intent.getLongExtra(EXTRA_TRACK_START_MS, 0L)
        val endMs = intent.getLongExtra(EXTRA_TRACK_END_MS, 0L)
        trackOnly = true
        trackReplayPlaying = false
        videoCard.visibility = View.GONE
        val appContext = applicationContext
        loadWorker.execute {
            val record = DriveRecordStore.loadRecords(appContext).firstOrNull { record ->
                record.startTimeMs == startMs && record.endTimeMs == endMs && record.routePoints.isNotEmpty()
            }
            val photos = if (record == null) emptyList() else loadRoutePhotoCandidates(appContext)
            mainHandler.post {
                if (isDestroyed || isFinishing) return@post
                if (record == null) {
                    Toast.makeText(this, R.string.video_replay_no_track, Toast.LENGTH_SHORT).show()
                    finish()
                    return@post
                }
                photoCandidates = photos
                showTrackOnlyRecord(record)
            }
        }
    }

    private fun showTrackOnlyRecord(record: DriveRecord) {
        trackOnly = true
        trackReplayPlaying = true
        trackReplayBaseMs = 0L
        trackReplayStartElapsedMs = SystemClock.elapsedRealtime()
        videoCard.visibility = View.GONE
        videoMapDivider.visibility = View.GONE
        layoutReplayControls.visibility = View.GONE
        layoutMapReplayControls.visibility = View.VISIBLE
        tvReplayFullscreen.visibility = View.GONE
        tvReplayLayout.visibility = View.GONE
        btnReplayLayout.visibility = View.GONE
        applyReplayLayout()
        val points = record.routePoints.mapIndexed { index, point ->
            val elapsedMs = (point.timeMs - record.startTimeMs).takeIf { it >= 0L } ?: (index * 1_000L)
            VideoTrackPoint(
                elapsedMs = elapsedMs,
                timeMs = point.timeMs,
                latitude = point.latitude,
                longitude = point.longitude,
                speedKmh = point.speedKmh,
                accuracyMeters = point.accuracyMeters,
                bearingDegrees = point.bearingDegrees
            )
        }
        track = VideoTrack(
            videoFileName = getString(R.string.gps_track_replay_title),
            startTimeMs = record.startTimeMs,
            points = points
        )
        trackSummary = TrackMetrics.summary(points)
        renderStaticInfo(emptyList())
        syncRouteToVideo()
        updateControls()
    }

    private fun prepareSegment(files: List<File>, index: Int, surface: Surface, seekMs: Long, autoStart: Boolean = true) {
        releasePlayer(releaseSurface = false)
        videoSurface = surface
        playerPrepared = false
        currentSegmentIndex = index.coerceIn(0, files.lastIndex)
        val file = files[currentSegmentIndex]
        val nextPlayer = MediaPlayer()
        var startAfterInitialSeek = autoStart
        mediaPlayer = nextPlayer
        nextPlayer.apply {
            setOnPreparedListener { player ->
                if (mediaPlayer !== player) return@setOnPreparedListener
                playerPrepared = true
                updateVideoLayout(player.videoWidth, player.videoHeight)
                if (seekMs > 0L) {
                    // 只在真的要跳转时才 seek：位置已是 0 时 seekTo 不回调，会把 start 卡住。
                    pendingSeekGlobalMs = segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L } + seekMs
                    startAfterInitialSeek = autoStart
                    player.seekTo(
                        seekMs.coerceIn(0L, player.duration.toLong().coerceAtLeast(0L)),
                        MediaPlayer.SEEK_CLOSEST_SYNC
                    )
                } else {
                    pendingSeekGlobalMs = null
                    if (autoStart) player.start()
                }
                applyPlaybackSpeed(player)
                syncRouteToVideo()
                updateControls()
                showControls()
            }
            setOnSeekCompleteListener { player ->
                if (mediaPlayer !== player) return@setOnSeekCompleteListener
                pendingSeekGlobalMs = null
                if (startAfterInitialSeek) {
                    startAfterInitialSeek = false
                    player.start()
                }
                syncRouteToVideo()
                updateControls()
            }
            setOnVideoSizeChangedListener { _, width, height ->
                updateVideoLayout(width, height)
            }
            setOnCompletionListener {
                if (currentSegmentIndex < files.lastIndex) {
                    videoSurface?.let { surface -> prepareSegment(files, currentSegmentIndex + 1, surface, 0L) }
                } else {
                    showControls()
                }
            }
            setOnErrorListener { _, _, _ ->
                playerPrepared = false
                pendingSeekGlobalMs = null
                Toast.makeText(this@VideoReplayActivity, R.string.video_replay_failed, Toast.LENGTH_SHORT).show()
                true
            }
        }
        try {
            nextPlayer.setDataSource(file.absolutePath)
            nextPlayer.setSurface(surface)
            nextPlayer.prepareAsync()
        } catch (error: Exception) {
            nextPlayer.release()
            if (mediaPlayer === nextPlayer) mediaPlayer = null
            pendingSeekGlobalMs = null
            Toast.makeText(this, R.string.video_replay_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun releasePlayer(releaseSurface: Boolean = true) {
        playerPrepared = false
        mediaPlayer?.release()
        mediaPlayer = null
        if (releaseSurface) {
            videoSurface?.release()
            videoSurface = null
        }
    }

    private fun switchPlaybackSpeed() {
        if (trackOnly) {
            trackReplayBaseMs = currentVideoPositionMs()
            trackReplayStartElapsedMs = SystemClock.elapsedRealtime()
        }
        playbackSpeedIndex = (playbackSpeedIndex + 1) % PLAYBACK_SPEEDS.size
        applyPlaybackSpeed()
        updateControls()
        if (exportPanel?.isShowing() == true) {
            exportPanel?.syncSpeed(playbackSpeedIndex)
        } else {
            ReplayRemembered.saveSpeed(this, playbackSpeedIndex)
        }
        if (!trackOnly) showControls()
    }

    private fun applyPlaybackSpeed(player: MediaPlayer? = mediaPlayer) {
        if (!playerPrepared) return
        val target = player ?: return
        val wasPlaying = try {
            target.isPlaying
        } catch (_: IllegalStateException) {
            false
        }
        appliedPlaybackSpeed = applyPlayerSpeed(target, currentPlaybackSpeed())
        lastCatchUpElapsedMs = SystemClock.elapsedRealtime()
        if (!wasPlaying) {
            try {
                if (target.isPlaying) target.pause()
            } catch (_: IllegalStateException) {
                // 保持原暂停状态即可。
            }
        }
    }

    private fun applyPlayerSpeed(player: MediaPlayer, requested: Float): Float {
        for (speed in PlaybackRate.fallbackSpeeds(requested)) {
            try {
                val params = try {
                    player.playbackParams
                } catch (_: IllegalStateException) {
                    PlaybackParams()
                }
                player.playbackParams = params.setSpeed(speed).setPitch(1f).allowDefaults()
                return speed
            } catch (_: RuntimeException) {
                // 这档倍率播放器不收，换低一档再试。
            }
        }
        return 1f
    }

    private fun catchUpPlaybackRate() {
        if (trackOnly || userSeeking || !playerPrepared) {
            lastCatchUpElapsedMs = SystemClock.elapsedRealtime()
            return
        }
        val playing = try {
            mediaPlayer?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
        val now = SystemClock.elapsedRealtime()
        val extra = if (playing && PlaybackRate.shouldCatchUp(currentPlaybackSpeed(), appliedPlaybackSpeed)) {
            PlaybackRate.extraSeekMs(now - lastCatchUpElapsedMs, currentPlaybackSpeed(), appliedPlaybackSpeed)
        } else {
            0L
        }
        lastCatchUpElapsedMs = now
        if (extra < 200L) return
        val position = currentVideoPositionMs()
        val target = (position + extra).coerceAtMost(totalDurationMs())
        if (target > position + 150L) seekToGlobal(target)
    }

    private fun setupSeekBar(target: SeekBar) {
        target.min = 0
        target.max = ReplayTimeline.SEEK_MAX
        target.progress = 0
        target.isEnabled = false
        target.isSaveEnabled = false
        target.layoutDirection = View.LAYOUT_DIRECTION_LTR
        target.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                mainHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                seekToGlobal(ReplayTimeline.position(progress, totalDurationMs()))
                userSeeking = false
                showControls()
            }
        })
    }

    private fun togglePlayPause() {
        if (trackOnly) {
            val current = currentVideoPositionMs()
            trackReplayBaseMs = current
            trackReplayStartElapsedMs = SystemClock.elapsedRealtime()
            trackReplayPlaying = !trackReplayPlaying && current < totalDurationMs()
            updateControls()
            return
        }
        if (!playerPrepared) return
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.start()
        }
        updateControls()
        showControls()
    }

    private fun showControls() {
        controlsVisible = true
        if (!trackOnly) layoutReplayControls.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    private fun hideControls() {
        controlsVisible = false
        layoutReplayControls.visibility = View.GONE
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun updateControls() {
        val duration = totalDurationMs().coerceAtLeast(0L)
        val current = currentVideoPositionMs().coerceIn(0L, duration)
        val ready = duration > 0L && (trackOnly || playerPrepared)
        seekReplayProgress.isEnabled = ready
        seekMapReplayProgress.isEnabled = ready
        if (!userSeeking) {
            val progress = ReplayTimeline.progress(current, duration)
            seekReplayProgress.progress = progress
            seekMapReplayProgress.progress = progress
        }
        tvReplayTime.text = "${ReplayTimeline.formatDuration(current)} / ${ReplayTimeline.formatDuration(duration)}"
        tvMapReplayTime.text = tvReplayTime.text
        tvReplayPlayPause.text = if (playerPrepared && mediaPlayer?.isPlaying == true) "暂停" else "播放"
        if (trackOnly) tvReplayPlayPause.text = if (trackReplayPlaying) "暂停" else "播放"
        tvMapReplayPlayPause.text = tvReplayPlayPause.text
        tvReplaySpeed.text = playbackSpeedLabel()
        tvMapReplaySpeed.text = tvReplaySpeed.text
        tvReplayFullscreen.text = if (fullscreen) "退出" else "全屏"
        val layoutLabel = if (layoutMode == ReplayMapLayout.MODE_FLOAT) {
            getString(R.string.video_replay_layout_split)
        } else {
            getString(R.string.video_replay_layout_float)
        }
        tvReplayLayout.text = layoutLabel
        btnReplayLayout.text = layoutLabel
    }

    private fun updateMapControls() {
        tvMapScale.text = getString(R.string.video_replay_map_scale, mapRouteView.mapScaleLabel())
        btnMapType.text = mapRouteView.mapTypeLabel()
        btnMapScaleIn.isEnabled = mapRouteView.canZoomInScale()
        btnMapScaleOut.isEnabled = mapRouteView.canZoomOutScale()
    }

    private fun setFullscreen(enabled: Boolean) {
        if (enabled && mapFullscreen) setMapFullscreen(false)
        fullscreen = enabled
        toolbar.visibility = View.GONE
        infoCard.visibility = if (enabled) View.GONE else View.VISIBLE
        videoMapDivider.visibility = if (enabled) View.GONE else View.VISIBLE
        replayRoot.setPadding(
            0,
            0,
            0,
            0
        )
        replayRoot.setBackgroundColor(
            if (enabled) android.graphics.Color.BLACK else androidx.core.content.ContextCompat.getColor(this, R.color.brand_sand)
        )
        videoCard.radius = if (enabled) 0f else normalVideoRadius
        applyReplayLayout()
        applyPlaybackSystemUi()
        mediaPlayer?.let { player -> videoFrame.post { updateVideoLayout(player.videoWidth, player.videoHeight) } }
        showControls()
        updateControls()
    }

    private fun setMapFullscreen(enabled: Boolean) {
        if (fullscreen) return
        mapFullscreen = enabled
        toolbar.visibility = View.GONE
        videoCard.visibility = if (enabled) View.GONE else View.VISIBLE
        videoMapDivider.visibility = if (enabled || trackOnly) View.GONE else View.VISIBLE
        mapRouteView.setOverlayControlsVisible(enabled)
        layoutMapReplayControls.visibility = if (enabled || trackOnly) View.VISIBLE else View.GONE
        layoutMapBottomOverlay.visibility = if (enabled) View.GONE else View.VISIBLE
        layoutMapActionControls.visibility = if (enabled) View.GONE else View.VISIBLE
        layoutMapScaleControls.visibility = if (enabled) View.GONE else View.VISIBLE
        tvReplayTitle.visibility = if (enabled) View.GONE else View.VISIBLE
        tvReplayStats.visibility = if (enabled) View.GONE else View.VISIBLE
        tvReplayPosition.visibility = if (enabled) View.GONE else View.VISIBLE
        replayRoot.setPadding(
            0,
            0,
            0,
            0
        )
        replayRoot.setBackgroundColor(
            if (enabled) android.graphics.Color.BLACK else androidx.core.content.ContextCompat.getColor(this, R.color.brand_sand)
        )
        infoCard.radius = if (enabled) 0f else normalInfoRadius
        infoContent.setPadding(
            0,
            0,
            0,
            0
        )
        applyReplayLayout()
        applyPlaybackSystemUi()
        mapRouteView.post { mapRouteView.revealWholeRoute() }
    }

    private fun renderStaticInfo(files: List<File>) {
        if (trackOnly) {
            tvReplayTitle.text = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: getString(R.string.gps_track_replay_title)
            renderTrackInfo()
            return
        }
        val folder = files.firstOrNull()?.parentFile?.name
        val startMs = folder?.let { VideoStorage.tripFolderStartTimeMs(it) } ?: 0L
        val durationMs = trackSummary?.durationMs ?: track?.points?.let { points ->
            if (points.isEmpty()) 0L else points.last().timeMs - points.first().timeMs
        } ?: 0L
        val title = VideoStorage.tripListTitle(startMs, durationMs)
            ?: files.firstOrNull()?.parentFile?.takeIf { files.size > 1 }?.name
            ?: files.first().name
        tvReplayTitle.text = getString(R.string.video_replay_file_title, title)
        val points = track?.points.orEmpty()
        tvReplayStats.text = if (points.isEmpty()) {
            getString(R.string.video_replay_no_track)
        } else {
            val summary = trackSummary ?: TrackMetrics.summary(points)
            getString(
                R.string.video_replay_track_stats,
                points.size,
                formatDuration(summary.durationMs),
                TrackMetrics.formatDistance(summary.totalDistanceMeters),
                summary.maxSpeedKmh,
                summary.averageSpeedKmh
            )
        }
        publishRoute(points, 0L)
        requestHistoricalRadars(points)
    }

    private fun renderTrackInfo() {
        val points = track?.points.orEmpty()
        tvReplayStats.text = if (points.isEmpty()) {
            getString(R.string.video_replay_no_track)
        } else {
            val summary = trackSummary ?: TrackMetrics.summary(points)
            getString(
                R.string.video_replay_track_stats,
                points.size,
                formatDuration(summary.durationMs),
                TrackMetrics.formatDistance(summary.totalDistanceMeters),
                summary.maxSpeedKmh,
                summary.averageSpeedKmh
            )
        }
        publishRoute(points, 0L)
    }

    private fun publishRoute(points: List<VideoTrackPoint>, elapsedMs: Long) {
        mapRouteView.setRoute(points, elapsedMs)
        mapRouteView.setPhotoMarks(
            RoutePhotoMarks.match(points, routePhotoCandidates(), track?.startTimeMs ?: 0L)
        )
        mapRouteView.setRadarMarks(track?.radars.orEmpty())
        if (!rememberedCameraApplied) {
            rememberedCameraApplied = true
            val memory = ReplayRemembered.preview(this)
            mapRouteView.restoreCamera(memory.satellite, memory.wholeRoute, memory.scaleIndex)
        }
    }

    private fun showRadarDetail(radar: VideoTrackRadar) {
        AlertDialog.Builder(this)
            .setTitle(R.string.route_radar_detail_title)
            .setMessage(
                RouteRadarMarks.detail(
                    cameraType = radar.cameraType,
                    speedLimit = radar.speedLimit,
                    roadLayer = radar.roadLayer,
                    usingDefault = radar.usingDefault,
                    correctedByDivergence = radar.correctedByDivergence
                )
            )
            .setPositiveButton(R.string.route_radar_detail_close, null)
            .show()
    }

    private fun showMaxSpeed(speedKmh: Float) {
        AlertDialog.Builder(this)
            .setTitle(R.string.route_max_speed_title)
            .setMessage(getString(R.string.route_max_speed_message, speedKmh))
            .setPositiveButton(R.string.route_radar_detail_close, null)
            .show()
    }

    /**
     * 新录像在录的时候已经把雷达写进轨迹。旧录像没有这份数据，按轨迹向导航补查一次，
     * 查到就写回轨迹文件，下次直接画。
     */
    private fun requestHistoricalRadars(points: List<VideoTrackPoint>) {
        if (trackOnly || points.size < 2 || !track?.radars.isNullOrEmpty()) return
        radarLookup?.cancel()
        val lookup = HistoricalRadarLookup(this)
        radarLookup = lookup
        lookup.load(points) { radars ->
            radarLookup = null
            if (isDestroyed || radars.isEmpty()) return@load
            track = track?.copy(radars = radars)
            mapRouteView.setRadarMarks(radars)
            persistLookedUpRadars(radars)
        }
    }

    private fun persistLookedUpRadars(radars: List<VideoTrackRadar>) {
        if (loadWorker.isShutdown) return
        val files = videoFiles
        val offsets = segmentOffsetsMs
        val durations = segmentDurationsMs
        val appContext = applicationContext
        runCatching { loadWorker.execute { writeLookedUpRadars(appContext, files, offsets, durations, radars) } }
    }

    private fun writeLookedUpRadars(
        context: android.content.Context,
        files: List<File>,
        offsets: List<Long>,
        durations: List<Long>,
        radars: List<VideoTrackRadar>
    ) {
        files.forEachIndexed { index, file ->
            val existing = VideoTrackStorage.readTrack(context, file) ?: return@forEachIndexed
            val offset = offsets.getOrElse(index) { 0L }
            val duration = durations.getOrElse(index) { Long.MAX_VALUE }
            val segmentRadars = radars.mapNotNull { radar ->
                val localElapsed = radar.elapsedMs - offset
                if (localElapsed < -2_000L || localElapsed > duration + 2_000L) null
                else radar.copy(elapsedMs = localElapsed.coerceAtLeast(0L))
            }
            if (segmentRadars.isEmpty()) return@forEachIndexed
            VideoTrackStorage.writeTrack(
                context,
                file,
                existing.copy(radars = RouteRadarMarks.uniqueByLocation(segmentRadars))
            )
        }
    }

    private fun routePhotoCandidates(): List<RoutePhotoCandidate> = photoCandidates

    private fun loadRoutePhotoCandidates(context: android.content.Context): List<RoutePhotoCandidate> {
        return VideoStorage.photoFiles(context).mapNotNull { file ->
            val timeMs = RoutePhotoMarks.timeFromFileName(file.name) ?: return@mapNotNull null
            RoutePhotoCandidate(timeMs, file.absolutePath)
        }
    }

    private fun openRoutePhoto(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.photo_preview_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PhotoPreviewActivity::class.java).putExtra(PhotoPreviewActivity.EXTRA_PATH, file.absolutePath))
    }

    private fun syncRouteToVideo() {
        catchUpPlaybackRate()
        val points = track?.points.orEmpty()
        val elapsedMs = currentVideoPositionMs()
        mapRouteView.setRoute(points, elapsedMs)
        syncSubtitle(elapsedMs)
        syncPlateOverlay(elapsedMs)
        val point = VideoTrackSync.pointAt(points, elapsedMs)
        tvReplayPosition.text = if (point == null) {
            getString(R.string.video_replay_position_empty)
        } else {
            val currentDistance = TrackMetrics.distanceAt(points, elapsedMs)
            val totalDistance = trackSummary?.totalDistanceMeters ?: TrackMetrics.totalDistance(points)
            getString(
                R.string.video_replay_position,
                TrackMetrics.formatDistance(currentDistance),
                TrackMetrics.formatDistance(totalDistance)
            )
        }
    }

    private fun syncSubtitle(elapsedMs: Long) {
        val fields = currentSubtitleFields()
        val cue = SrtSubtitle.cueAt(subtitleCues, elapsedMs, SUBTITLE_GAP_HOLD_MS)
        tvSubtitleOverlay.setInfo(
            ShareSubtitleFormatter.infoBar(cue?.text.orEmpty(), fields),
            fields.hasAnyField()
        )
    }

    private fun syncPlateOverlay(elapsedMs: Long) {
        val sample = plateSamples.lastOrNull { sample ->
            sample.elapsedMs <= elapsedMs && elapsedMs - sample.elapsedMs <= PLATE_REPLAY_HOLD_MS
        }
        plateOverlayView.setFrame(
            sample?.let {
                PlateOverlayFrame(
                    imageWidth = it.imageWidth,
                    imageHeight = it.imageHeight,
                    rotationDegrees = it.rotationDegrees,
                    plates = it.plates
                )
            }
        )
    }

    private fun currentVideoPositionMs(): Long {
        if (trackOnly) {
            val duration = totalDurationMs().coerceAtLeast(0L)
            val current = if (trackReplayPlaying) {
                trackReplayBaseMs + ((SystemClock.elapsedRealtime() - trackReplayStartElapsedMs) * currentPlaybackSpeed()).toLong()
            } else {
                trackReplayBaseMs
            }.coerceIn(0L, duration)
            if (current >= duration && duration > 0L) {
                trackReplayBaseMs = duration
                trackReplayPlaying = false
            }
            return current
        }
        pendingSeekGlobalMs?.let { return it }
        if (!playerPrepared) return 0L
        return try {
            segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L } +
                (mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L)
        } catch (_: IllegalStateException) {
            segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L }
        }
    }

    private fun totalDurationMs(): Long {
        val mediaDuration = segmentDurationsMs.sum()
        if (mediaDuration > 0L) return mediaDuration
        return track?.points?.maxOfOrNull { it.elapsedMs } ?: 0L
    }

    private fun seekToGlobal(positionMs: Long) {
        if (trackOnly) {
            trackReplayBaseMs = positionMs.coerceIn(0L, totalDurationMs().coerceAtLeast(0L))
            trackReplayStartElapsedMs = SystemClock.elapsedRealtime()
            syncRouteToVideo()
            updateControls()
            return
        }
        val files = playbackFiles
        if (files.isEmpty()) return
        val clamped = positionMs.coerceIn(0L, totalDurationMs().coerceAtLeast(0L))
        val index = segmentIndexFor(clamped)
        val localMs = (clamped - segmentOffsetsMs.getOrElse(index) { 0L }).coerceAtLeast(0L)
        pendingSeekGlobalMs = clamped
        if (index == currentSegmentIndex && playerPrepared) {
            mediaPlayer?.seekTo(localMs.toInt())
            syncRouteToVideo()
            updateControls()
        } else {
            val surface = videoSurface
            if (surface == null) {
                pendingSeekGlobalMs = null
            } else {
                prepareSegment(files, index, surface, localMs)
            }
        }
    }

    private fun setupVideoMapDivider() {
        videoMapDivider.setOnTouchListener { view, event ->
            if (fullscreen || mapFullscreen || trackOnly || layoutMode == ReplayMapLayout.MODE_FLOAT) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dividerDragStartY = event.rawY
                    dividerDragStartVideoHeight = videoCard.height
                    dividerDragTotalHeight = videoCard.height + infoCard.height
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalHeight = dividerDragTotalHeight.takeIf { it > 0 } ?: (videoCard.height + infoCard.height)
                    if (totalHeight <= 0) return@setOnTouchListener true
                    val minSectionHeight = minOf(dp(150), (totalHeight * 0.35f).toInt()).coerceAtLeast(1)
                    val nextVideoHeight = (dividerDragStartVideoHeight + event.rawY - dividerDragStartY)
                        .toInt()
                        .coerceIn(minSectionHeight, totalHeight - minSectionHeight)
                    videoMapRatio = (nextVideoHeight.toFloat() / totalHeight.toFloat())
                        .coerceIn(MIN_VIDEO_MAP_RATIO, MAX_VIDEO_MAP_RATIO)
                    applyVideoMapRatio(videoMapRatio)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!VideoReplayRotation.isQuarterTurn(videoRotationDegrees)) {
                        flatVideoMapRatio = null
                        persistVideoMapRatio()
                    }
                    view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun applyVideoMapRatio(ratio: Float) {
        videoMapRatio = ratio.coerceIn(MIN_VIDEO_MAP_RATIO, MAX_VIDEO_MAP_RATIO)
        applyReplayLayout()
    }

    private fun applyReplayLayout() {
        val width = replayRoot.width
        val height = replayRoot.height
        if (width <= 0 || height <= 0) {
            replayRoot.post { applyReplayLayout() }
            return
        }
        val floating = layoutMode == ReplayMapLayout.MODE_FLOAT && !trackOnly && !fullscreen && !mapFullscreen
        videoMapDivider.visibility = if (floating || fullscreen || mapFullscreen || trackOnly) {
            View.GONE
        } else {
            View.VISIBLE
        }
        infoCard.edgePx = if (floating) dp(ReplayMapLayout.FLOAT_EDGE_DP) else 0
        if (!trackOnly) tvReplayLayout.visibility = View.VISIBLE
        applyMapChrome(ReplayMapLayout.chrome(layoutMode, mapFullscreen, trackOnly))
        infoCard.cardElevation = if (floating) dp(8).toFloat() else 0f
        infoCard.clipToOutline = floating
        infoCard.radius = when {
            floating -> dp(16).toFloat()
            mapFullscreen -> 0f
            else -> normalInfoRadius
        }
        when {
            trackOnly || mapFullscreen -> {
                videoCard.visibility = View.GONE
                infoCard.visibility = View.VISIBLE
                placeReplayChild(infoCard, 0, 0, width, height)
            }
            fullscreen -> {
                infoCard.visibility = View.GONE
                videoCard.visibility = View.VISIBLE
                placeReplayChild(videoCard, 0, 0, width, height)
            }
            floating -> {
                videoCard.visibility = View.VISIBLE
                infoCard.visibility = View.VISIBLE
                placeReplayChild(videoCard, 0, 0, width, height)
                val window = ReplayMapLayout.frame(floatSpec, width, floatingMapAreaHeight(), dp(160), dp(140))
                placeReplayChild(infoCard, window.left, window.top, window.width, window.height)
                infoCard.bringToFront()
            }
            else -> {
                videoCard.visibility = View.VISIBLE
                infoCard.visibility = View.VISIBLE
                val dividerHeight = dp(18)
                val space = (height - dividerHeight).coerceAtLeast(2)
                val minSection = minOf(dp(150), (space * 0.35f).toInt(), space / 2).coerceAtLeast(1)
                val videoHeight = (space * videoMapRatio).roundToInt()
                    .coerceIn(minSection, space - minSection)
                placeReplayChild(videoCard, 0, 0, width, videoHeight)
                placeReplayChild(videoMapDivider, 0, videoHeight, width, dividerHeight)
                placeReplayChild(
                    infoCard,
                    0,
                    videoHeight + dividerHeight,
                    width,
                    height - videoHeight - dividerHeight
                )
            }
        }
        refreshVideoLayoutAfterResize()
        exportPanel?.keepAboveMap()
    }

    private fun applyMapChrome(chrome: ReplayMapChrome) {
        val textVisibility = if (chrome.showTrackText) View.VISIBLE else View.GONE
        tvReplayTitle.visibility = textVisibility
        tvReplayStats.visibility = textVisibility
        tvReplayPosition.visibility = textVisibility
        mapRouteView.setSpeedCaptionVisible(chrome.showSpeedCaption)
        layoutMapScaleControls.visibility = if (chrome.showScaleControls) View.VISIBLE else View.GONE
        btnOfflineMap.visibility = if (chrome.showOfflineButton) View.VISIBLE else View.GONE
        btnReplayLayout.visibility = if (chrome.showLayoutButton) View.VISIBLE else View.GONE
        val height = if (chrome.compactActions) dp(26) else dp(28)
        val gap = if (chrome.compactActions) dp(4) else dp(6)
        layoutMapActionControls.layoutParams = layoutMapActionControls.layoutParams.apply {
            this.height = height
        }
        listOf(btnMapViewportAll, btnMapViewportCurrent, btnMapType).forEachIndexed { index, button ->
            val params = button.layoutParams as LinearLayout.LayoutParams
            if (chrome.compactActions) {
                params.width = LinearLayout.LayoutParams.WRAP_CONTENT
                params.weight = 0f
            } else {
                params.width = 0
                params.weight = 1f
            }
            params.height = height
            params.marginStart = if (index == 0) 0 else gap
            button.layoutParams = params
            button.minWidth = 0
            button.minimumWidth = 0
            button.minHeight = 0
            button.minimumHeight = 0
            button.insetTop = 0
            button.insetBottom = 0
            button.maxLines = 1
            button.ellipsize = null
            button.setTextColor(android.graphics.Color.WHITE)
            button.textSize = if (chrome.compactActions) 12f else 10f
            if (chrome.compactActions) {
                button.setPaddingRelative(dp(8), 0, dp(8), 0)
            } else {
                button.setPaddingRelative(
                    mapActionPadStart,
                    mapActionPadTop,
                    mapActionPadEnd,
                    mapActionPadBottom
                )
            }
        }
    }

    private fun placeReplayChild(view: View, left: Int, top: Int, width: Int, height: Int) {
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun toggleReplayLayout() {
        if (trackOnly) return
        layoutMode = if (layoutMode == ReplayMapLayout.MODE_FLOAT) {
            ReplayMapLayout.MODE_SPLIT
        } else {
            ReplayMapLayout.MODE_FLOAT
        }
        persistLayoutMode()
        applyReplayLayout()
        updateControls()
        updateMapControls()
    }

    private fun setupFloatMapGestures() {
        floatTouch = FloatWindowTouch(
            edgePx = dp(ReplayMapLayout.FLOAT_EDGE_DP),
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        )
        infoCard.onEdgeTouch = { event -> onFloatWindowTouch(event, bodyOnly = false) }
        mapRouteView.setConsumeTouchListener { event -> onFloatWindowTouch(event, bodyOnly = true) }
    }

    private fun onFloatWindowTouch(event: MotionEvent, bodyOnly: Boolean): Boolean {
        if (layoutMode != ReplayMapLayout.MODE_FLOAT || fullscreen || mapFullscreen || trackOnly) {
            return false
        }
        val parentWidth = replayRoot.width
        val parentHeight = floatingMapAreaHeight()
        val consumed = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (bodyOnly) {
                    floatTouch.downBody(event.rawX, event.rawY, currentFloatFrame(), event.eventTime)
                } else {
                    floatTouch.down(
                        localX = event.x,
                        localY = event.y,
                        rawX = event.rawX,
                        rawY = event.rawY,
                        viewWidth = infoCard.width,
                        viewHeight = infoCard.height,
                        frame = currentFloatFrame(),
                        timeMs = event.eventTime
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val next = floatTouch.move(
                    rawX = event.rawX,
                    rawY = event.rawY,
                    timeMs = event.eventTime,
                    parentWidth = parentWidth,
                    parentHeight = parentHeight,
                    minWidth = dp(160),
                    minHeight = dp(140)
                )
                if (next != null) {
                    floatSpec = ReplayMapLayout.spec(next, parentWidth, parentHeight)
                    applyReplayLayout()
                    replayRoot.requestDisallowInterceptTouchEvent(true)
                }
                floatTouch.consuming()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = floatTouch.up(event.eventTime)
                if (handled) persistFloatSpec()
                handled
            }
            else -> floatTouch.consuming()
        }
        if (floatTouch.takeDragEntry()) {
            infoCard.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        return consumed
    }

    private fun floatingMapAreaHeight(): Int {
        // 首次布局时 videoFrame 还没测量完，用整屏高度兜底，避免算出 1px 的地图窗口。
        val base = (if (videoFrame.height > 0) videoFrame.height else replayRoot.height).coerceAtLeast(1)
        return (base - infoBarHeightPx()).coerceAtLeast(dp(140))
    }

    private fun currentFloatFrame(): ReplayFloatFrame {
        return ReplayMapLayout.frame(
            floatSpec,
            replayRoot.width,
            floatingMapAreaHeight(),
            dp(160),
            dp(140)
        )
    }

    private fun refreshVideoLayoutAfterResize() {
        if (lastVideoWidth <= 0 || lastVideoHeight <= 0) return
        videoFrame.post { updateVideoLayout(lastVideoWidth, lastVideoHeight) }
    }

    private fun loadLayoutMode(): String {
        return ReplayMapLayout.normalizeMode(
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getString(ReplayMapLayout.PREF_LAYOUT_MODE, ReplayMapLayout.MODE_SPLIT)
        )
    }

    private fun persistLayoutMode() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(ReplayMapLayout.PREF_LAYOUT_MODE, layoutMode)
            .apply()
    }

    private fun loadFloatSpec(): ReplayFloatSpec {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        val defaults = ReplayMapLayout.defaultFloatSpec()
        return ReplayFloatSpec(
            leftFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_LEFT, defaults.leftFraction),
            topFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_TOP, defaults.topFraction),
            widthFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_WIDTH, defaults.widthFraction),
            heightFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_HEIGHT, defaults.heightFraction)
        )
    }

    private fun persistFloatSpec() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(ReplayMapLayout.PREF_FLOAT_LEFT, floatSpec.leftFraction)
            .putFloat(ReplayMapLayout.PREF_FLOAT_TOP, floatSpec.topFraction)
            .putFloat(ReplayMapLayout.PREF_FLOAT_WIDTH, floatSpec.widthFraction)
            .putFloat(ReplayMapLayout.PREF_FLOAT_HEIGHT, floatSpec.heightFraction)
            .apply()
    }

    private fun loadVideoMapRatio(): Float {
        return getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .getFloat(PREF_VIDEO_REPLAY_SPLIT_RATIO, DEFAULT_VIDEO_MAP_RATIO)
            .coerceIn(MIN_VIDEO_MAP_RATIO, MAX_VIDEO_MAP_RATIO)
    }

    private fun persistPreviewMemory() {
        ReplayRemembered.savePreview(
            this,
            ReplayPreviewMemory(
                rotationDegrees = videoRotationDegrees,
                satellite = mapRouteView.isSatelliteMap(),
                wholeRoute = mapRouteView.isWholeRoute(),
                scaleIndex = mapRouteView.scaleIndex()
            )
        )
    }

    private fun persistVideoMapRatio() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(PREF_VIDEO_REPLAY_SPLIT_RATIO, videoMapRatio.coerceIn(MIN_VIDEO_MAP_RATIO, MAX_VIDEO_MAP_RATIO))
            .apply()
    }

    private fun segmentIndexFor(positionMs: Long): Int {
        if (segmentOffsetsMs.isEmpty()) return 0
        return segmentOffsetsMs.indexOfLast { it <= positionMs }.coerceIn(0, videoFiles.lastIndex)
    }

    private fun segmentDurationMs(context: android.content.Context, file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            duration.coerceAtLeast(0L)
        } catch (_: RuntimeException) {
            VideoTrackStorage.readTrack(context, file)?.points?.maxOfOrNull { it.elapsedMs } ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun rotatePlayback(visualClockwiseDegrees: Int) {
        val next = VideoReplayRotation.normalize(videoRotationDegrees + visualClockwiseDegrees)
        val turningUpright = VideoReplayRotation.isQuarterTurn(videoRotationDegrees) &&
            !VideoReplayRotation.isQuarterTurn(next)
        videoRotationDegrees = next
        if (!fullscreen && !mapFullscreen && !trackOnly && lastVideoWidth > 0 && replayRoot.height > 0) {
            if (VideoReplayRotation.isQuarterTurn(videoRotationDegrees)) {
                if (flatVideoMapRatio == null) flatVideoMapRatio = videoMapRatio
                applyVideoMapRatio(
                    VideoReplayRotation.videoAreaFraction(
                        replayRoot.width,
                        replayRoot.height,
                        lastVideoWidth,
                        lastVideoHeight,
                        videoRotationDegrees,
                        MIN_VIDEO_MAP_RATIO,
                        MAX_VIDEO_MAP_RATIO
                    )
                )
            } else if (turningUpright) {
                applyVideoMapRatio(flatVideoMapRatio ?: videoMapRatio)
                flatVideoMapRatio = null
            }
        }
        mapRouteView.setContentRotation(videoRotationDegrees)
        applyVideoStage(lastVideoWidth, lastVideoHeight)
        persistPreviewMemory()
        showControls()
    }

    private fun updateVideoLayout(videoWidth: Int, videoHeight: Int) {
        applyVideoStage(videoWidth, videoHeight)
    }

    /** 参数栏跟着画面一起转，明细最多三行，按三行留高度；导出时才换算到视频像素。 */
    private fun infoBarHeightPx(): Int =
        if (currentSubtitleFields().hasAnyField()) dp(ExportSubtitleStyle.barHeightDp()) else 0

    /** 导出面板打开时用面板里的勾选，否则跟随设置页的字幕开关。 */
    private fun currentSubtitleFields(): ShareSubtitleFields =
        exportPanel?.takeIf { it.isShowing() }?.subtitleFields() ?: DashcamSettings.subtitleFields(this)

    private fun applyVideoStage(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight
        if (videoFrame.width <= 0 || videoFrame.height <= 0) {
            videoFrame.post { applyVideoStage(videoWidth, videoHeight) }
            return
        }
        val barPx = infoBarHeightPx()
        val (contentWidth, contentHeight) = VideoReplayRotation.fittedStageSize(
            frameWidth = videoFrame.width,
            frameHeight = videoFrame.height,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            barPx = barPx,
            rotationDegrees = videoRotationDegrees
        )
        if (videoStage.layoutParams?.width != contentWidth || videoStage.layoutParams?.height != contentHeight) {
            videoStage.layoutParams = FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER)
        }
        // 画面占上部，白底栏占底部固定高度，都由代码给定，不依赖权重测量。
        val textureHeight = (contentHeight - barPx).coerceAtLeast(1)
        if (videoTextureView.layoutParams?.height != textureHeight) {
            videoTextureView.layoutParams = FrameLayout.LayoutParams(contentWidth, textureHeight, Gravity.TOP)
        }
        // 字幕开关全关时整条白栏收起，画面独占。
        tvSubtitleOverlay.visibility = if (barPx > 0) View.VISIBLE else View.GONE
        if (tvSubtitleOverlay.layoutParams?.width != contentWidth || tvSubtitleOverlay.layoutParams?.height != barPx) {
            tvSubtitleOverlay.layoutParams = FrameLayout.LayoutParams(contentWidth, barPx, Gravity.BOTTOM)
        }
        videoStage.pivotX = contentWidth / 2f
        videoStage.pivotY = contentHeight / 2f
        videoStage.rotation = videoRotationDegrees.toFloat()
        videoTextureView.setTransform(null)
    }

    private fun applyPlaybackSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun currentPlaybackSpeed(): Float = PLAYBACK_SPEEDS[playbackSpeedIndex]

    private fun playbackSpeedLabel(): String {
        val speed = currentPlaybackSpeed()
        return if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
    }

    private fun showExportPanel() {
        if (trackOnly || videoFiles.isEmpty()) return
        val panel = exportPanel ?: ReplayExportPanel(
            activity = this,
            initial = ReplayRemembered.exportSettings(this),
            onSpeedSelected = { index ->
                if (playbackSpeedIndex != index) {
                    playbackSpeedIndex = index
                    applyPlaybackSpeed()
                    updateControls()
                }
            },
            onChanged = { settings ->
                ReplayRemembered.saveExport(this, settings)
                // 导出面板和设置页共用同一组字幕开关，两处始终保持一致。
                DashcamSettings.saveSubtitleFields(this, settings.fields)
                // 字幕开关变了白栏可能整条收起或重新出现，画面高度要跟着重算。
                if (lastVideoWidth > 0 && lastVideoHeight > 0) {
                    applyVideoStage(lastVideoWidth, lastVideoHeight)
                    applyReplayLayout()
                }
            },
            onExport = { shareAfter -> exportVideo(shareAfter) }
        ).also { exportPanel = it }
        panel.show(replayRoot, totalDurationMs(), playbackSpeedIndex)
    }

    private fun exportVideo(shareAfter: Boolean) {
        val panel = exportPanel ?: return
        if (exportRunning || videoFiles.isEmpty() || trackOnly) return
        if (fullscreen) setFullscreen(false)
        if (mapFullscreen) setMapFullscreen(false)
        try {
            mediaPlayer?.pause()
        } catch (_: IllegalStateException) {
            // 导出时停住预览，避免和截图抢地图。
        }
        exportRunning = true
        exportCancelled = false
        panel.setBusy(true)
        panel.setProgress(0, getString(R.string.share_export_running, 0))
        val screenWidth = replayRoot.width.coerceAtLeast(1)
        val barHeight = if (lastVideoWidth > 0 && lastVideoHeight > 0) {
            ExportSubtitleStyle.barHeightPx(lastVideoWidth, lastVideoHeight)
        } else {
            dp(ExportSubtitleStyle.barHeightDp())
        }
        val orientedWidth = if (VideoReplayRotation.isQuarterTurn(videoRotationDegrees)) {
            lastVideoWidth + barHeight
        } else {
            lastVideoWidth
        }
        val cornerRadius = if (orientedWidth > 0) {
            dp(16) * orientedWidth / screenWidth.toFloat()
        } else {
            dp(16).toFloat()
        }
        val options = ShareExportOptions(
            includeRouteMap = panel.includeMap(),
            mapHeightPercent = ((1f - videoMapRatio) * 100f).roundToInt().coerceIn(18, 70),
            mapScalePercent = 100,
            sourceStartMs = panel.selectedStartMs(),
            sourceEndMs = panel.selectedEndMs(),
            playbackSpeed = panel.selectedSpeed(),
            subtitleFields = panel.subtitleFields(),
            fps = panel.selectedFps(),
            layoutMode = layoutMode,
            floatSpec = floatSpec,
            rotationDegrees = videoRotationDegrees,
            mapCornerRadiusPx = cornerRadius,
            mapAspectRatio = exportMapAspectRatio()
        )
        val files = videoFiles
        exportWorker.execute {
            try {
                val output = ShareVideoExporter.export(
                    context = this,
                    videos = files,
                    options = options,
                    onProgress = { percent ->
                        mainHandler.post {
                            if (isDestroyed) return@post
                            panel.setProgress(percent, getString(R.string.share_export_running, percent))
                        }
                    },
                    mapFrameAt = { elapsedMs -> captureMapFrame(elapsedMs) },
                    shouldContinue = { !exportCancelled && !Thread.currentThread().isInterrupted }
                )
                if (exportCancelled) {
                    output.delete()
                    throw ExportCancelled()
                }
                val published = VideoStorage.publishShareVideoToGallery(this, output)
                cleanupPrivateExports(output, published, keepOutput = shareAfter)
                mainHandler.post {
                    exportRunning = false
                    if (isDestroyed) return@post
                    panel.setBusy(false)
                    panel.setStatus(getString(R.string.share_export_done, published.name))
                    if (shareAfter) shareExportedVideo(output)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    exportRunning = false
                    if (isDestroyed) return@post
                    panel.setBusy(false)
                    if (error !is ExportCancelled) {
                        panel.setStatus(getString(R.string.share_export_failed, error.message ?: error.javaClass.simpleName))
                    }
                }
            }
        }
    }

    /** 成片已经复制进相册，私有目录那份只在要分享时留着，下次导出再清掉。 */
    private fun cleanupPrivateExports(output: File, published: File, keepOutput: Boolean) {
        val dir = output.parentFile ?: return
        val sameDir = runCatching { published.parentFile?.canonicalPath == dir.canonicalPath }.getOrDefault(true)
        if (sameDir) return
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".mp4") || !file.name.contains("-share-")) return@forEach
            if (keepOutput && file.absolutePath == output.absolutePath) return@forEach
            file.delete()
        }
    }

    private fun exportMapAspectRatio(): Float {
        if (layoutMode != ReplayMapLayout.MODE_FLOAT || replayRoot.width <= 0 || replayRoot.height <= 0) return 0f
        val window = currentFloatFrame()
        if (window.width <= 0 || window.height <= 0) return 0f
        return window.width.toFloat() / window.height.toFloat()
    }

    private fun captureMapFrame(elapsedMs: Long): Bitmap? {
        if (isDestroyed || exportCancelled) return null
        val latch = CountDownLatch(1)
        val lock = Any()
        var shot: Bitmap? = null
        var abandoned = false
        mainHandler.post {
            if (isDestroyed) {
                latch.countDown()
                return@post
            }
            mapRouteView.captureFrame(track?.points.orEmpty(), elapsedMs) { bitmap ->
                synchronized(lock) {
                    if (abandoned) bitmap?.recycle() else shot = bitmap
                }
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        synchronized(lock) {
            if (shot == null) abandoned = true
            return shot
        }
    }

    private fun shareExportedVideo(video: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", video)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_export_share_title)))
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_PATHS = "video_paths"
        const val EXTRA_TRACK_ONLY = "track_only"
        const val EXTRA_TRACK_START_MS = "track_start_ms"
        const val EXTRA_TRACK_END_MS = "track_end_ms"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_OPEN_EXPORT = "open_export"
        private const val PREF_VIDEO_REPLAY_SPLIT_RATIO = "video_replay_split_ratio"
        private const val DEFAULT_VIDEO_MAP_RATIO = 0.432f
        private const val MIN_VIDEO_MAP_RATIO = 0.15f
        private const val MAX_VIDEO_MAP_RATIO = 0.85f
        private const val CONTROLS_AUTO_HIDE_MS = 1_500L
        private const val PLATE_REPLAY_HOLD_MS = 1_500L
        private const val SUBTITLE_GAP_HOLD_MS = 1_500L
        private val PLAYBACK_SPEEDS = ExportFps.SPEEDS
    }
}

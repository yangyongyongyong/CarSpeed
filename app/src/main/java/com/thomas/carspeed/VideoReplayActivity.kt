package com.thomas.carspeed

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoReplayActivity : AppCompatActivity() {
    private lateinit var replayRoot: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var videoCard: MaterialCardView
    private lateinit var infoCard: MaterialCardView
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
    private lateinit var tvSubtitleOverlay: TextView
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
    private lateinit var tvMapScale: TextView
    private lateinit var btnMapViewportAll: MaterialButton
    private lateinit var btnMapViewportCurrent: MaterialButton
    private lateinit var btnMapType: MaterialButton
    private lateinit var btnOfflineMap: MaterialButton
    private lateinit var btnMapScaleOut: MaterialButton
    private lateinit var btnMapScaleIn: MaterialButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoFile: File? = null
    private var videoFiles: List<File> = emptyList()
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
    private var dividerDragStartY = 0f
    private var dividerDragStartVideoHeight = 0
    private var dividerDragTotalHeight = 0
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var playbackSpeedIndex = 0
    private var radarLookup: HistoricalRadarLookup? = null

    private val syncTicker = object : Runnable {
        override fun run() {
            syncRouteToVideo()
            updateControls()
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
        tvMapScale = findViewById(R.id.tvMapScale)
        btnMapViewportAll = findViewById(R.id.btnMapViewportAll)
        btnMapViewportCurrent = findViewById(R.id.btnMapViewportCurrent)
        btnMapType = findViewById(R.id.btnMapType)
        btnOfflineMap = findViewById(R.id.btnOfflineMap)
        btnMapScaleOut = findViewById(R.id.btnMapScaleOut)
        btnMapScaleIn = findViewById(R.id.btnMapScaleIn)
        videoCard.clipToOutline = false
        videoCard.clipChildren = false
        videoFrame.clipChildren = false
        infoCard.clipToOutline = false
        infoCard.clipChildren = false
        mapFrame.clipChildren = false
        normalVideoRadius = videoCard.radius
        normalInfoRadius = infoCard.radius
        videoMapRatio = loadVideoMapRatio()
        applyVideoMapRatio(videoMapRatio)
        applyPlaybackSystemUi()

        toolbar.setNavigationOnClickListener { finish() }
        tvReplayPlayPause.setOnClickListener { togglePlayPause() }
        tvMapReplayPlayPause.setOnClickListener { togglePlayPause() }
        tvReplaySpeed.setOnClickListener { switchPlaybackSpeed() }
        tvMapReplaySpeed.setOnClickListener { switchPlaybackSpeed() }
        tvReplayFullscreen.setOnClickListener { setFullscreen(!fullscreen) }
        tvReplayRotate.setOnClickListener { rotatePlayback(90) }
        mapRouteView.onMapTap = { if (!mapFullscreen) setMapFullscreen(true) }
        mapRouteView.onPhotoClick = { path -> openRoutePhoto(path) }
        mapRouteView.onRadarClick = { radar -> showRadarDetail(radar) }
        mapRouteView.onMaxSpeedClick = { speedKmh -> showMaxSpeed(speedKmh) }
        mapRouteView.onScaleChanged = { updateMapControls() }
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
        updateMapControls()
        setupSeekBar(seekReplayProgress)
        setupSeekBar(seekMapReplayProgress)
        if (intent.getBooleanExtra(EXTRA_TRACK_ONLY, false)) {
            setupTrackOnlyReplay()
            return
        }
        val files = intent.getStringArrayListExtra(EXTRA_VIDEO_PATHS)
            ?.map { File(it) }
            ?.filter { it.exists() }
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_VIDEO_PATH)?.let { listOf(File(it)) }.orEmpty().filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoFiles = files
        videoFile = files.first()
        segmentDurationsMs = files.map { segmentDurationMs(it) }
        segmentOffsetsMs = segmentDurationsMs.runningFold(0L) { total, duration -> total + duration }.dropLast(1)
        track = VideoTrackStorage.readMergedTrack(this, files, segmentOffsetsMs, segmentDurationsMs)
        trackSummary = track?.let { TrackMetrics.summary(it.points) }
        subtitleCues = SrtSubtitle.readCues(files, segmentOffsetsMs, segmentDurationsMs)
        plateSamples = PlateRecognitionStorage.readSamples(this, files, segmentOffsetsMs, segmentDurationsMs)
        setupVideo(files)
        renderStaticInfo(files)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(syncTicker)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(syncTicker)
        mainHandler.removeCallbacks(hideControlsRunnable)
        mediaPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        radarLookup?.cancel()
        radarLookup = null
        mainHandler.removeCallbacks(syncTicker)
        mainHandler.removeCallbacks(hideControlsRunnable)
        releasePlayer()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (mapFullscreen) {
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
        val record = DriveRecordStore.loadRecords(this).firstOrNull { record ->
            record.startTimeMs == startMs && record.endTimeMs == endMs && record.routePoints.isNotEmpty()
        }
        if (record == null) {
            Toast.makeText(this, R.string.video_replay_no_track, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        trackOnly = true
        trackReplayPlaying = true
        trackReplayBaseMs = 0L
        trackReplayStartElapsedMs = SystemClock.elapsedRealtime()
        videoCard.visibility = View.GONE
        videoMapDivider.visibility = View.GONE
        layoutReplayControls.visibility = View.GONE
        layoutMapReplayControls.visibility = View.VISIBLE
        tvReplayFullscreen.visibility = View.GONE
        infoCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply { topMargin = 0 }
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
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setSurface(surface)
            setOnPreparedListener { player ->
                playerPrepared = true
                updateVideoLayout(player.videoWidth, player.videoHeight)
                if (seekMs > 0L) {
                    pendingSeekGlobalMs = segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L } + seekMs
                    player.seekTo(seekMs.coerceAtMost(player.duration.toLong()).toInt())
                }
                applyPlaybackSpeed(player)
                syncRouteToVideo()
                if (autoStart) player.start()
                showControls()
            }
            setOnSeekCompleteListener {
                pendingSeekGlobalMs = null
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
                Toast.makeText(this@VideoReplayActivity, R.string.video_replay_failed, Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
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
        try {
            val params = try {
                target.playbackParams
            } catch (_: IllegalStateException) {
                PlaybackParams()
            }
            target.playbackParams = params
                .setSpeed(currentPlaybackSpeed())
                .setPitch(1f)
                .allowDefaults()
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.video_replay_speed_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!wasPlaying) {
            try {
                if (target.isPlaying) target.pause()
            } catch (_: IllegalStateException) {
                // 保持原暂停状态即可。
            }
        }
    }

    private fun setupSeekBar(target: SeekBar) {
        target.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                mainHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: return
                seekToGlobal(progress.toLong())
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
        val current = currentVideoPositionMs().coerceAtMost(duration).toInt()
        seekReplayProgress.max = duration.toInt()
        seekMapReplayProgress.max = duration.toInt()
        if (!userSeeking) {
            seekReplayProgress.progress = current
            seekMapReplayProgress.progress = current
        }
        tvReplayTime.text = "${formatShortDuration(current.toLong())}/${formatShortDuration(duration)}"
        tvMapReplayTime.text = tvReplayTime.text
        tvReplayPlayPause.text = if (playerPrepared && mediaPlayer?.isPlaying == true) "暂停" else "播放"
        if (trackOnly) tvReplayPlayPause.text = if (trackReplayPlaying) "暂停" else "播放"
        tvMapReplayPlayPause.text = tvReplayPlayPause.text
        tvReplaySpeed.text = playbackSpeedLabel()
        tvMapReplaySpeed.text = tvReplaySpeed.text
        tvReplayFullscreen.text = if (fullscreen) "退出" else "全屏"
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
        if (enabled) {
            videoCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = 0 }
        } else {
            applyVideoMapRatio(videoMapRatio)
        }
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
        if (enabled) {
            infoCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = 0 }
        } else {
            applyVideoMapRatio(videoMapRatio)
        }
        applyPlaybackSystemUi()
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
        videoFiles.forEachIndexed { index, file ->
            val existing = VideoTrackStorage.readTrack(this, file) ?: return@forEachIndexed
            val offset = segmentOffsetsMs.getOrElse(index) { 0L }
            val duration = segmentDurationsMs.getOrElse(index) { Long.MAX_VALUE }
            val segmentRadars = radars.mapNotNull { radar ->
                val localElapsed = radar.elapsedMs - offset
                if (localElapsed < -2_000L || localElapsed > duration + 2_000L) null
                else radar.copy(elapsedMs = localElapsed.coerceAtLeast(0L))
            }
            if (segmentRadars.isEmpty()) return@forEachIndexed
            VideoTrackStorage.writeTrack(
                this,
                file,
                existing.copy(radars = RouteRadarMarks.uniqueByLocation(segmentRadars))
            )
        }
    }

    private fun routePhotoCandidates(): List<RoutePhotoCandidate> {
        return VideoStorage.photoFiles(this).mapNotNull { file ->
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
                formatDuration(elapsedMs),
                point.speedKmh,
                TrackMetrics.formatDistance(currentDistance),
                TrackMetrics.formatDistance(totalDistance),
                point.latitude,
                point.longitude,
                formatTime(point.timeMs)
            )
        }
    }

    private fun syncSubtitle(elapsedMs: Long) {
        val cue = SrtSubtitle.cueAt(subtitleCues, elapsedMs, SUBTITLE_GAP_HOLD_MS)
        if (cue == null) {
            tvSubtitleOverlay.visibility = View.GONE
        } else {
            tvSubtitleOverlay.text = cue.text
            tvSubtitleOverlay.visibility = View.VISIBLE
        }
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
        val files = videoFiles
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
            if (fullscreen || mapFullscreen || trackOnly) return@setOnTouchListener false
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
        val clampedRatio = ratio.coerceIn(MIN_VIDEO_MAP_RATIO, MAX_VIDEO_MAP_RATIO)
        videoMapRatio = clampedRatio
        videoCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            clampedRatio
        ).apply { topMargin = 0 }
        infoCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f - clampedRatio
        ).apply { topMargin = 0 }
        refreshVideoLayoutAfterResize()
    }

    private fun refreshVideoLayoutAfterResize() {
        if (lastVideoWidth <= 0 || lastVideoHeight <= 0) return
        videoFrame.post { updateVideoLayout(lastVideoWidth, lastVideoHeight) }
    }

    private fun loadVideoMapRatio(): Float {
        return getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .getFloat(PREF_VIDEO_REPLAY_SPLIT_RATIO, DEFAULT_VIDEO_MAP_RATIO)
            .coerceIn(MIN_VIDEO_MAP_RATIO, MAX_VIDEO_MAP_RATIO)
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

    private fun segmentDurationMs(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            duration.coerceAtLeast(0L)
        } catch (_: RuntimeException) {
            VideoTrackStorage.readTrack(this, file)?.points?.maxOfOrNull { it.elapsedMs } ?: 0L
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
        showControls()
    }

    private fun updateVideoLayout(videoWidth: Int, videoHeight: Int) {
        applyVideoStage(videoWidth, videoHeight)
    }

    private fun applyVideoStage(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight
        if (videoFrame.width <= 0 || videoFrame.height <= 0) {
            videoFrame.post { applyVideoStage(videoWidth, videoHeight) }
            return
        }
        val (contentWidth, contentHeight) = VideoReplayRotation.fittedContentSize(
            videoFrame.width,
            videoFrame.height,
            videoWidth,
            videoHeight,
            videoRotationDegrees
        )
        if (videoStage.layoutParams?.width != contentWidth || videoStage.layoutParams?.height != contentHeight) {
            videoStage.layoutParams = FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER)
        }
        videoStage.pivotX = contentWidth / 2f
        videoStage.pivotY = contentHeight / 2f
        videoStage.rotation = videoRotationDegrees.toFloat()
        val subtitleParams = tvSubtitleOverlay.layoutParams as FrameLayout.LayoutParams
        val bottomMargin = if (VideoReplayRotation.isQuarterTurn(videoRotationDegrees)) dp(12) else dp(64)
        if (subtitleParams.bottomMargin != bottomMargin) {
            subtitleParams.bottomMargin = bottomMargin
            tvSubtitleOverlay.layoutParams = subtitleParams
        }
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

    private fun formatShortDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun currentPlaybackSpeed(): Float = PLAYBACK_SPEEDS[playbackSpeedIndex]

    private fun playbackSpeedLabel(): String {
        return when (val speed = currentPlaybackSpeed()) {
            1f -> "1x"
            1.5f -> "1.5x"
            2f -> "2x"
            2.5f -> "2.5x"
            3f -> "3x"
            5f -> "5x"
            10f -> "10x"
            else -> String.format(Locale.US, "%.1fx", speed)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "--"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_PATHS = "video_paths"
        const val EXTRA_TRACK_ONLY = "track_only"
        const val EXTRA_TRACK_START_MS = "track_start_ms"
        const val EXTRA_TRACK_END_MS = "track_end_ms"
        const val EXTRA_TRACK_TITLE = "track_title"
        private const val PREF_VIDEO_REPLAY_SPLIT_RATIO = "video_replay_split_ratio"
        private const val DEFAULT_VIDEO_MAP_RATIO = 0.432f
        private const val MIN_VIDEO_MAP_RATIO = 0.15f
        private const val MAX_VIDEO_MAP_RATIO = 0.85f
        private const val CONTROLS_AUTO_HIDE_MS = 1_500L
        private const val PLATE_REPLAY_HOLD_MS = 1_500L
        private const val SUBTITLE_GAP_HOLD_MS = 1_500L
        private val PLAYBACK_SPEEDS = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 5f, 10f)
    }
}

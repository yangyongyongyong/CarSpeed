package com.thomas.carspeed

import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
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
    private lateinit var videoTextureView: TextureView
    private lateinit var mapFrame: FrameLayout
    private lateinit var mapRouteView: MapRouteView
    private lateinit var tvReplayTitle: TextView
    private lateinit var tvReplayStats: TextView
    private lateinit var tvReplayPosition: TextView
    private lateinit var tvSubtitleOverlay: TextView
    private lateinit var layoutReplayControls: LinearLayout
    private lateinit var layoutMapReplayControls: LinearLayout
    private lateinit var tvReplayPlayPause: TextView
    private lateinit var tvMapReplayPlayPause: TextView
    private lateinit var seekReplayProgress: SeekBar
    private lateinit var seekMapReplayProgress: SeekBar
    private lateinit var tvReplayTime: TextView
    private lateinit var tvMapReplayTime: TextView
    private lateinit var tvReplayFullscreen: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoFile: File? = null
    private var videoFiles: List<File> = emptyList()
    private var segmentDurationsMs: List<Long> = emptyList()
    private var segmentOffsetsMs: List<Long> = emptyList()
    private var currentSegmentIndex = 0
    private var track: VideoTrack? = null
    private var trackSummary: TrackSummary? = null
    private var subtitleCues: List<SrtCue> = emptyList()
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var playerPrepared = false
    private var controlsVisible = true
    private var fullscreen = false
    private var mapFullscreen = false
    private var userSeeking = false
    private var normalVideoRadius = 0f
    private var normalInfoRadius = 0f
    private var normalVideoSlotWidth = 0
    private var normalVideoSlotHeight = 0

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
        videoTextureView = findViewById(R.id.videoTextureView)
        mapFrame = findViewById(R.id.mapFrame)
        mapRouteView = findViewById(R.id.mapRouteView)
        tvReplayTitle = findViewById(R.id.tvReplayTitle)
        tvReplayStats = findViewById(R.id.tvReplayStats)
        tvReplayPosition = findViewById(R.id.tvReplayPosition)
        tvSubtitleOverlay = findViewById(R.id.tvSubtitleOverlay)
        layoutReplayControls = findViewById(R.id.layoutReplayControls)
        layoutMapReplayControls = findViewById(R.id.layoutMapReplayControls)
        tvReplayPlayPause = findViewById(R.id.tvReplayPlayPause)
        tvMapReplayPlayPause = findViewById(R.id.tvMapReplayPlayPause)
        seekReplayProgress = findViewById(R.id.seekReplayProgress)
        seekMapReplayProgress = findViewById(R.id.seekMapReplayProgress)
        tvReplayTime = findViewById(R.id.tvReplayTime)
        tvMapReplayTime = findViewById(R.id.tvMapReplayTime)
        tvReplayFullscreen = findViewById(R.id.tvReplayFullscreen)
        normalVideoRadius = videoCard.radius
        normalInfoRadius = infoCard.radius

        toolbar.setNavigationOnClickListener { finish() }
        tvReplayPlayPause.setOnClickListener { togglePlayPause() }
        tvMapReplayPlayPause.setOnClickListener { togglePlayPause() }
        tvReplayFullscreen.setOnClickListener { setFullscreen(!fullscreen) }
        mapRouteView.onMapTap = { if (!mapFullscreen) setMapFullscreen(true) }
        setupSeekBar(seekReplayProgress)
        setupSeekBar(seekMapReplayProgress)
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
        track = VideoTrackStorage.readMergedTrack(files)
        trackSummary = track?.let { TrackMetrics.summary(it.points) }
        subtitleCues = readSubtitleCues(files)
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
                if (seekMs > 0L) player.seekTo(seekMs.coerceAtMost(player.duration.toLong()).toInt())
                syncRouteToVideo()
                if (autoStart) player.start()
                showControls()
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
        layoutReplayControls.visibility = View.VISIBLE
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
        tvMapReplayPlayPause.text = tvReplayPlayPause.text
        tvReplayFullscreen.text = if (fullscreen) "退出" else "全屏"
    }

    private fun setFullscreen(enabled: Boolean) {
        if (enabled && mapFullscreen) setMapFullscreen(false)
        fullscreen = enabled
        toolbar.visibility = if (enabled) View.GONE else View.VISIBLE
        infoCard.visibility = if (enabled) View.GONE else View.VISIBLE
        replayRoot.setPadding(
            if (enabled) 0 else dp(16),
            if (enabled) 0 else dp(18),
            if (enabled) 0 else dp(16),
            if (enabled) 0 else dp(16)
        )
        replayRoot.setBackgroundColor(
            if (enabled) android.graphics.Color.BLACK else androidx.core.content.ContextCompat.getColor(this, R.color.brand_sand)
        )
        videoCard.radius = if (enabled) 0f else normalVideoRadius
        videoCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            if (enabled) 1f else 1.1f
        ).apply {
            topMargin = if (enabled) 0 else dp(12)
        }
        window.decorView.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        mediaPlayer?.let { player -> videoFrame.post { updateVideoLayout(player.videoWidth, player.videoHeight) } }
        showControls()
        updateControls()
    }

    private fun setMapFullscreen(enabled: Boolean) {
        if (fullscreen) return
        mapFullscreen = enabled
        toolbar.visibility = if (enabled) View.GONE else View.VISIBLE
        videoCard.visibility = if (enabled) View.GONE else View.VISIBLE
        layoutMapReplayControls.visibility = if (enabled) View.VISIBLE else View.GONE
        tvReplayTitle.visibility = if (enabled) View.GONE else View.VISIBLE
        tvReplayStats.visibility = if (enabled) View.GONE else View.VISIBLE
        tvReplayPosition.visibility = if (enabled) View.GONE else View.VISIBLE
        replayRoot.setPadding(
            if (enabled) 0 else dp(16),
            if (enabled) 0 else dp(18),
            if (enabled) 0 else dp(16),
            if (enabled) 0 else dp(16)
        )
        replayRoot.setBackgroundColor(
            if (enabled) android.graphics.Color.BLACK else androidx.core.content.ContextCompat.getColor(this, R.color.brand_sand)
        )
        infoCard.radius = if (enabled) 0f else normalInfoRadius
        infoContent.setPadding(
            if (enabled) 0 else dp(14),
            if (enabled) 0 else dp(14),
            if (enabled) 0 else dp(14),
            if (enabled) 0 else dp(14)
        )
        infoCard.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            if (enabled) 1f else 1f
        ).apply { topMargin = if (enabled) 0 else dp(12) }
        window.decorView.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun renderStaticInfo(files: List<File>) {
        val title = files.firstOrNull()?.parentFile?.takeIf { files.size > 1 }?.name ?: files.first().name
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
        mapRouteView.setRoute(points, 0L)
    }

    private fun syncRouteToVideo() {
        val points = track?.points.orEmpty()
        val elapsedMs = currentVideoPositionMs()
        mapRouteView.setRoute(points, elapsedMs)
        syncSubtitle(elapsedMs)
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
        val cue = subtitleCues.firstOrNull { elapsedMs in it.startMs until it.endMs }
        if (cue == null) {
            tvSubtitleOverlay.visibility = View.GONE
        } else {
            tvSubtitleOverlay.text = cue.text
            tvSubtitleOverlay.visibility = View.VISIBLE
        }
    }

    private fun currentVideoPositionMs(): Long {
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
        val files = videoFiles
        if (files.isEmpty()) return
        val clamped = positionMs.coerceIn(0L, totalDurationMs().coerceAtLeast(0L))
        val index = segmentIndexFor(clamped)
        val localMs = (clamped - segmentOffsetsMs.getOrElse(index) { 0L }).coerceAtLeast(0L)
        if (index == currentSegmentIndex && playerPrepared) {
            mediaPlayer?.seekTo(localMs.toInt())
            syncRouteToVideo()
        } else {
            videoSurface?.let { prepareSegment(files, index, it, localMs) }
        }
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
            VideoTrackStorage.readTrack(file)?.points?.maxOfOrNull { it.elapsedMs } ?: 0L
        }
    }

    private fun updateVideoLayout(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (!fullscreen) {
            updateNormalVideoCardLayout(videoWidth, videoHeight)
            return
        }
        if (videoFrame.width <= 0 || videoFrame.height <= 0) {
            videoFrame.post { updateVideoLayout(videoWidth, videoHeight) }
            return
        }
        val frameWidth = videoFrame.width.toFloat()
        val frameHeight = videoFrame.height.toFloat()
        val displayWidth = videoWidth.toFloat()
        val displayHeight = videoHeight.toFloat()
        val scale = minOf(frameWidth / displayWidth, frameHeight / displayHeight)
        val targetWidth = (displayWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (displayHeight * scale).toInt().coerceAtLeast(1)
        val params = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        if (videoTextureView.layoutParams?.width != targetWidth || videoTextureView.layoutParams?.height != targetHeight) {
            videoTextureView.layoutParams = params
        }
        videoTextureView.setTransform(null)
    }

    private fun updateNormalVideoCardLayout(videoWidth: Int, videoHeight: Int) {
        if (videoCard.width <= 0 || videoCard.height <= 0) {
            videoCard.post { updateVideoLayout(videoWidth, videoHeight) }
            return
        }
        val params = videoCard.layoutParams as? LinearLayout.LayoutParams
        if (normalVideoSlotWidth <= 0 || normalVideoSlotHeight <= 0 || (params?.weight ?: 0f) > 0f) {
            normalVideoSlotWidth = videoCard.width
            normalVideoSlotHeight = videoCard.height
        }
        val slotWidth = normalVideoSlotWidth.coerceAtLeast(1)
        val slotHeight = normalVideoSlotHeight.coerceAtLeast(1)
        val scale = minOf(slotWidth.toFloat() / videoWidth.toFloat(), slotHeight.toFloat() / videoHeight.toFloat())
        val targetWidth = (videoWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (videoHeight * scale).toInt().coerceAtLeast(1)
        val targetParams = LinearLayout.LayoutParams(targetWidth, targetHeight).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        }
        val currentParams = videoCard.layoutParams as? LinearLayout.LayoutParams
        if (currentParams?.width != targetWidth || currentParams.height != targetHeight || currentParams.weight != 0f) {
            videoCard.layoutParams = targetParams
        }
        val textureParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        if (videoTextureView.layoutParams?.width != FrameLayout.LayoutParams.MATCH_PARENT ||
            videoTextureView.layoutParams?.height != FrameLayout.LayoutParams.MATCH_PARENT
        ) {
            videoTextureView.layoutParams = textureParams
        }
        videoTextureView.setTransform(null)
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "--"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    private fun readSubtitleCues(videos: List<File>): List<SrtCue> {
        return videos.flatMapIndexed { index, video ->
            val subtitle = VideoStorage.subtitleFileFor(video)
            if (!subtitle.exists()) return@flatMapIndexed emptyList()
            val offsetMs = segmentOffsetsMs.getOrElse(index) { 0L }
            try {
                subtitle.readText(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                    .split(Regex("\n{2,}"))
                    .mapNotNull { block -> parseSrtBlock(block, offsetMs) }
            } catch (_: RuntimeException) {
                emptyList()
            }
        }
    }

    private fun parseSrtBlock(block: String, offsetMs: Long): SrtCue? {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.size < 3) return null
        val timeLine = lines.firstOrNull { it.contains("-->") } ?: return null
        val match = SRT_TIME_REGEX.find(timeLine) ?: return null
        val startMs = offsetMs + parseSrtTime(match.groupValues, 1)
        val endMs = offsetMs + parseSrtTime(match.groupValues, 5)
        if (endMs <= startMs) return null
        val text = lines.dropWhile { !it.contains("-->") }
            .drop(1)
            .joinToString("\n")
            .replace("  |  ", "\n")
            .trim()
        if (text.isBlank()) return null
        return SrtCue(startMs, endMs, text)
    }

    private fun parseSrtTime(values: List<String>, offset: Int): Long {
        val hours = values[offset].toLongOrNull() ?: 0L
        val minutes = values[offset + 1].toLongOrNull() ?: 0L
        val seconds = values[offset + 2].toLongOrNull() ?: 0L
        val millis = values[offset + 3].toLongOrNull() ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private data class SrtCue(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_PATHS = "video_paths"
        private const val CONTROLS_AUTO_HIDE_MS = 1_500L
        private val SRT_TIME_REGEX = Regex(
            "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s+-->\\s+(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})"
        )
    }
}

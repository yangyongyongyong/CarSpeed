package com.thomas.carspeed

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoReplayActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var videoTextureView: TextureView
    private lateinit var routeMapView: RouteMapView
    private lateinit var tvReplayTitle: TextView
    private lateinit var tvReplayStats: TextView
    private lateinit var tvReplayPosition: TextView
    private lateinit var mediaController: MediaController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoFile: File? = null
    private var track: VideoTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var playerPrepared = false

    private val syncTicker = object : Runnable {
        override fun run() {
            syncRouteToVideo()
            mainHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_replay)

        toolbar = findViewById(R.id.toolbar)
        videoTextureView = findViewById(R.id.videoTextureView)
        routeMapView = findViewById(R.id.routeMapView)
        tvReplayTitle = findViewById(R.id.tvReplayTitle)
        tvReplayStats = findViewById(R.id.tvReplayStats)
        tvReplayPosition = findViewById(R.id.tvReplayPosition)

        toolbar.setNavigationOnClickListener { finish() }
        val file = intent.getStringExtra(EXTRA_VIDEO_PATH)?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        videoFile = file
        track = VideoTrackStorage.readTrack(file)
        setupVideo(file)
        renderStaticInfo(file)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(syncTicker)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(syncTicker)
        mediaPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(syncTicker)
        releasePlayer()
        super.onDestroy()
    }

    private fun setupVideo(file: File) {
        mediaController = MediaController(this)
        mediaController.setAnchorView(videoTextureView)
        mediaController.setMediaPlayer(mediaControl)
        mediaController.isEnabled = false
        videoTextureView.setOnClickListener {
            if (playerPrepared) mediaController.show()
        }
        videoTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                preparePlayer(file, Surface(surface))
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer?.let { applyTextureTransform(it.videoWidth, it.videoHeight) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (videoTextureView.isAvailable) {
            preparePlayer(file, Surface(videoTextureView.surfaceTexture))
        }
    }

    private fun preparePlayer(file: File, surface: Surface) {
        releasePlayer()
        videoSurface = surface
        playerPrepared = false
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setSurface(surface)
            setOnPreparedListener { player ->
                playerPrepared = true
                mediaController.isEnabled = true
                applyTextureTransform(player.videoWidth, player.videoHeight)
                syncRouteToVideo()
                player.start()
                mediaController.show(1_500)
            }
            setOnVideoSizeChangedListener { _, width, height ->
                applyTextureTransform(width, height)
            }
            setOnCompletionListener {
                mediaController.show()
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@VideoReplayActivity, R.string.video_replay_failed, Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    private fun releasePlayer() {
        playerPrepared = false
        mediaController.isEnabled = false
        mediaPlayer?.release()
        mediaPlayer = null
        videoSurface?.release()
        videoSurface = null
    }

    private fun renderStaticInfo(file: File) {
        tvReplayTitle.text = getString(R.string.video_replay_file_title, file.name)
        val points = track?.points.orEmpty()
        tvReplayStats.text = if (points.isEmpty()) {
            getString(R.string.video_replay_no_track)
        } else {
            getString(
                R.string.video_replay_track_stats,
                points.size,
                formatDuration(points.last().elapsedMs),
                points.maxOf { it.speedKmh }
            )
        }
        routeMapView.setRoute(points, 0L)
    }

    private fun syncRouteToVideo() {
        val points = track?.points.orEmpty()
        val elapsedMs = currentVideoPositionMs()
        routeMapView.setRoute(points, elapsedMs)
        val point = VideoTrackSync.pointAt(points, elapsedMs)
        tvReplayPosition.text = if (point == null) {
            getString(R.string.video_replay_position_empty)
        } else {
            getString(
                R.string.video_replay_position,
                formatDuration(elapsedMs),
                point.speedKmh,
                point.latitude,
                point.longitude,
                formatTime(point.timeMs)
            )
        }
    }

    private fun currentVideoPositionMs(): Long {
        if (!playerPrepared) return 0L
        return try {
            mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L
        } catch (_: IllegalStateException) {
            0L
        }
    }

    private fun applyTextureTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || videoTextureView.width <= 0 || videoTextureView.height <= 0) return
        val viewRect = RectF(0f, 0f, videoTextureView.width.toFloat(), videoTextureView.height.toFloat())
        val videoRect = RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        val matrix = Matrix()
        videoRect.offset(centerX - videoRect.centerX(), centerY - videoRect.centerY())
        matrix.setRectToRect(viewRect, videoRect, Matrix.ScaleToFit.CENTER)
        val scale = maxOf(
            viewRect.width() / videoWidth.toFloat(),
            viewRect.height() / videoHeight.toFloat()
        )
        matrix.postScale(scale, scale, centerX, centerY)
        videoTextureView.setTransform(matrix)
    }

    private val mediaControl = object : MediaController.MediaPlayerControl {
        override fun start() {
            if (playerPrepared) mediaPlayer?.start()
        }

        override fun pause() {
            if (playerPrepared) mediaPlayer?.pause()
        }

        override fun getDuration(): Int {
            return if (playerPrepared) mediaPlayer?.duration ?: 0 else 0
        }

        override fun getCurrentPosition(): Int {
            return currentVideoPositionMs().toInt()
        }

        override fun seekTo(pos: Int) {
            if (playerPrepared) mediaPlayer?.seekTo(pos)
        }

        override fun isPlaying(): Boolean {
            return playerPrepared && mediaPlayer?.isPlaying == true
        }

        override fun getBufferPercentage(): Int = 100
        override fun canPause(): Boolean = true
        override fun canSeekBackward(): Boolean = true
        override fun canSeekForward(): Boolean = true
        override fun getAudioSessionId(): Int = mediaPlayer?.audioSessionId ?: 0
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "--"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
    }
}

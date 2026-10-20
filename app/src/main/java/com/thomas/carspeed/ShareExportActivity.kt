package com.thomas.carspeed

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import android.view.TextureView
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ShareExportActivity : AppCompatActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var previewVideoCard: MaterialCardView
    private lateinit var previewVideoFrame: FrameLayout
    private lateinit var previewVideoStage: FrameLayout
    private lateinit var previewTexture: TextureView
    private lateinit var previewSubtitle: TextView
    private lateinit var previewPlay: TextView
    private lateinit var previewSpeedButton: TextView
    private lateinit var previewSeek: SeekBar
    private lateinit var previewTime: TextView
    private lateinit var previewFullscreenButton: TextView
    private lateinit var previewRotate: TextView
    private lateinit var previewDivider: View
    private lateinit var previewMapCard: FloatMapCard
    private lateinit var previewMap: MapRouteView
    private lateinit var previewMapScale: TextView
    private lateinit var previewMapType: MaterialButton
    private lateinit var previewMapScaleIn: MaterialButton
    private lateinit var previewMapScaleOut: MaterialButton
    private lateinit var previewTitle: TextView
    private lateinit var previewStats: TextView
    private lateinit var previewPosition: TextView
    private lateinit var previewRoot: FrameLayout
    private lateinit var previewLayoutButton: TextView
    private lateinit var previewMapActions: LinearLayout
    private lateinit var previewMapScaleControls: LinearLayout
    private lateinit var previewViewportAll: MaterialButton
    private lateinit var previewViewportCurrent: MaterialButton
    private lateinit var previewOffline: MaterialButton
    private lateinit var layoutTrackAlpha: LinearLayout
    private lateinit var seekTrackAlpha: SeekBar
    private lateinit var tvTrackAlpha: TextView
    private lateinit var cbRouteMap: CheckBox
    private lateinit var seekMapHeight: SeekBar
    private lateinit var seekMapScale: SeekBar
    private lateinit var seekStart: SeekBar
    private lateinit var seekEnd: SeekBar
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvMapHeight: TextView
    private lateinit var tvMapScale: TextView
    private lateinit var tvTimeRange: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnExportOnly: MaterialButton
    private lateinit var btnExportShare: MaterialButton
    private lateinit var cbSubtitleTime: CheckBox
    private lateinit var cbSubtitleSpeed: CheckBox
    private lateinit var cbSubtitleStationaryWait: CheckBox
    private lateinit var cbSubtitleGps: CheckBox
    private lateinit var cbSubtitleSatellites: CheckBox
    private lateinit var cbSubtitleRadar: CheckBox
    private lateinit var cbSubtitleAddress: CheckBox
    private lateinit var cbSubtitleFocus: CheckBox
    private lateinit var cbSubtitleVideoMode: CheckBox
    private lateinit var cbSubtitleRoadLayer: CheckBox
    private var videos: List<File> = emptyList()
    private var segmentDurationsMs: List<Long> = emptyList()
    private var segmentOffsetsMs: List<Long> = emptyList()
    private var segmentStartWallMs: List<Long> = emptyList()
    private var totalDurationMs: Long = 1_000L
    private var subtitleCues: List<SrtCue> = emptyList()
    private var previewTrack: VideoTrack? = null
    private var previewSummary: TrackSummary? = null
    private var mediaPlayer: MediaPlayer? = null
    private var videoSurface: Surface? = null
    private var playerPrepared = false
    private var currentSegmentIndex = 0
    private var pendingSeekGlobalMs: Long? = null
    private var rangeSeekPending = false
    private var previewUserWantsPlay = true
    private var userSeekingPreview = false
    private var applyingSplit = false
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0
    private var videoRotationDegrees = 0
    private var previewFullscreen = false
    private lateinit var pageToolbar: MaterialToolbar
    private lateinit var optionsScroll: ScrollView
    private var dividerDragStartY = 0f
    private var dividerDragStartVideoHeight = 0
    private var dividerDragTotalHeight = 0
    private var layoutMode = ReplayMapLayout.MODE_SPLIT
    private var floatSpec = ReplayMapLayout.defaultFloatSpec()
    private var trackAlphaPercent = 100
    private lateinit var floatTouch: FloatWindowTouch
    private var mapActionPadStart = 0
    private var mapActionPadTop = 0
    private var mapActionPadEnd = 0
    private var mapActionPadBottom = 0
    private val previewTicker = object : Runnable {
        override fun run() {
            syncPreviewPlayback()
            mainHandler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videos = intent.getStringArrayListExtra(EXTRA_VIDEO_PATHS)
            ?.map { File(it) }
            ?.filter { it.exists() }
            ?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra(EXTRA_VIDEO_PATH)?.let { listOf(File(it)) }.orEmpty().filter { it.exists() }
        if (videos.isEmpty()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        segmentDurationsMs = videos.map { videoDurationMs(it).coerceAtLeast(1L) }
        segmentOffsetsMs = segmentDurationsMs.runningFold(0L) { total, duration -> total + duration }.dropLast(1)
        segmentStartWallMs = videos.mapIndexed { index, video -> videoStartWallMs(video, index) }
        totalDurationMs = segmentDurationsMs.sum().coerceAtLeast(1_000L)
        buildUi()
        updateLabels()
        updatePreview()
        loadPreviewData()
    }

    override fun onResume() {
        super.onResume()
        if (previewUserWantsPlay && playerPrepared) {
            try {
                mediaPlayer?.start()
            } catch (_: IllegalStateException) {
                // 播放器还没准备好时忽略。
            }
        }
        mainHandler.post(previewTicker)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(previewTicker)
        try {
            mediaPlayer?.pause()
        } catch (_: IllegalStateException) {
            // 播放器还没准备好时忽略。
        }
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(previewTicker)
        releasePreviewPlayer()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.brand_sand))
        }
        pageToolbar = MaterialToolbar(this).apply {
            title = getString(R.string.share_export_title)
            setTitleTextColor(color(R.color.text_primary))
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(pageToolbar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        optionsScroll = ScrollView(this)
        val scroll = optionsScroll
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        val options = optionsCard()
        val status = statusCard()
        val preview = layoutInflater.inflate(R.layout.layout_share_export_preview, root, false)
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.15f))
        bindPreview(preview)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.setPadding(dp(16), dp(8), dp(16), dp(16))
        content.addView(TextView(this).apply {
            text = getString(R.string.share_export_subtitle)
            setTextColor(color(R.color.text_secondary))
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(options)
        content.addView(status)
        setContentView(root)
    }

    private fun bindPreview(root: View) {
        previewRoot = root as FrameLayout
        previewVideoCard = root.findViewById(R.id.exportVideoCard)
        previewVideoFrame = root.findViewById(R.id.exportVideoFrame)
        previewVideoStage = root.findViewById(R.id.exportVideoStage)
        previewTexture = root.findViewById(R.id.exportVideoTexture)
        previewSubtitle = root.findViewById(R.id.exportSubtitle)
        previewPlay = root.findViewById(R.id.exportPlayPause)
        previewSpeedButton = root.findViewById(R.id.exportReplaySpeed)
        previewSeek = root.findViewById(R.id.exportReplaySeek)
        previewTime = root.findViewById(R.id.exportReplayTime)
        previewFullscreenButton = root.findViewById(R.id.exportReplayFullscreen)
        previewRotate = root.findViewById(R.id.exportReplayRotate)
        previewDivider = root.findViewById(R.id.exportVideoMapDivider)
        previewMapCard = root.findViewById(R.id.exportMapCard)
        previewMap = root.findViewById(R.id.exportMapRoute)
        previewMapScale = root.findViewById(R.id.exportMapScale)
        previewMapType = root.findViewById(R.id.exportMapType)
        previewMapScaleIn = root.findViewById(R.id.exportMapScaleIn)
        previewMapScaleOut = root.findViewById(R.id.exportMapScaleOut)
        previewTitle = root.findViewById(R.id.exportReplayTitle)
        previewStats = root.findViewById(R.id.exportReplayStats)
        previewPosition = root.findViewById(R.id.exportReplayPosition)
        previewLayoutButton = root.findViewById(R.id.exportReplayLayout)
        previewMapActions = root.findViewById(R.id.exportMapActionControls)
        previewMapScaleControls = root.findViewById(R.id.exportMapScaleControls)
        previewViewportAll = root.findViewById(R.id.exportMapViewportAll)
        previewViewportCurrent = root.findViewById(R.id.exportMapViewportCurrent)
        previewOffline = root.findViewById(R.id.exportMapOffline)
        layoutTrackAlpha = root.findViewById(R.id.exportTrackAlpha)
        seekTrackAlpha = root.findViewById(R.id.exportTrackAlphaSeek)
        tvTrackAlpha = root.findViewById(R.id.exportTrackAlphaValue)
        layoutMode = loadPreviewLayoutMode()
        floatSpec = loadPreviewFloatSpec()
        trackAlphaPercent = loadPreviewTrackAlpha()
        previewMap.setTrackAlphaPercent(trackAlphaPercent)
        seekTrackAlpha.progress = trackAlphaPercent
        tvTrackAlpha.text = getString(R.string.video_replay_track_alpha_value, trackAlphaPercent)
        mapActionPadStart = previewViewportAll.paddingStart
        mapActionPadTop = previewViewportAll.paddingTop
        mapActionPadEnd = previewViewportAll.paddingEnd
        mapActionPadBottom = previewViewportAll.paddingBottom
        previewVideoCard.clipChildren = false
        previewVideoFrame.clipChildren = false
        previewMapCard.clipChildren = false
        previewMap.setOverlayControlsVisible(false)
        previewMap.onPhotoClick = { path -> openPreviewPhoto(path) }
        previewMap.onRadarClick = { radar -> showPreviewRadar(radar) }
        previewMap.onMaxSpeedClick = { speedKmh -> showPreviewMaxSpeed(speedKmh) }
        previewMap.onScaleChanged = { updatePreviewMapControls() }
        previewPlay.setOnClickListener { togglePreviewPlayback() }
        previewSpeedButton.setOnClickListener { cyclePreviewSpeed() }
        previewFullscreenButton.setOnClickListener { setPreviewFullscreen(!previewFullscreen) }
        previewLayoutButton.setOnClickListener { togglePreviewLayout() }
        previewRotate.setOnClickListener { rotatePreview(90) }
        previewTexture.setOnClickListener {
            val controls = previewPlay.parent as View
            controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        previewViewportAll.setOnClickListener { previewMap.showWholeRoute() }
        previewViewportCurrent.setOnClickListener { previewMap.showCurrentViewport() }
        previewMapType.setOnClickListener {
            previewMap.toggleMapType()
            updatePreviewMapControls()
        }
        previewOffline.setOnClickListener { previewMap.openOfflineMap() }
        previewMapScaleOut.setOnClickListener {
            previewMap.zoomOutScale()
            updatePreviewMapControls()
        }
        previewMapScaleIn.setOnClickListener {
            previewMap.zoomInScale()
            updatePreviewMapControls()
        }
        previewSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                previewTime.text = previewClock(selectedStartMs() + progress, selectedEndMs())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeekingPreview = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userSeekingPreview = false
                seekPreviewTo(selectedStartMs() + (seekBar?.progress ?: 0))
            }
        })
        setupPreviewDivider()
        setupPreviewFloatGestures()
        setupPreviewTrackAlpha()
        previewTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val start = selectedStartMs()
                preparePreviewSegment(segmentIndexFor(start), Surface(surface), localMs(start), previewUserWantsPlay)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                mediaPlayer?.let { applyPreviewVideoStage(it.videoWidth, it.videoHeight) }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                releasePreviewPlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (previewTexture.isAvailable) {
            previewTexture.surfaceTexture?.let { texture ->
                val start = selectedStartMs()
                preparePreviewSegment(segmentIndexFor(start), Surface(texture), localMs(start), previewUserWantsPlay)
            }
        }
        previewTitle.text = videos.first().nameWithoutExtension
        applyPreviewChrome()
        updatePreviewMapControls()
    }

    private fun optionsCard(): View {
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.surface_primary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            val box = LinearLayout(this@ShareExportActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            tvTimeRange = optionLabel()
            box.addView(tvTimeRange)
            seekStart = SeekBar(this@ShareExportActivity).apply {
                max = durationSeconds().coerceAtLeast(1)
                progress = 0
                setOnSeekBarChangeListener(simpleSeekListener { clampTimeRange(); updateLabels(); updatePreview() })
            }
            box.addView(seekStart)
            seekEnd = SeekBar(this@ShareExportActivity).apply {
                max = durationSeconds().coerceAtLeast(1)
                progress = durationSeconds().coerceAtLeast(1)
                setOnSeekBarChangeListener(simpleSeekListener { clampTimeRange(); updateLabels(); updatePreview() })
            }
            box.addView(seekEnd)
            tvSpeed = optionLabel()
            box.addView(tvSpeed)
            seekSpeed = SeekBar(this@ShareExportActivity).apply {
                max = SPEED_OPTIONS.lastIndex
                progress = 0
                setOnSeekBarChangeListener(simpleSeekListener { updateLabels(); updatePreview() })
            }
            box.addView(seekSpeed)
            box.addView(sectionTitle(getString(R.string.share_export_subtitle_fields)))
            val subtitleGrid = LinearLayout(this@ShareExportActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            cbSubtitleTime = subtitleCheckBox(getString(R.string.share_export_field_time))
            cbSubtitleSpeed = subtitleCheckBox(getString(R.string.share_export_field_speed))
            cbSubtitleStationaryWait = subtitleCheckBox(getString(R.string.share_export_field_stationary_wait))
            cbSubtitleGps = subtitleCheckBox(getString(R.string.share_export_field_gps))
            cbSubtitleSatellites = subtitleCheckBox(getString(R.string.share_export_field_satellites))
            cbSubtitleRadar = subtitleCheckBox(getString(R.string.share_export_field_radar))
            cbSubtitleAddress = subtitleCheckBox(getString(R.string.share_export_field_address))
            cbSubtitleFocus = subtitleCheckBox(getString(R.string.share_export_field_focus))
            cbSubtitleVideoMode = subtitleCheckBox(getString(R.string.share_export_field_video_mode))
            cbSubtitleRoadLayer = subtitleCheckBox(getString(R.string.share_export_field_road_layer))
            listOf(
                cbSubtitleTime,
                cbSubtitleSpeed,
                cbSubtitleStationaryWait,
                cbSubtitleGps,
                cbSubtitleSatellites,
                cbSubtitleRadar,
                cbSubtitleAddress,
                cbSubtitleFocus,
                cbSubtitleVideoMode,
                cbSubtitleRoadLayer
            ).forEach {
                subtitleGrid.addView(it)
            }
            box.addView(subtitleGrid)
            box.addView(sectionTitle(getString(R.string.share_export_route_map_title)))
            cbRouteMap = MaterialCheckBox(this@ShareExportActivity).apply {
                text = getString(R.string.share_export_route_map_toggle)
                isChecked = true
                setTextColor(color(R.color.text_primary))
                setOnCheckedChangeListener { _, _ -> updatePreview() }
            }
            box.addView(cbRouteMap)
            tvMapHeight = optionLabel()
            box.addView(tvMapHeight)
            seekMapHeight = SeekBar(this@ShareExportActivity).apply {
                max = 27
                progress = 10
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        updateLabels()
                        if (fromUser && !applyingSplit) applyPreviewSplit(mapHeightPercent())
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        applyPreviewChrome()
                    }
                })
            }
            box.addView(seekMapHeight)
            tvMapScale = optionLabel()
            box.addView(tvMapScale)
            seekMapScale = SeekBar(this@ShareExportActivity).apply {
                max = 160
                progress = 40
                setOnSeekBarChangeListener(simpleSeekListener { updateLabels(); updatePreview() })
            }
            box.addView(seekMapScale)
            val buttonRow = LinearLayout(this@ShareExportActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnExportOnly = MaterialButton(this@ShareExportActivity).apply {
                text = getString(R.string.share_export_only)
                setOnClickListener { exportVideo(shareAfter = false) }
            }
            btnExportShare = MaterialButton(this@ShareExportActivity).apply {
                text = getString(R.string.share_export_start)
                setOnClickListener { exportVideo(shareAfter = true) }
            }
            buttonRow.addView(btnExportOnly, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            })
            buttonRow.addView(btnExportShare, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            })
            box.addView(buttonRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(box)
        }
    }

    private fun statusCard(): View {
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.surface_secondary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            val box = LinearLayout(this@ShareExportActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            tvStatus = TextView(this@ShareExportActivity).apply {
                text = getString(R.string.share_export_ready)
                setTextColor(color(R.color.text_secondary))
                textSize = 13f
            }
            this@ShareExportActivity.progress = ProgressBar(this@ShareExportActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = 0
                visibility = View.GONE
            }
            box.addView(tvStatus)
            box.addView(this@ShareExportActivity.progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(box)
        }
    }

    private fun optionLabel(): TextView {
        return TextView(this).apply {
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun sectionTitle(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(color(R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun subtitleCheckBox(textValue: String): CheckBox {
        return MaterialCheckBox(this).apply {
            text = textValue
            isChecked = true
            setTextColor(color(R.color.text_primary))
            setOnCheckedChangeListener { _, _ -> updatePreview() }
        }
    }

    private fun updateLabels() {
        val startMs = selectedStartMs()
        val endMs = selectedEndMs()
        tvTimeRange.text = getString(
            R.string.share_export_time_range,
            formatWallTime(startMs),
            formatWallTime(endMs),
            formatDuration(startMs),
            formatDuration(endMs),
            formatDuration((endMs - startMs).coerceAtLeast(0L))
        )
        tvSpeed.text = getString(R.string.share_export_speed, speedLabel())
        tvMapHeight.text = getString(R.string.share_export_map_height, mapHeightPercent())
        tvMapScale.text = getString(R.string.share_export_map_scale, mapScalePercent())
        if (::previewSpeedButton.isInitialized) previewSpeedButton.text = speedLabel()
    }

    private fun updatePreview() {
        applyPreviewChrome()
        clampPlaybackToRange()
    }

    private fun exportVideo(shareAfter: Boolean) {
        releasePreviewPlayer(releaseSurface = false)
        btnExportOnly.isEnabled = false
        btnExportShare.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        tvStatus.text = getString(R.string.share_export_running, 0)
        val options = currentOptions()
        worker.execute {
            try {
                val output = ShareVideoExporter.export(this, videos, options) { percent ->
                    mainHandler.post {
                        progress.progress = percent
                        tvStatus.text = getString(R.string.share_export_running, percent)
                    }
                }
                val published = VideoStorage.publishShareVideoToGallery(this, output)
                mainHandler.post {
                    btnExportOnly.isEnabled = true
                    btnExportShare.isEnabled = true
                    progress.progress = 100
                    tvStatus.text = getString(R.string.share_export_done, published.name)
                    restartPreviewAfterExport()
                    if (shareAfter) shareVideo(output)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    btnExportOnly.isEnabled = true
                    btnExportShare.isEnabled = true
                    tvStatus.text = getString(R.string.share_export_failed, error.message ?: error.javaClass.simpleName)
                    restartPreviewAfterExport()
                }
            }
        }
    }

    private fun shareVideo(video: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", video)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_export_share_title)))
    }

    private fun currentOptions(): ShareExportOptions {
        return ShareExportOptions(
            includeRouteMap = cbRouteMap.isChecked,
            mapHeightPercent = mapHeightPercent(),
            mapScalePercent = mapScalePercent(),
            sourceStartMs = selectedStartMs(),
            sourceEndMs = selectedEndMs(),
            playbackSpeed = selectedSpeed(),
            subtitleFields = ShareSubtitleFields(
                showTime = cbSubtitleTime.isChecked,
                showSpeed = cbSubtitleSpeed.isChecked,
                showStationaryWait = cbSubtitleStationaryWait.isChecked,
                showGps = cbSubtitleGps.isChecked,
                showSatellites = cbSubtitleSatellites.isChecked,
                showRadarSummary = cbSubtitleRadar.isChecked,
                showAddress = cbSubtitleAddress.isChecked,
                showFocus = cbSubtitleFocus.isChecked,
                showVideoMode = cbSubtitleVideoMode.isChecked,
                showRoadLayer = cbSubtitleRoadLayer.isChecked
            )
        )
    }

    private fun selectedStartMs(): Long = seekStart.progress * 1_000L

    private fun selectedEndMs(): Long = (seekEnd.progress * 1_000L).coerceAtLeast(selectedStartMs() + 1_000L).coerceAtMost(totalDurationMs)

    private fun selectedSpeed(): Float = SPEED_OPTIONS[seekSpeed.progress.coerceIn(0, SPEED_OPTIONS.lastIndex)]

    private fun speedLabel(): String {
        val speed = selectedSpeed()
        return if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
    }

    private fun clampTimeRange() {
        if (seekEnd.progress <= seekStart.progress) {
            seekEnd.progress = (seekStart.progress + 1).coerceAtMost(seekEnd.max)
        }
        if (seekStart.progress >= seekEnd.progress) {
            seekStart.progress = (seekEnd.progress - 1).coerceAtLeast(0)
        }
    }

    private fun mapHeightPercent(): Int = 18 + seekMapHeight.progress

    private fun mapScalePercent(): Int = 60 + seekMapScale.progress

    private fun durationSeconds(): Int = ((totalDurationMs + 999L) / 1_000L).toInt().coerceAtLeast(1)

    private fun videoDurationMs(video: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // 忽略系统解码器释放异常。
            }
        }
    }

    private fun videoStartWallMs(video: File, index: Int): Long {
        VideoTrackStorage.readTrack(this, video)?.startTimeMs?.takeIf { it > 0L }?.let { return it }
        parseVideoStartMs(video.nameWithoutExtension)?.let { return it }
        return (video.lastModified() - segmentDurationsMs.getOrElse(index) { 0L }).takeIf { it > 0L } ?: 0L
    }

    private fun parseVideoStartMs(name: String): Long? {
        val match = VIDEO_NAME_REGEX.find(name) ?: return null
        return try {
            VIDEO_NAME_FORMAT.parse("${match.groupValues[1]}-${match.groupValues[2]}")?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun formatWallTime(elapsedMs: Long): String {
        val wallTime = wallTimeForElapsed(elapsedMs)
        return if (wallTime > 0L) {
            WALL_TIME_FORMAT.format(Date(wallTime))
        } else {
            formatDuration(elapsedMs)
        }
    }

    private fun wallTimeForElapsed(elapsedMs: Long): Long {
        if (segmentStartWallMs.isEmpty()) return 0L
        val segment = segmentOffsetsMs.indexOfLast { it <= elapsedMs }.coerceIn(0, segmentStartWallMs.lastIndex)
        val startWall = segmentStartWallMs.getOrElse(segment) { 0L }
        if (startWall <= 0L) return 0L
        val offset = segmentOffsetsMs.getOrElse(segment) { 0L }
        return startWall + (elapsedMs - offset).coerceAtLeast(0L)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val h = totalSec / 3_600L
        val m = (totalSec % 3_600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun simpleSeekListener(onStop: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = onStop()
        }
    }

    private fun loadPreviewData() {
        worker.execute {
            val cues = SrtSubtitle.readCues(videos, segmentOffsetsMs, segmentDurationsMs)
            val track = VideoTrackStorage.readMergedTrack(this, videos, segmentOffsetsMs, segmentDurationsMs)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                subtitleCues = cues
                previewTrack = track
                previewSummary = track?.let { TrackMetrics.summary(it.points) }
                publishPreviewRoute(currentPreviewPositionMs())
                renderPreviewStats()
                syncPreviewSubtitle(currentPreviewPositionMs())
            }
        }
    }

    private fun applyPreviewChrome() {
        if (!::previewVideoCard.isInitialized) return
        applyPreviewLayout()
        applyPreviewPlaybackSpeed()
        syncPreviewSubtitle(currentPreviewPositionMs())
        if (::previewSpeedButton.isInitialized) previewSpeedButton.text = speedLabel()
    }

    private fun applyPreviewSplit(mapPercent: Int) {
        if (layoutMode == ReplayMapLayout.MODE_FLOAT) return
        applyPreviewLayout()
    }

    private fun applyPreviewLayout() {
        if (!::previewRoot.isInitialized) return
        val width = previewRoot.width
        val height = previewRoot.height
        if (width <= 0 || height <= 0) {
            previewRoot.post { applyPreviewLayout() }
            return
        }
        val showMap = ::cbRouteMap.isInitialized && cbRouteMap.isChecked && !previewFullscreen
        val floating = showMap && layoutMode == ReplayMapLayout.MODE_FLOAT
        previewDivider.visibility = if (showMap && !floating) View.VISIBLE else View.GONE
        previewMapCard.visibility = if (showMap) View.VISIBLE else View.GONE
        previewMapCard.edgePx = if (floating) dp(ReplayMapLayout.FLOAT_EDGE_DP) else 0
        previewLayoutButton.visibility = if (showMap) View.VISIBLE else View.GONE
        previewLayoutButton.text = getString(
            if (layoutMode == ReplayMapLayout.MODE_FLOAT) {
                R.string.video_replay_layout_split
            } else {
                R.string.video_replay_layout_float
            }
        )
        applyPreviewMapChrome(
            ReplayMapLayout.chrome(
                mode = if (floating) ReplayMapLayout.MODE_FLOAT else ReplayMapLayout.MODE_SPLIT,
                mapFullscreen = false,
                trackOnly = false
            )
        )
        previewMapCard.cardElevation = if (floating) dp(8).toFloat() else 0f
        previewMapCard.clipToOutline = floating
        previewMapCard.radius = if (floating) dp(16).toFloat() else 0f
        when {
            !showMap -> placePreviewChild(previewVideoCard, 0, 0, width, height)
            floating -> {
                placePreviewChild(previewVideoCard, 0, 0, width, height)
                val window = ReplayMapLayout.frame(floatSpec, width, height, dp(160), dp(140))
                placePreviewChild(previewMapCard, window.left, window.top, window.width, window.height)
                previewMapCard.bringToFront()
            }
            else -> {
                val sections = ShareExportPreviewSplit.splitSections(height, mapHeightPercent(), dp(18))
                placePreviewChild(previewVideoCard, 0, sections.first.top, width, sections.first.height)
                placePreviewChild(previewDivider, 0, sections.second.top, width, sections.second.height)
                placePreviewChild(previewMapCard, 0, sections.third.top, width, sections.third.height)
            }
        }
        if (lastVideoWidth > 0 && lastVideoHeight > 0) {
            previewVideoFrame.post { applyPreviewVideoStage(lastVideoWidth, lastVideoHeight) }
        }
    }

    private fun applyPreviewMapChrome(chrome: ReplayMapChrome) {
        val textVisibility = if (chrome.showTrackText) View.VISIBLE else View.GONE
        previewTitle.visibility = textVisibility
        previewStats.visibility = textVisibility
        previewPosition.visibility = textVisibility
        layoutTrackAlpha.visibility = if (chrome.showTrackSlider) View.VISIBLE else View.GONE
        previewMap.setSpeedCaptionVisible(chrome.showSpeedCaption)
        previewMapScaleControls.visibility = if (chrome.showScaleControls) View.VISIBLE else View.GONE
        previewOffline.visibility = if (chrome.showOfflineButton) View.VISIBLE else View.GONE
        val buttonHeight = if (chrome.compactActions) dp(26) else dp(28)
        val gap = if (chrome.compactActions) dp(4) else dp(6)
        previewMapActions.layoutParams = previewMapActions.layoutParams.apply {
            this.height = buttonHeight
        }
        listOf(previewViewportAll, previewViewportCurrent, previewMapType).forEachIndexed { index, button ->
            val params = button.layoutParams as LinearLayout.LayoutParams
            if (chrome.compactActions) {
                params.width = LinearLayout.LayoutParams.WRAP_CONTENT
                params.weight = 0f
            } else {
                params.width = 0
                params.weight = 1f
            }
            params.height = buttonHeight
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

    private fun placePreviewChild(view: View, left: Int, top: Int, width: Int, height: Int) {
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = left
            topMargin = top
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun togglePreviewLayout() {
        layoutMode = if (layoutMode == ReplayMapLayout.MODE_FLOAT) {
            ReplayMapLayout.MODE_SPLIT
        } else {
            ReplayMapLayout.MODE_FLOAT
        }
        persistPreviewLayoutMode()
        applyPreviewLayout()
    }

    private fun setupPreviewFloatGestures() {
        floatTouch = FloatWindowTouch(
            edgePx = dp(ReplayMapLayout.FLOAT_EDGE_DP),
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        )
        previewMapCard.onEdgeTouch = { event -> onPreviewFloatTouch(event, bodyOnly = false) }
        previewMap.setConsumeTouchListener { event -> onPreviewFloatTouch(event, bodyOnly = true) }
    }

    private fun onPreviewFloatTouch(event: MotionEvent, bodyOnly: Boolean): Boolean {
        if (layoutMode != ReplayMapLayout.MODE_FLOAT || previewFullscreen || !cbRouteMap.isChecked) {
            return false
        }
        val parentWidth = previewRoot.width
        val parentHeight = previewRoot.height
        val consumed = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (bodyOnly) {
                    floatTouch.downBody(event.rawX, event.rawY, currentPreviewFloatFrame(), event.eventTime)
                } else {
                    floatTouch.down(
                        localX = event.x,
                        localY = event.y,
                        rawX = event.rawX,
                        rawY = event.rawY,
                        viewWidth = previewMapCard.width,
                        viewHeight = previewMapCard.height,
                        frame = currentPreviewFloatFrame(),
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
                    applyPreviewLayout()
                    previewRoot.requestDisallowInterceptTouchEvent(true)
                }
                floatTouch.consuming()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = floatTouch.up(event.eventTime)
                if (handled) persistPreviewFloatSpec()
                handled
            }
            else -> floatTouch.consuming()
        }
        if (floatTouch.takeDragEntry()) {
            previewMapCard.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        return consumed
    }

    private fun currentPreviewFloatFrame(): ReplayFloatFrame {
        return ReplayMapLayout.frame(floatSpec, previewRoot.width, previewRoot.height, dp(160), dp(140))
    }

    private fun setupPreviewTrackAlpha() {
        seekTrackAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || layoutTrackAlpha.visibility != View.VISIBLE) return
                trackAlphaPercent = ReplayMapLayout.trackAlphaPercent(progress)
                tvTrackAlpha.text = getString(R.string.video_replay_track_alpha_value, trackAlphaPercent)
                previewMap.setTrackAlphaPercent(trackAlphaPercent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persistPreviewTrackAlpha()
            }
        })
    }

    private fun loadPreviewLayoutMode(): String {
        return ReplayMapLayout.normalizeMode(
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getString(ReplayMapLayout.PREF_LAYOUT_MODE, ReplayMapLayout.MODE_SPLIT)
        )
    }

    private fun persistPreviewLayoutMode() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(ReplayMapLayout.PREF_LAYOUT_MODE, layoutMode)
            .apply()
    }

    private fun loadPreviewFloatSpec(): ReplayFloatSpec {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        val defaults = ReplayMapLayout.defaultFloatSpec()
        return ReplayFloatSpec(
            leftFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_LEFT, defaults.leftFraction),
            topFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_TOP, defaults.topFraction),
            widthFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_WIDTH, defaults.widthFraction),
            heightFraction = prefs.getFloat(ReplayMapLayout.PREF_FLOAT_HEIGHT, defaults.heightFraction)
        )
    }

    private fun persistPreviewFloatSpec() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(ReplayMapLayout.PREF_FLOAT_LEFT, floatSpec.leftFraction)
            .putFloat(ReplayMapLayout.PREF_FLOAT_TOP, floatSpec.topFraction)
            .putFloat(ReplayMapLayout.PREF_FLOAT_WIDTH, floatSpec.widthFraction)
            .putFloat(ReplayMapLayout.PREF_FLOAT_HEIGHT, floatSpec.heightFraction)
            .apply()
    }

    private fun loadPreviewTrackAlpha(): Int {
        return ReplayMapLayout.trackAlphaPercent(
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .getInt(ReplayMapLayout.PREF_TRACK_ALPHA, 100)
        )
    }

    private fun persistPreviewTrackAlpha() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putInt(ReplayMapLayout.PREF_TRACK_ALPHA, trackAlphaPercent)
            .apply()
    }

    private fun setupPreviewDivider() {
        previewDivider.setOnTouchListener { view, event ->
            if (previewMapCard.visibility != View.VISIBLE || layoutMode == ReplayMapLayout.MODE_FLOAT) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dividerDragStartY = event.rawY
                    dividerDragStartVideoHeight = previewVideoCard.height
                    dividerDragTotalHeight = previewVideoCard.height + previewMapCard.height
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalHeight = dividerDragTotalHeight.takeIf { it > 0 }
                        ?: (previewVideoCard.height + previewMapCard.height)
                    if (totalHeight <= 0) return@setOnTouchListener true
                    val nextVideoHeight = ShareExportPreviewSplit.clampVideoHeight(
                        (dividerDragStartVideoHeight + event.rawY - dividerDragStartY).toInt(),
                        totalHeight
                    )
                    val percent = ShareExportPreviewSplit.mapPercent(nextVideoHeight, totalHeight)
                    applyingSplit = true
                    seekMapHeight.progress = (percent - ShareExportPreviewSplit.MIN_MAP_PERCENT)
                        .coerceIn(0, seekMapHeight.max)
                    applyingSplit = false
                    applyPreviewSplit(percent)
                    updateLabels()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun preparePreviewSegment(index: Int, surface: Surface, seekMs: Long, autoStart: Boolean) {
        releasePreviewPlayer(releaseSurface = false)
        videoSurface = surface
        playerPrepared = false
        currentSegmentIndex = index.coerceIn(0, videos.lastIndex)
        val file = videos[currentSegmentIndex]
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setSurface(surface)
            setOnPreparedListener { player ->
                playerPrepared = true
                applyPreviewVideoStage(player.videoWidth, player.videoHeight)
                if (seekMs > 0L) {
                    pendingSeekGlobalMs = segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L } + seekMs
                    player.seekTo(seekMs.coerceAtMost(player.duration.toLong()).toInt())
                } else {
                    pendingSeekGlobalMs = null
                }
                applyPreviewPlaybackSpeed(player)
                if (autoStart && previewUserWantsPlay) player.start()
                syncPreviewPlayback()
            }
            setOnSeekCompleteListener {
                rangeSeekPending = false
                pendingSeekGlobalMs = null
                syncPreviewPlayback()
            }
            setOnVideoSizeChangedListener { _, width, height ->
                applyPreviewVideoStage(width, height)
            }
            setOnCompletionListener {
                val nextIndex = currentSegmentIndex + 1
                val nextStart = segmentOffsetsMs.getOrElse(nextIndex) { Long.MAX_VALUE }
                val surfaceForNext = videoSurface
                if (nextIndex <= videos.lastIndex && nextStart < selectedEndMs() && surfaceForNext != null) {
                    preparePreviewSegment(nextIndex, surfaceForNext, 0L, true)
                } else if (surfaceForNext != null) {
                    val start = selectedStartMs()
                    preparePreviewSegment(segmentIndexFor(start), surfaceForNext, localMs(start), true)
                }
            }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@ShareExportActivity, R.string.video_replay_failed, Toast.LENGTH_SHORT).show()
                true
            }
            prepareAsync()
        }
    }

    private fun releasePreviewPlayer(releaseSurface: Boolean = true) {
        playerPrepared = false
        rangeSeekPending = false
        pendingSeekGlobalMs = null
        val player = mediaPlayer
        mediaPlayer = null
        try {
            player?.release()
        } catch (_: Exception) {
            // 忽略播放器重复释放。
        }
        if (releaseSurface) {
            videoSurface?.release()
            videoSurface = null
        }
    }

    private fun restartPreviewAfterExport() {
        val start = selectedStartMs()
        val surface = videoSurface
        if (surface != null) {
            preparePreviewSegment(segmentIndexFor(start), surface, localMs(start), previewUserWantsPlay)
            return
        }
        val texture = previewTexture.surfaceTexture ?: return
        preparePreviewSegment(segmentIndexFor(start), Surface(texture), localMs(start), previewUserWantsPlay)
    }

    private fun togglePreviewPlayback() {
        val player = mediaPlayer ?: return
        if (!playerPrepared) return
        if (player.isPlaying) {
            player.pause()
            previewUserWantsPlay = false
        } else {
            val position = currentPreviewPositionMs()
            if (position >= selectedEndMs() - 150L || position < selectedStartMs()) {
                seekPreviewTo(selectedStartMs(), autoStart = true)
            } else {
                player.start()
            }
            previewUserWantsPlay = true
        }
        syncPreviewPlayback()
    }

    private fun setPreviewFullscreen(enabled: Boolean) {
        previewFullscreen = enabled
        pageToolbar.visibility = if (enabled) View.GONE else View.VISIBLE
        optionsScroll.visibility = if (enabled) View.GONE else View.VISIBLE
        previewFullscreenButton.text = if (enabled) "退出" else "全屏"
        applyPreviewChrome()
    }

    private fun rotatePreview(visualClockwiseDegrees: Int) {
        videoRotationDegrees = VideoReplayRotation.normalize(videoRotationDegrees + visualClockwiseDegrees)
        if (::previewMap.isInitialized) previewMap.setContentRotation(videoRotationDegrees)
        if (lastVideoWidth > 0 && lastVideoHeight > 0) applyPreviewVideoStage(lastVideoWidth, lastVideoHeight)
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        if (previewFullscreen) {
            setPreviewFullscreen(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun cyclePreviewSpeed() {
        seekSpeed.progress = (seekSpeed.progress + 1) % SPEED_OPTIONS.size
        updateLabels()
        applyPreviewPlaybackSpeed()
    }

    private fun applyPreviewPlaybackSpeed(player: MediaPlayer? = mediaPlayer) {
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
            target.playbackParams = params.setSpeed(selectedSpeed()).setPitch(1f).allowDefaults()
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.video_replay_speed_failed, Toast.LENGTH_SHORT).show()
            return
        }
        if (!wasPlaying) {
            try {
                if (target.isPlaying) target.pause()
            } catch (_: IllegalStateException) {
                // 变速后系统可能自动开播，停回去。
            }
        }
    }

    private fun syncPreviewPlayback() {
        if (!::previewSeek.isInitialized || isFinishing || isDestroyed) return
        val start = selectedStartMs()
        val end = selectedEndMs()
        val playing = try {
            mediaPlayer?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
        var position = currentPreviewPositionMs()
        if (playing && !rangeSeekPending && (position >= end - 150L || position < start)) {
            rangeSeekPending = true
            seekPreviewTo(start, autoStart = true)
            position = start
        }
        val span = (end - start).toInt().coerceAtLeast(1)
        if (!userSeekingPreview) {
            if (previewSeek.max != span) previewSeek.max = span
            previewSeek.progress = (position - start).toInt().coerceIn(0, span)
        }
        previewTime.text = previewClock(position, end)
        previewPlay.text = if (playerPrepared && playing) "暂停" else "播放"
        syncPreviewSubtitle(position)
        publishPreviewRoute(position)
        updatePreviewPosition(position)
    }

    private fun clampPlaybackToRange() {
        if (!playerPrepared || userSeekingPreview) return
        val position = currentPreviewPositionMs()
        if (position < selectedStartMs() || position >= selectedEndMs()) {
            seekPreviewTo(selectedStartMs(), autoStart = previewUserWantsPlay)
        }
    }

    private fun seekPreviewTo(positionMs: Long, autoStart: Boolean? = null) {
        if (videos.isEmpty()) return
        val clamped = positionMs.coerceIn(0L, totalDurationMs)
        val index = segmentIndexFor(clamped)
        val local = localMs(clamped)
        val shouldPlay = autoStart ?: previewUserWantsPlay
        pendingSeekGlobalMs = clamped
        if (index == currentSegmentIndex && playerPrepared) {
            mediaPlayer?.seekTo(local.toInt())
            if (shouldPlay) mediaPlayer?.start() else mediaPlayer?.pause()
        } else {
            val surface = videoSurface ?: run {
                pendingSeekGlobalMs = null
                rangeSeekPending = false
                return
            }
            preparePreviewSegment(index, surface, local, shouldPlay)
        }
    }

    private fun syncPreviewSubtitle(elapsedMs: Long) {
        if (!::previewSubtitle.isInitialized) return
        val cue = SrtSubtitle.cueAt(subtitleCues, elapsedMs, PREVIEW_SUBTITLE_HOLD_MS)
        val text = cue?.let { ShareSubtitleFormatter.overlayText(it.text, currentOptions().subtitleFields) }.orEmpty()
        if (text.isBlank()) {
            previewSubtitle.visibility = View.GONE
        } else {
            previewSubtitle.text = text
            previewSubtitle.visibility = View.VISIBLE
        }
    }

    private fun publishPreviewRoute(elapsedMs: Long) {
        if (!::previewMap.isInitialized) return
        val points = previewTrack?.points.orEmpty()
        previewMap.setRoute(points, elapsedMs)
        if (previewTrack != null && previewMapPhotoPublished.not()) {
            previewMap.setPhotoMarks(
                RoutePhotoMarks.match(points, previewPhotoCandidates(), previewTrack?.startTimeMs ?: 0L)
            )
            previewMap.setRadarMarks(previewTrack?.radars.orEmpty())
            previewMapPhotoPublished = true
        }
    }

    private var previewMapPhotoPublished = false

    private fun renderPreviewStats() {
        if (!::previewStats.isInitialized) return
        val points = previewTrack?.points.orEmpty()
        previewStats.text = if (points.isEmpty()) {
            getString(R.string.video_replay_no_track)
        } else {
            val summary = previewSummary ?: TrackMetrics.summary(points)
            getString(
                R.string.video_replay_track_stats,
                points.size,
                formatDuration(summary.durationMs),
                TrackMetrics.formatDistance(summary.totalDistanceMeters),
                summary.maxSpeedKmh,
                summary.averageSpeedKmh
            )
        }
    }

    private fun updatePreviewPosition(elapsedMs: Long) {
        if (!::previewPosition.isInitialized) return
        val points = previewTrack?.points.orEmpty()
        val point = VideoTrackSync.pointAt(points, elapsedMs)
        previewPosition.text = if (point == null) {
            getString(R.string.video_replay_position_empty)
        } else {
            val currentDistance = TrackMetrics.distanceAt(points, elapsedMs)
            val totalDistance = previewSummary?.totalDistanceMeters ?: TrackMetrics.totalDistance(points)
            getString(
                R.string.video_replay_position,
                formatDuration(elapsedMs),
                point.speedKmh,
                TrackMetrics.formatDistance(currentDistance),
                TrackMetrics.formatDistance(totalDistance),
                point.latitude,
                point.longitude,
                formatWallTime(elapsedMs)
            )
        }
    }

    private fun updatePreviewMapControls() {
        if (!::previewMapScale.isInitialized) return
        previewMapScale.text = getString(R.string.video_replay_map_scale, previewMap.mapScaleLabel())
        previewMapType.text = previewMap.mapTypeLabel()
        previewMapScaleIn.isEnabled = previewMap.canZoomInScale()
        previewMapScaleOut.isEnabled = previewMap.canZoomOutScale()
    }

    private fun previewPhotoCandidates(): List<RoutePhotoCandidate> {
        return VideoStorage.photoFiles(this).mapNotNull { file ->
            val timeMs = RoutePhotoMarks.timeFromFileName(file.name) ?: return@mapNotNull null
            RoutePhotoCandidate(timeMs, file.absolutePath)
        }
    }

    private fun openPreviewPhoto(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, R.string.photo_preview_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PhotoPreviewActivity::class.java).putExtra(PhotoPreviewActivity.EXTRA_PATH, file.absolutePath))
    }

    private fun showPreviewRadar(radar: VideoTrackRadar) {
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

    private fun showPreviewMaxSpeed(speedKmh: Float) {
        AlertDialog.Builder(this)
            .setTitle(R.string.route_max_speed_title)
            .setMessage(getString(R.string.route_max_speed_message, speedKmh))
            .setPositiveButton(R.string.route_radar_detail_close, null)
            .show()
    }

    private fun applyPreviewVideoStage(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        lastVideoWidth = videoWidth
        lastVideoHeight = videoHeight
        if (previewVideoFrame.width <= 0 || previewVideoFrame.height <= 0) {
            previewVideoFrame.post { applyPreviewVideoStage(videoWidth, videoHeight) }
            return
        }
        val (contentWidth, contentHeight) = VideoReplayRotation.fittedContentSize(
            previewVideoFrame.width,
            previewVideoFrame.height,
            videoWidth,
            videoHeight,
            videoRotationDegrees
        )
        if (previewVideoStage.layoutParams?.width != contentWidth || previewVideoStage.layoutParams?.height != contentHeight) {
            previewVideoStage.layoutParams = FrameLayout.LayoutParams(contentWidth, contentHeight, Gravity.CENTER)
        }
        previewVideoStage.pivotX = contentWidth / 2f
        previewVideoStage.pivotY = contentHeight / 2f
        previewVideoStage.rotation = videoRotationDegrees.toFloat()
        val subtitleParams = previewSubtitle.layoutParams as FrameLayout.LayoutParams
        val bottomMargin = if (VideoReplayRotation.isQuarterTurn(videoRotationDegrees)) dp(12) else dp(64)
        if (subtitleParams.bottomMargin != bottomMargin) {
            subtitleParams.bottomMargin = bottomMargin
            previewSubtitle.layoutParams = subtitleParams
        }
        previewTexture.setTransform(null)
    }

    private fun currentPreviewPositionMs(): Long {
        pendingSeekGlobalMs?.let { return it }
        if (!playerPrepared) return selectedStartMs()
        return try {
            segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L } +
                (mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L)
        } catch (_: IllegalStateException) {
            segmentOffsetsMs.getOrElse(currentSegmentIndex) { 0L }
        }
    }

    private fun segmentIndexFor(positionMs: Long): Int {
        if (segmentOffsetsMs.isEmpty()) return 0
        return segmentOffsetsMs.indexOfLast { it <= positionMs }.coerceIn(0, videos.lastIndex)
    }

    private fun localMs(positionMs: Long): Long {
        val index = segmentIndexFor(positionMs)
        return (positionMs - segmentOffsetsMs.getOrElse(index) { 0L }).coerceAtLeast(0L)
    }

    private fun previewClock(positionMs: Long, endMs: Long): String {
        val start = selectedStartMs()
        return "${formatShortDuration((positionMs - start).coerceAtLeast(0L))}/${formatShortDuration((endMs - start).coerceAtLeast(0L))}"
    }

    private fun formatShortDuration(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0L)
        val h = totalSec / 3_600L
        val m = (totalSec % 3_600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_PATHS = "video_paths"
        private val SPEED_OPTIONS = floatArrayOf(1f, 2f, 4f, 8f, 16f)
        private const val PREVIEW_SUBTITLE_HOLD_MS = 1_500L
        private val VIDEO_NAME_REGEX = Regex("carspeed-(\\d{8})-(\\d{6})")
        private val VIDEO_NAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        private val WALL_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    }
}

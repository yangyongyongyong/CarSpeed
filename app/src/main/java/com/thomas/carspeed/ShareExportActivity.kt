package com.thomas.carspeed

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var previewImage: ImageView
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
    @Volatile private var previewRequestId = 0

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
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(color(R.color.brand_sand))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.share_export_title)
            setTitleTextColor(color(R.color.text_primary))
            setNavigationIcon(R.drawable.ic_nav_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(TextView(this).apply {
            text = getString(R.string.share_export_subtitle)
            setTextColor(color(R.color.text_secondary))
            textSize = 14f
        })
        content.addView(previewCard())
        content.addView(optionsCard())
        content.addView(statusCard())
        setContentView(root)
    }

    private fun previewCard(): View {
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.surface_primary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
            val frame = FrameLayout(this@ShareExportActivity).apply {
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            previewImage = ImageView(this@ShareExportActivity).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
            }
            frame.addView(previewImage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(460)))
            addView(frame)
        }
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
                setOnSeekBarChangeListener(simpleSeekListener { updateLabels(); updatePreview() })
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
    }

    private fun updatePreview() {
        val requestId = ++previewRequestId
        val options = currentOptions()
        tvStatus.text = getString(R.string.share_export_previewing)
        worker.execute {
            val bitmap = ShareVideoExporter.createPreview(this, videos, options)
            mainHandler.post {
                if (requestId != previewRequestId || isFinishing || isDestroyed) return@post
                if (bitmap != null) {
                    previewImage.setImageBitmap(bitmap)
                    tvStatus.text = getString(R.string.share_export_ready)
                } else {
                    tvStatus.text = getString(R.string.share_export_preview_failed)
                }
            }
        }
    }

    private fun exportVideo(shareAfter: Boolean) {
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
                    if (shareAfter) shareVideo(output)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    btnExportOnly.isEnabled = true
                    btnExportShare.isEnabled = true
                    tvStatus.text = getString(R.string.share_export_failed, error.message ?: error.javaClass.simpleName)
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

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_VIDEO_PATH = "video_path"
        const val EXTRA_VIDEO_PATHS = "video_paths"
        private val SPEED_OPTIONS = floatArrayOf(1f, 2f, 4f, 8f, 16f)
        private val VIDEO_NAME_REGEX = Regex("carspeed-(\\d{8})-(\\d{6})")
        private val VIDEO_NAME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        private val WALL_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
    }
}

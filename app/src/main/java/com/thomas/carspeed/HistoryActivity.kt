package com.thomas.carspeed

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private lateinit var spSortField: Spinner
    private lateinit var btnSortOrder: Button
    private lateinit var btnExportGpx: Button
    private lateinit var btnDeleteAllVideos: Button
    private lateinit var tvVideoSummary: TextView
    private lateinit var layoutVideoList: LinearLayout
    private lateinit var tvHistory: TextView
    private lateinit var toolbar: MaterialToolbar

    private var currentField: HistorySortField = HistorySortField.START_TIME
    private var ascending: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        spSortField = findViewById(R.id.spSortField)
        btnSortOrder = findViewById(R.id.btnSortOrder)
        btnExportGpx = findViewById(R.id.btnExportGpx)
        btnDeleteAllVideos = findViewById(R.id.btnDeleteAllVideos)
        tvVideoSummary = findViewById(R.id.tvVideoSummary)
        layoutVideoList = findViewById(R.id.layoutVideoList)
        tvHistory = findViewById(R.id.tvHistory)
        toolbar = findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        currentField = HistorySortField.entries[
            prefs.getInt(PREF_HISTORY_SORT_FIELD, HistorySortField.START_TIME.ordinal)
                .coerceIn(0, HistorySortField.entries.lastIndex)
        ]
        ascending = prefs.getBoolean(PREF_HISTORY_SORT_ASC, false)

        setupSortUi()
        btnExportGpx.setOnClickListener { exportLatestGpx() }
        btnDeleteAllVideos.setOnClickListener { confirmDeleteAllVideos() }
        render()
    }

    private fun setupSortUi() {
        val items = listOf("启动时间", "结束时间", "驾驶时长", "最高速度", "行驶距离", "平均速度")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spSortField.adapter = adapter
        spSortField.setSelection(currentField.ordinal)

        btnSortOrder.text = if (ascending) "升序" else "降序"

        spSortField.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                currentField = HistorySortField.entries[position]
                persistSortPref()
                render()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        btnSortOrder.setOnClickListener {
            ascending = !ascending
            btnSortOrder.text = if (ascending) "升序" else "降序"
            persistSortPref()
            render()
        }
    }

    private fun exportLatestGpx() {
        val record = DriveRecordStore.loadRecords(this).maxByOrNull { it.startTimeMs }
        if (record == null) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (record.routePoints.isEmpty()) {
            Toast.makeText(this, R.string.gpx_no_route, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val share = GpxExporter.shareIntent(this, record)
            startActivity(Intent.createChooser(share, getString(R.string.gpx_share_title)))
        } catch (_: RuntimeException) {
            Toast.makeText(this, getString(R.string.gpx_export_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun replayVideo(video: File) {
        startActivity(
            Intent(this, VideoReplayActivity::class.java).apply {
                putExtra(VideoReplayActivity.EXTRA_VIDEO_PATH, video.absolutePath)
            }
        )
    }

    private fun confirmDeleteVideo(video: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_one)
            .setMessage(video.name)
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = VideoStorage.deleteVideoGroup(video)
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                renderVideoList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllVideos() {
        val videos = VideoStorage.videoFiles(this)
        if (videos.isEmpty()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_all)
            .setMessage(getString(R.string.video_delete_all_confirm, videos.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = VideoStorage.deleteAllVideos(this)
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                renderVideoList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persistSortPref() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_HISTORY_SORT_FIELD, currentField.ordinal)
            .putBoolean(PREF_HISTORY_SORT_ASC, ascending)
            .apply()
    }

    private fun render() {
        renderVideoList()
        val records = DriveRecordStore.loadRecords(this)
        if (records.isEmpty()) {
            tvHistory.text = getString(R.string.history_empty)
            return
        }

        val sorted = HistorySort.sort(records, currentField, ascending)
        val text = buildString {
            appendLine("历史驾驶记录（最多100条）")
            appendLine("排序字段: ${fieldLabel(currentField)}  顺序: ${if (ascending) "升序" else "降序"}")
            appendLine("====================")
            sorted.forEachIndexed { idx, r ->
                appendLine("#${idx + 1}")
                appendLine("启动时间: ${fmt.format(Date(r.startTimeMs))}")
                appendLine("结束时间: ${fmt.format(Date(r.endTimeMs))}")
                appendLine("本次耗时: ${formatDuration(r.durationMs)}")
                appendLine("静止等待: ${formatDuration(r.waitDurationMs)}")
                appendLine("行驶距离: ${formatDistance(r.distanceMeters)}")
                appendLine("平均速度: ${String.format("%.1f", r.averageSpeedKmh)} km/h")
                appendLine("最高速度: ${String.format("%.1f", r.maxSpeedKmh)} km/h")
                appendLine("GPS质量: ${formatGpsQuality(r)}")
                appendLine("轨迹点: ${r.routePoints.size}")
                val maxLat = r.maxSpeedLatitude
                val maxLon = r.maxSpeedLongitude
                if (maxLat != null && maxLon != null) {
                    appendLine(
                        "峰值位置: ${String.format("%.6f", maxLat)}, " +
                            String.format("%.6f", maxLon)
                    )
                }
                appendLine("--------------------")
            }
        }
        tvHistory.text = text
    }

    private fun renderVideoList() {
        val videos = VideoStorage.videoFiles(this).sortedWith(
            compareByDescending<File> { VideoStorage.isFavorite(it) }
                .thenByDescending { it.lastModified() }
        )
        val totalBytes = videos.sumOf { VideoStorage.companionFilesFor(it).sumOf { file -> file.length() } }
        tvVideoSummary.text = getString(R.string.video_history_summary, videos.size, formatBytes(totalBytes))
        layoutVideoList.removeAllViews()
        if (videos.isEmpty()) {
            layoutVideoList.addView(TextView(this).apply {
                text = getString(R.string.video_history_empty)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
            })
        } else {
            videos.forEach { video ->
                layoutVideoList.addView(videoCard(video))
            }
        }
        btnDeleteAllVideos.isEnabled = videos.isNotEmpty()
    }

    private fun videoCard(video: File): View {
        val density = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 16f * density
            cardElevation = 0f
            setCardBackgroundColor(getColorCompat(R.color.surface_primary))
            isClickable = true
            isFocusable = true
            setOnClickListener { replayVideo(video) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val thumbWrap = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(82))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(loadThumbnail(video))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val play = TextView(this).apply {
            text = "▶"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 1f, 1f, android.graphics.Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        thumbWrap.addView(image)
        thumbWrap.addView(play)
        row.addView(thumbWrap)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        content.addView(TextView(this).apply {
            text = video.nameWithoutExtension
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 14f
            maxLines = 2
        })
        content.addView(TextView(this).apply {
            text = videoMetaLabel(video)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        content.addView(TextView(this).apply {
            text = if (VideoStorage.isFavorite(video)) {
                getString(R.string.video_favorite_badge)
            } else if (!VideoStorage.isAutoGeneratedVideoFile(video)) {
                getString(R.string.video_protected_badge)
            } else {
                getString(R.string.video_play_hint)
            }
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        actions.addView(actionText(getString(if (VideoStorage.isFavorite(video)) R.string.video_unfavorite else R.string.video_favorite)) {
            VideoStorage.setFavorite(video, !VideoStorage.isFavorite(video))
            renderVideoList()
        })
        actions.addView(actionText(getString(R.string.video_rename)) { showRenameDialog(video) })
        actions.addView(actionText(getString(R.string.video_delete_one)) { confirmDeleteVideo(video) })
        content.addView(actions)
        row.addView(content)
        card.addView(row)
        return card
    }

    private fun videoMetaLabel(video: File): String {
        val companions = VideoStorage.companionFilesFor(video)
        val totalBytes = companions.sumOf { it.length() }
        val hasTrack = VideoStorage.trackFileFor(video).exists()
        val hasSubtitle = VideoStorage.subtitleFileFor(video).exists()
        val extras = listOfNotNull(
            if (hasTrack) "轨迹" else null,
            if (hasSubtitle) "字幕" else null
        ).ifEmpty { listOf("仅视频") }.joinToString("+")
        return "${formatBytes(totalBytes)}  $extras"
    }

    private fun actionText(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 12f
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun showRenameDialog(video: File) {
        val input = EditText(this).apply {
            setText(video.nameWithoutExtension)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.video_rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_rename_title)
            .setView(input)
            .setPositiveButton(R.string.video_rename) { _, _ ->
                val renamed = VideoStorage.renameVideoGroup(video, input.text.toString())
                if (renamed == null) {
                    Toast.makeText(this, R.string.video_rename_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.video_rename_done, Toast.LENGTH_SHORT).show()
                    renderVideoList()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadThumbnail(video: File): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(video, Size(dp(264), dp(164)), null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(video.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            }
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun getColorCompat(colorRes: Int): Int {
        return androidx.core.content.ContextCompat.getColor(this, colorRes)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun fieldLabel(field: HistorySortField): String {
        return when (field) {
            HistorySortField.START_TIME -> "启动时间"
            HistorySortField.END_TIME -> "结束时间"
            HistorySortField.DURATION -> "驾驶时长"
            HistorySortField.MAX_SPEED -> "最高速度"
            HistorySortField.DISTANCE -> "行驶距离"
            HistorySortField.AVERAGE_SPEED -> "平均速度"
        }
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000f) {
            String.format("%.2f km", meters / 1000f)
        } else {
            String.format("%.0f m", meters)
        }
    }

    private fun formatGpsQuality(record: DriveRecord): String {
        val avgAccuracy = record.averageAccuracyMeters?.let { String.format("%.1fm", it) } ?: "--"
        val goodRatio = String.format("%.0f%%", record.goodFixRatio.coerceIn(0f, 1f) * 100f)
        return "平均精度 $avgAccuracy，好点率 $goodRatio"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    companion object {
        private const val PREF_HISTORY_SORT_FIELD = "history_sort_field"
        private const val PREF_HISTORY_SORT_ASC = "history_sort_asc"
    }
}

package com.thomas.carspeed

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {

    private val geocodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val historyExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val addressCache = mutableMapOf<String, String>()
    private val pendingAddressKeys = mutableSetOf<String>()

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
    @Volatile private var renderRequestId: Int = 0
    private var currentVideoItems: List<VideoListItem> = emptyList()

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

    override fun onDestroy() {
        geocodeExecutor.shutdownNow()
        historyExecutor.shutdownNow()
        super.onDestroy()
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

    private fun replayTrip(trip: VideoStorage.VideoTrip) {
        startActivity(
            Intent(this, VideoReplayActivity::class.java).apply {
                putStringArrayListExtra(
                    VideoReplayActivity.EXTRA_VIDEO_PATHS,
                    ArrayList(trip.segments.map { it.absolutePath })
                )
            }
        )
    }

    private fun confirmDeleteVideo(trip: VideoStorage.VideoTrip) {
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_one)
            .setMessage(trip.title)
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = VideoStorage.deleteVideoTrip(trip)
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllVideos() {
        val trips = currentVideoItems.map { it.trip }
        if (trips.isEmpty()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_all)
            .setMessage(getString(R.string.video_delete_all_confirm, trips.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = VideoStorage.deleteAllVideos(this)
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                render()
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
        val requestId = ++renderRequestId
        val sortField = currentField
        val sortAscending = ascending
        showLoadingState()
        val appContext = applicationContext
        historyExecutor.execute {
            val videoItems = VideoStorage.videoTrips(appContext)
                .map { trip -> buildVideoListItem(trip) }
                .sortedWith(
                    compareByDescending<VideoListItem> { it.favorite }
                        .thenByDescending { it.trip.lastModified }
                )
            if (requestId != renderRequestId) return@execute
            val totalBytes = videoItems.sumOf { it.totalBytes }
            val storedRecords = DriveRecordStore.loadRecords(appContext)
            val records = storedRecords.ifEmpty { videoTrackRecords(videoItems) }
            val sortedRecords = HistorySort.sort(records, sortField, sortAscending)
            val historyText = historyText(storedRecords.isEmpty(), sortedRecords, sortField, sortAscending)
            mainHandler.post {
                if (requestId != renderRequestId || isFinishing || isDestroyed) return@post
                renderVideoList(videoItems, totalBytes)
                tvHistory.text = historyText
            }
        }
    }

    private fun showLoadingState() {
        currentVideoItems = emptyList()
        btnDeleteAllVideos.isEnabled = false
        tvVideoSummary.text = "正在加载录像…"
        layoutVideoList.removeAllViews()
        layoutVideoList.addView(TextView(this).apply {
            text = "正在扫描驾驶记录…"
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
        })
        tvHistory.text = "正在加载历史驾驶记录…"
    }

    private fun historyText(
        fromVideoTracks: Boolean,
        records: List<DriveRecord>,
        sortField: HistorySortField,
        sortAscending: Boolean
    ): String {
        if (records.isEmpty()) {
            return getString(R.string.history_empty) +
                "\n\n记录明细以前只来自 HUD 行驶记录；现在录像结束会写入记录。旧视频如果没有轨迹文件，也无法补算。"
        }

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val text = buildString {
            appendLine(if (fromVideoTracks) "录像行程记录（由 GPS 轨迹补算）" else "历史驾驶记录（最多100条）")
            appendLine("排序字段: ${fieldLabel(sortField)}  顺序: ${if (sortAscending) "升序" else "降序"}")
            appendLine("====================")
            records.forEachIndexed { idx, r ->
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
        return text
    }

    private fun videoTrackRecords(videoItems: List<VideoListItem>): List<DriveRecord> {
        return videoItems.mapNotNull { item ->
            val track = item.track ?: return@mapNotNull null
            val points = track.points
            if (points.isEmpty()) return@mapNotNull null
            val summary = item.trackSummary ?: TrackMetrics.summary(points)
            val maxPoint = points.maxByOrNull { it.speedKmh }
            val startTimeMs = track.startTimeMs.takeIf { it > 0L } ?: points.first().timeMs
            val endTimeMs = points.last().timeMs.takeIf { it > 0L } ?: (startTimeMs + summary.durationMs)
            DriveRecord(
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                durationMs = summary.durationMs,
                maxSpeedKmh = summary.maxSpeedKmh,
                distanceMeters = summary.totalDistanceMeters,
                averageSpeedKmh = summary.averageSpeedKmh,
                maxSpeedLatitude = maxPoint?.latitude,
                maxSpeedLongitude = maxPoint?.longitude,
                maxSpeedAccuracyMeters = maxPoint?.accuracyMeters,
                averageAccuracyMeters = summary.averageAccuracyMeters,
                goodFixRatio = summary.goodFixRatio,
                routePoints = points.map { point ->
                    DriveRoutePoint(
                        timeMs = point.timeMs,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        speedKmh = point.speedKmh,
                        accuracyMeters = point.accuracyMeters,
                        bearingDegrees = point.bearingDegrees
                    )
                }
            )
        }
    }

    private fun buildVideoListItem(trip: VideoStorage.VideoTrip): VideoListItem {
        val track = VideoTrackStorage.readMergedTrack(trip.segments)
        val points = track?.points.orEmpty()
        val companions = VideoStorage.companionFilesFor(trip)
        return VideoListItem(
            trip = trip,
            favorite = VideoStorage.isFavorite(trip),
            totalBytes = companions.sumOf { it.length() },
            hasTrack = track != null || trip.segments.any { VideoStorage.trackFileFor(it).exists() },
            hasSubtitle = trip.segments.any { VideoStorage.subtitleFileFor(it).exists() },
            track = track,
            trackSummary = points.takeIf { it.isNotEmpty() }?.let { TrackMetrics.summary(it) },
            startPoint = points.firstOrNull(),
            endPoint = points.lastOrNull()
        )
    }

    private fun renderVideoList(items: List<VideoListItem>, totalBytes: Long) {
        currentVideoItems = items
        tvVideoSummary.text = getString(R.string.video_history_summary, items.size, formatBytes(totalBytes))
        layoutVideoList.removeAllViews()
        if (items.isEmpty()) {
            layoutVideoList.addView(TextView(this).apply {
                text = getString(R.string.video_history_empty)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
            })
        } else {
            items.forEach { item ->
                layoutVideoList.addView(videoCard(item))
            }
        }
        btnDeleteAllVideos.isEnabled = items.isNotEmpty()
    }

    private fun videoCard(item: VideoListItem): View {
        val trip = item.trip
        val density = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 16f * density
            cardElevation = 0f
            setCardBackgroundColor(getColorCompat(R.color.surface_primary))
            isClickable = true
            isFocusable = true
            setOnClickListener { replayTrip(trip) }
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
            alpha = 0.28f
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(android.R.drawable.ic_menu_slideshow)
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
            text = trip.title
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 14f
            maxLines = 2
        })
        content.addView(TextView(this).apply {
            text = videoMetaLabel(item)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        content.addView(TextView(this).apply {
            text = videoRouteLabel(item, this)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            maxLines = 2
        })
        content.addView(TextView(this).apply {
            text = if (item.favorite) {
                getString(R.string.video_favorite_badge)
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
        actions.addView(actionText(getString(if (item.favorite) R.string.video_unfavorite else R.string.video_favorite)) {
            VideoStorage.setFavorite(trip, !item.favorite)
            render()
        })
        if (trip.segments.size == 1) {
            actions.addView(actionText(getString(R.string.video_rename)) { showRenameDialog(trip.firstVideo) })
        } else {
            actions.addView(actionText(getString(R.string.video_segments_count, trip.segments.size)) { replayTrip(trip) })
        }
        actions.addView(actionText(getString(R.string.video_delete_one)) { confirmDeleteVideo(trip) })
        content.addView(actions)
        row.addView(content)
        card.addView(row)
        return card
    }

    private fun videoMetaLabel(item: VideoListItem): String {
        val extras = listOfNotNull(
            if (item.hasTrack) "轨迹" else null,
            if (item.hasSubtitle) "字幕" else null
        ).ifEmpty { listOf("仅视频") }.joinToString("+")
        val distance = item.trackSummary?.totalDistanceMeters?.takeIf { it > 0f }?.let { "GPS ${formatDistance(it)}" }
        val segments = if (item.trip.segments.size > 1) getString(R.string.video_segments_count, item.trip.segments.size) else null
        return listOfNotNull(segments, formatBytes(item.totalBytes), distance, extras).joinToString("  ·  ")
    }

    private fun videoRouteLabel(item: VideoListItem, target: TextView? = null, schedule: Boolean = true): String {
        val start = item.startPoint ?: return "路线：暂无 GPS 起终点"
        val end = item.endPoint ?: return "路线：暂无 GPS 起终点"
        val startKey = addressKey(start)
        val endKey = addressKey(end)
        val startAddress = cachedAddress(startKey)
        val endAddress = cachedAddress(endKey)
        if (startAddress != null && endAddress != null) {
            return "路线：$startAddress 到 $endAddress"
        }
        if (schedule) {
            scheduleAddressResolve(start, startKey, item, target)
            scheduleAddressResolve(end, endKey, item, target)
        }
        return "路线：解析中"
    }

    private fun scheduleAddressResolve(point: VideoTrackPoint, key: String, item: VideoListItem, target: TextView?) {
        if (cachedAddress(key) != null || key in pendingAddressKeys) return
        pendingAddressKeys += key
        geocodeExecutor.execute {
            val label = resolveShortAddress(point)
            getSharedPreferences(PREF_GEO_CACHE, MODE_PRIVATE)
                .edit()
                .putString(key, label)
                .apply()
            mainHandler.post {
                pendingAddressKeys -= key
                addressCache[key] = label
                target?.text = videoRouteLabel(item, target = null, schedule = false)
            }
        }
    }

    private fun cachedAddress(key: String): String? {
        return addressCache[key]
            ?: getSharedPreferences(PREF_GEO_CACHE, MODE_PRIVATE).getString(key, null)?.also { addressCache[key] = it }
    }

    private fun addressKey(point: VideoTrackPoint): String {
        return String.format(Locale.US, "geo_%.5f_%.5f", point.latitude, point.longitude)
    }

    @Suppress("DEPRECATION")
    private fun resolveShortAddress(point: VideoTrackPoint): String {
        return try {
            val address = Geocoder(this, Locale.CHINA)
                .getFromLocation(point.latitude, point.longitude, 1)
                ?.firstOrNull()
            shortAddress(address, point)
        } catch (_: RuntimeException) {
            coordinateLabel(point)
        } catch (_: java.io.IOException) {
            coordinateLabel(point)
        }
    }

    private fun shortAddress(address: Address?, point: VideoTrackPoint): String {
        if (address == null) return coordinateLabel(point)
        val province = cleanAddressPart(address.adminArea)
        val city = cleanAddressPart(address.locality ?: address.subAdminArea ?: address.adminArea)
        val district = cleanAddressPart(address.subLocality ?: address.subAdminArea)
            ?.takeIf { it != city && it != province }
        val place = listOf(
            address.featureName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) address.premises else null,
            address.thoroughfare,
            address.getAddressLine(0)
        ).mapNotNull { raw ->
            cleanAddressPart(raw)
                ?.replace("中国", "")
                ?.replace(province.orEmpty(), "")
                ?.replace(city.orEmpty(), "")
                ?.replace(district.orEmpty(), "")
                ?.trim()
                ?.takeIf { it.length >= 2 && it != city && it != district && !it.all { ch -> ch.isDigit() } }
        }.firstOrNull()

        val label = listOfNotNull(city, district, place)
            .distinct()
            .joinToString("")
            .ifBlank { coordinateLabel(point) }
        return shortenEndpoint(label)
    }

    private fun cleanAddressPart(value: String?): String? {
        return value
            ?.replace(Regex("[\\s,，]+"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun shortenEndpoint(label: String): String {
        return if (label.length > 18) label.take(17) + "…" else label
    }

    private fun coordinateLabel(point: VideoTrackPoint): String {
        return String.format(Locale.US, "%.5f,%.5f", point.latitude, point.longitude)
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
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        return TrackMetrics.formatDistance(meters)
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

    private data class VideoListItem(
        val trip: VideoStorage.VideoTrip,
        val favorite: Boolean,
        val totalBytes: Long,
        val hasTrack: Boolean,
        val hasSubtitle: Boolean,
        val track: VideoTrack?,
        val trackSummary: TrackSummary?,
        val startPoint: VideoTrackPoint?,
        val endPoint: VideoTrackPoint?
    )

    companion object {
        private const val PREF_HISTORY_SORT_FIELD = "history_sort_field"
        private const val PREF_HISTORY_SORT_ASC = "history_sort_asc"
        private const val PREF_GEO_CACHE = "history_geo_cache"
    }
}

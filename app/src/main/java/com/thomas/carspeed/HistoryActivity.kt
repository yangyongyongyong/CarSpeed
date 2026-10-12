package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.location.Address
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.app.DatePickerDialog
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {

    private val geocodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val historyExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val addressCache = mutableMapOf<String, String>()
    private val pendingAddressKeys = mutableSetOf<String>()
    private val thumbnailCache = mutableMapOf<String, Bitmap?>()

    private lateinit var spSortField: Spinner
    private lateinit var btnSortOrder: Button
    private lateinit var btnVideoFilterDialog: Button
    private lateinit var btnVideoFilterClear: Button
    private lateinit var btnVideoScopeAll: MaterialButton
    private lateinit var btnVideoScopeFavorites: MaterialButton
    private lateinit var btnVideoPrevPage: Button
    private lateinit var btnVideoNextPage: Button
    private lateinit var btnDeleteAllVideos: Button
    private lateinit var tvVideoSummary: TextView
    private lateinit var tvVideoPage: TextView
    private lateinit var layoutVideoList: LinearLayout
    private lateinit var tvGpsTrackSummary: TextView
    private lateinit var layoutGpsTrackList: LinearLayout
    private lateinit var tvHistory: TextView
    private lateinit var toolbar: MaterialToolbar

    private var currentField: HistorySortField = HistorySortField.START_TIME
    private var ascending: Boolean = false
    private var filterSingleDayMs: Long? = null
    private var filterRangeStartMs: Long? = null
    private var filterRangeEndMs: Long? = null
    private var filterMinDurationMs: Long? = null
    private var filterMaxDurationMs: Long? = null
    private var filterMinDistanceMeters: Float? = null
    private var filterMaxDistanceMeters: Float? = null
    private var showFavoriteVideosOnly = false
    private var videoPageIndex = 0
    @Volatile private var renderRequestId: Int = 0
    private var currentVideoItems: List<VideoListItem> = emptyList()
    private var currentFilteredVideoTrips: List<VideoStorage.VideoTrip> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        spSortField = findViewById(R.id.spSortField)
        btnSortOrder = findViewById(R.id.btnSortOrder)
        btnVideoFilterDialog = findViewById(R.id.btnVideoFilterDialog)
        btnVideoFilterClear = findViewById(R.id.btnVideoFilterClear)
        btnVideoScopeAll = findViewById(R.id.btnVideoScopeAll)
        btnVideoScopeFavorites = findViewById(R.id.btnVideoScopeFavorites)
        btnVideoPrevPage = findViewById(R.id.btnVideoPrevPage)
        btnVideoNextPage = findViewById(R.id.btnVideoNextPage)
        btnDeleteAllVideos = findViewById(R.id.btnDeleteAllVideos)
        tvVideoSummary = findViewById(R.id.tvVideoSummary)
        tvVideoPage = findViewById(R.id.tvVideoPage)
        layoutVideoList = findViewById(R.id.layoutVideoList)
        tvGpsTrackSummary = findViewById(R.id.tvGpsTrackSummary)
        layoutGpsTrackList = findViewById(R.id.layoutGpsTrackList)
        tvHistory = findViewById(R.id.tvHistory)
        toolbar = findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        currentField = HistorySortField.entries[
            prefs.getInt(PREF_HISTORY_SORT_FIELD, HistorySortField.START_TIME.ordinal)
                .coerceIn(0, HistorySortField.entries.lastIndex)
        ]
        ascending = prefs.getBoolean(PREF_HISTORY_SORT_ASC, false)
        filterSingleDayMs = prefs.getLong(PREF_VIDEO_FILTER_DAY_MS, 0L).takeIf { it > 0L }
        filterRangeStartMs = prefs.getLong(PREF_VIDEO_FILTER_RANGE_START_MS, 0L).takeIf { it > 0L }
        filterRangeEndMs = prefs.getLong(PREF_VIDEO_FILTER_RANGE_END_MS, 0L).takeIf { it > 0L }
        filterMinDurationMs = prefs.getLong(PREF_VIDEO_FILTER_MIN_DURATION_MS, 0L).takeIf { it > 0L }
        filterMaxDurationMs = prefs.getLong(PREF_VIDEO_FILTER_MAX_DURATION_MS, 0L).takeIf { it > 0L }
        filterMinDistanceMeters = prefs.getFloat(PREF_VIDEO_FILTER_MIN_DISTANCE_METERS, -1f).takeIf { it >= 0f }
        filterMaxDistanceMeters = prefs.getFloat(PREF_VIDEO_FILTER_MAX_DISTANCE_METERS, -1f).takeIf { it >= 0f }

        setupSortUi()
        setupTimeFilterUi()
        setupFavoriteFilterUi()
        setupPagingUi()
        btnDeleteAllVideos.setOnClickListener { confirmDeleteAllVideos() }
        render()
    }

    override fun onDestroy() {
        geocodeExecutor.shutdownNow()
        historyExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
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

    private fun setupTimeFilterUi() {
        updateTimeFilterButtons()
        btnVideoFilterDialog.setOnClickListener { showVideoFilterDialog() }
        btnVideoFilterClear.setOnClickListener {
            clearVideoFilter()
            videoPageIndex = 0
            persistTimeFilterPref()
            updateTimeFilterButtons()
            render()
        }
    }

    private fun setupPagingUi() {
        btnVideoPrevPage.setOnClickListener {
            if (videoPageIndex > 0) {
                videoPageIndex -= 1
                render()
            }
        }
        btnVideoNextPage.setOnClickListener {
            videoPageIndex += 1
            render()
        }
    }

    private fun setupFavoriteFilterUi() {
        updateVideoScopeButtons()
        btnVideoScopeAll.setOnClickListener {
            if (!showFavoriteVideosOnly) return@setOnClickListener
            showFavoriteVideosOnly = false
            videoPageIndex = 0
            updateVideoScopeButtons()
            render()
        }
        btnVideoScopeFavorites.setOnClickListener {
            if (showFavoriteVideosOnly) return@setOnClickListener
            showFavoriteVideosOnly = true
            videoPageIndex = 0
            updateVideoScopeButtons()
            render()
        }
    }

    private fun updateVideoScopeButtons() {
        styleVideoScopeButton(btnVideoScopeAll, !showFavoriteVideosOnly)
        styleVideoScopeButton(btnVideoScopeFavorites, showFavoriteVideosOnly)
    }

    private fun styleVideoScopeButton(button: MaterialButton, selected: Boolean) {
        button.isSelected = selected
        button.backgroundTintList = ColorStateList.valueOf(
            getColorCompat(if (selected) R.color.brand_blue else R.color.surface_secondary)
        )
        button.strokeColor = ColorStateList.valueOf(
            getColorCompat(if (selected) R.color.brand_blue else R.color.divider_soft)
        )
        button.strokeWidth = dp(1)
        button.setTextColor(getColorCompat(if (selected) R.color.white else R.color.text_primary))
    }

    private fun showVideoFilterDialog() {
        var dayMs = filterSingleDayMs
        var rangeStartMs = filterRangeStartMs
        var rangeEndMs = filterRangeEndMs
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val cbDay = filterCheckBox(getString(R.string.video_filter_date), dayMs != null)
        val btnDay = filterButton(dayMs?.let { formatFilterDate(it) } ?: getString(R.string.video_filter_date_none))
        btnDay.setOnClickListener {
            showDatePicker(dayMs ?: System.currentTimeMillis()) { picked ->
                dayMs = startOfDay(picked)
                cbDay.isChecked = true
                btnDay.text = formatFilterDate(dayMs!!)
            }
        }
        content.addView(cbDay)
        content.addView(btnDay)

        val cbRange = filterCheckBox(getString(R.string.video_filter_date_range), rangeStartMs != null || rangeEndMs != null)
        val rowRange = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRangeStart = filterButton(rangeStartMs?.let { formatFilterDate(it) } ?: getString(R.string.video_filter_start_none))
        val btnRangeEnd = filterButton(rangeEndMs?.let { formatFilterDate(it) } ?: getString(R.string.video_filter_end_none))
        btnRangeStart.setOnClickListener {
            showDatePicker(rangeStartMs ?: System.currentTimeMillis()) { picked ->
                rangeStartMs = startOfDay(picked)
                cbRange.isChecked = true
                btnRangeStart.text = formatFilterDate(rangeStartMs!!)
            }
        }
        btnRangeEnd.setOnClickListener {
            showDatePicker(rangeEndMs ?: System.currentTimeMillis()) { picked ->
                rangeEndMs = endOfDay(picked)
                cbRange.isChecked = true
                btnRangeEnd.text = formatFilterDate(rangeEndMs!!)
            }
        }
        rowRange.addView(btnRangeStart, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        rowRange.addView(btnRangeEnd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        content.addView(cbRange)
        content.addView(rowRange)

        val cbDuration = filterCheckBox(getString(R.string.video_filter_duration), filterMinDurationMs != null || filterMaxDurationMs != null)
        val durationRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etMinDuration = filterEditText(R.string.video_filter_min_duration_hint, filterMinDurationMs?.let { (it / 1000L).toString() })
        val etMaxDuration = filterEditText(R.string.video_filter_max_duration_hint, filterMaxDurationMs?.let { (it / 1000L).toString() })
        durationRow.addView(etMinDuration, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        durationRow.addView(etMaxDuration, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        content.addView(cbDuration)
        content.addView(durationRow)

        val cbDistance = filterCheckBox(getString(R.string.video_filter_distance), filterMinDistanceMeters != null || filterMaxDistanceMeters != null)
        val distanceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etMinDistance = filterEditText(R.string.video_filter_min_distance_hint, filterMinDistanceMeters?.let { trimNumber(it / 1000f) }, decimal = true)
        val etMaxDistance = filterEditText(R.string.video_filter_max_distance_hint, filterMaxDistanceMeters?.let { trimNumber(it / 1000f) }, decimal = true)
        distanceRow.addView(etMinDistance, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        distanceRow.addView(etMaxDistance, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        content.addView(cbDistance)
        content.addView(distanceRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.video_filter_dialog_title)
            .setView(content)
            .setPositiveButton(R.string.video_filter_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nextDayMs = if (cbDay.isChecked) dayMs ?: startOfDay(System.currentTimeMillis()) else null
            val nextRangeStartMs = if (cbRange.isChecked) rangeStartMs else null
            val nextRangeEndMs = if (cbRange.isChecked) rangeEndMs else null
            val nextMinDurationMs = if (cbDuration.isChecked) etMinDuration.text.toString().toLongOrNull()?.times(1000L) else null
            val nextMaxDurationMs = if (cbDuration.isChecked) etMaxDuration.text.toString().toLongOrNull()?.times(1000L) else null
            val nextMinDistanceMeters = if (cbDistance.isChecked) etMinDistance.text.toString().toFloatOrNull()?.times(1000f) else null
            val nextMaxDistanceMeters = if (cbDistance.isChecked) etMaxDistance.text.toString().toFloatOrNull()?.times(1000f) else null
            if (invalidRange(nextRangeStartMs, nextRangeEndMs) ||
                invalidRange(nextMinDurationMs, nextMaxDurationMs) ||
                invalidRange(nextMinDistanceMeters, nextMaxDistanceMeters)
            ) {
                Toast.makeText(this, R.string.video_filter_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            filterSingleDayMs = nextDayMs
            filterRangeStartMs = nextRangeStartMs
            filterRangeEndMs = nextRangeEndMs
            filterMinDurationMs = nextMinDurationMs
            filterMaxDurationMs = nextMaxDurationMs
            filterMinDistanceMeters = nextMinDistanceMeters
            filterMaxDistanceMeters = nextMaxDistanceMeters
            videoPageIndex = 0
            persistTimeFilterPref()
            updateTimeFilterButtons()
            render()
            dialog.dismiss()
        }
    }

    private fun showDatePicker(initialMs: Long, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialMs }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                onPicked(picked)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateTimeFilterButtons() {
        btnVideoFilterDialog.text = if (hasVideoFilter()) {
            getString(R.string.video_filter_summary, videoFilterSummary())
        } else {
            getString(R.string.video_filter_none)
        }
        btnVideoFilterClear.isEnabled = hasVideoFilter()
    }

    private fun persistTimeFilterPref() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putLong(PREF_VIDEO_FILTER_DAY_MS, filterSingleDayMs ?: 0L)
            .putLong(PREF_VIDEO_FILTER_RANGE_START_MS, filterRangeStartMs ?: 0L)
            .putLong(PREF_VIDEO_FILTER_RANGE_END_MS, filterRangeEndMs ?: 0L)
            .putLong(PREF_VIDEO_FILTER_MIN_DURATION_MS, filterMinDurationMs ?: 0L)
            .putLong(PREF_VIDEO_FILTER_MAX_DURATION_MS, filterMaxDurationMs ?: 0L)
            .putFloat(PREF_VIDEO_FILTER_MIN_DISTANCE_METERS, filterMinDistanceMeters ?: -1f)
            .putFloat(PREF_VIDEO_FILTER_MAX_DISTANCE_METERS, filterMaxDistanceMeters ?: -1f)
            .apply()
    }

    private fun clearVideoFilter() {
        filterSingleDayMs = null
        filterRangeStartMs = null
        filterRangeEndMs = null
        filterMinDurationMs = null
        filterMaxDurationMs = null
        filterMinDistanceMeters = null
        filterMaxDistanceMeters = null
    }

    private fun filterCheckBox(textValue: String, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = textValue
            isChecked = checked
            setTextColor(getColorCompat(R.color.text_primary))
        }
    }

    private fun filterButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            isAllCaps = false
        }
    }

    private fun filterEditText(hintRes: Int, value: String?, decimal: Boolean = false): EditText {
        return EditText(this).apply {
            hint = getString(hintRes)
            setText(value.orEmpty())
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            setSingleLine(true)
        }
    }

    private fun videoFilterSummary(): String {
        return listOfNotNull(
            filterSingleDayMs?.let { "单日 ${formatFilterDate(it)}" },
            rangeText(filterRangeStartMs, filterRangeEndMs, "日期"),
            rangeText(filterMinDurationMs, filterMaxDurationMs, "时长") { formatDuration(it) },
            rangeText(filterMinDistanceMeters, filterMaxDistanceMeters, "里程") { trimNumber(it / 1000f) + "km" }
        ).joinToString("；").ifBlank { "不限" }
    }

    private fun rangeText(start: Long?, end: Long?, label: String, formatter: (Long) -> String = { it.toString() }): String? {
        if (start == null && end == null) return null
        return "$label ${start?.let(formatter) ?: "不限"}~${end?.let(formatter) ?: "不限"}"
    }

    private fun rangeText(start: Float?, end: Float?, label: String, formatter: (Float) -> String): String? {
        if (start == null && end == null) return null
        return "$label ${start?.let(formatter) ?: "不限"}~${end?.let(formatter) ?: "不限"}"
    }

    private fun invalidRange(start: Long?, end: Long?): Boolean = start != null && end != null && end < start

    private fun invalidRange(start: Float?, end: Float?): Boolean = start != null && end != null && end < start

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

    private fun exportTrip(trip: VideoStorage.VideoTrip) {
        startActivity(
            Intent(this, ShareExportActivity::class.java).apply {
                putStringArrayListExtra(
                    ShareExportActivity.EXTRA_VIDEO_PATHS,
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
                val deleted = VideoStorage.deleteVideoTrip(this, trip)
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllVideos() {
        val trips = currentFilteredVideoTrips
        if (trips.isEmpty()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_all)
            .setMessage(getString(R.string.video_delete_all_confirm, trips.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = trips.sumOf { trip -> VideoStorage.deleteVideoTrip(this, trip) }
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
        val filter = currentVideoFilter()
        val favoriteOnly = showFavoriteVideosOnly
        showLoadingState()
        val appContext = applicationContext
        historyExecutor.execute {
            val allTrips = VideoStorage.videoTrips(appContext)
                .sortedWith(
                    compareByDescending<VideoStorage.VideoTrip> { VideoStorage.isFavorite(appContext, it) }
                        .thenByDescending { it.lastModified }
                )
            val scopedTrips = if (favoriteOnly) {
                allTrips.filter { trip -> VideoStorage.isFavorite(appContext, trip) }
            } else {
                allTrips
            }
            val needsDetailedFilter = filter.requiresDetailedVideoData()
            val allVideoItems = if (needsDetailedFilter) {
                scopedTrips.map { trip -> buildVideoListItem(appContext, trip) }
            } else {
                emptyList()
            }
            val filteredTrips = if (needsDetailedFilter) {
                allVideoItems.filter { item -> item.matchesFilter(filter) }.map { it.trip }
            } else {
                scopedTrips.filter { trip -> trip.matchesLightFilter(filter) }
            }
            val pageCount = pageCount(filteredTrips.size)
            val pageIndex = videoPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val pageTrips = filteredTrips.drop(pageIndex * VIDEO_PAGE_SIZE).take(VIDEO_PAGE_SIZE)
            val videoItems = if (needsDetailedFilter) {
                allVideoItems.filter { item -> item.trip in pageTrips }
            } else {
                pageTrips.map { trip -> buildVideoListItem(appContext, trip) }
            }
            if (requestId != renderRequestId) return@execute
            val totalBytes = videoItems.sumOf { it.totalBytes }
            val storedRecords = DriveRecordStore.loadRecords(appContext)
            val records = storedRecords.ifEmpty { videoTrackRecords(videoItems) }
                .filter { record -> recordMatchesFilter(record, filter) }
            val sortedRecords = HistorySort.sort(records, sortField, sortAscending)
            val gpsTrackRecords = HistorySort.sort(
                storedRecords.filter { record ->
                    record.source == DriveRecordStore.SOURCE_GPS_TRACK && record.routePoints.isNotEmpty()
                },
                sortField,
                sortAscending
            )
            val historyText = historyText(storedRecords.isEmpty(), sortedRecords, sortField, sortAscending)
            mainHandler.post {
                if (requestId != renderRequestId || isFinishing || isDestroyed) return@post
                videoPageIndex = pageIndex
                currentFilteredVideoTrips = filteredTrips
                renderVideoList(
                    videoItems,
                    totalBytes,
                    filteredTrips.size,
                    scopedTrips.size,
                    pageIndex,
                    favoriteOnly
                )
                renderGpsTrackList(gpsTrackRecords)
                tvHistory.text = historyText
            }
        }
    }

    private fun showLoadingState() {
        currentVideoItems = emptyList()
        currentFilteredVideoTrips = emptyList()
        btnDeleteAllVideos.isEnabled = false
        btnVideoPrevPage.isEnabled = false
        btnVideoNextPage.isEnabled = false
        tvVideoPage.text = ""
        tvVideoSummary.text = "正在加载录像…"
        layoutVideoList.removeAllViews()
        layoutVideoList.addView(TextView(this).apply {
            text = "正在扫描驾驶记录…"
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
        })
        tvGpsTrackSummary.text = getString(R.string.gps_track_history_summary, 0)
        layoutGpsTrackList.removeAllViews()
        layoutGpsTrackList.addView(TextView(this).apply {
            text = "正在加载 GPS 轨迹记录…"
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
                source = DriveRecordStore.SOURCE_VIDEO,
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

    private fun buildVideoListItem(context: Context, trip: VideoStorage.VideoTrip): VideoListItem {
        val track = VideoTrackStorage.readMergedTrack(context, trip.segments)
        val points = track?.points.orEmpty()
        val trackSummary = points.takeIf { it.isNotEmpty() }?.let { TrackMetrics.summary(it) }
        val startTimeMs = tripStartTimeMs(trip, track)
        val endTimeMs = tripEndTimeMs(trip, track, startTimeMs)
        val durationMs = trip.segments.sumOf { videoDurationMs(it) }
            .takeIf { it > 0L }
            ?: trackSummary?.durationMs
            ?: (endTimeMs - startTimeMs).coerceAtLeast(0L)
        val companions = VideoStorage.companionFilesFor(context, trip)
        return VideoListItem(
            trip = trip,
            favorite = VideoStorage.isFavorite(context, trip),
            totalBytes = companions.sumOf { it.length() },
            hasTrack = track != null || trip.segments.any { video -> VideoStorage.trackFilesFor(context, video).any { it.exists() } },
            hasSubtitle = trip.segments.any { VideoStorage.subtitleFileFor(it).exists() },
            track = track,
            trackSummary = trackSummary,
            startPoint = points.firstOrNull(),
            endPoint = points.lastOrNull(),
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            durationMs = durationMs,
            distanceMeters = trackSummary?.totalDistanceMeters ?: 0f
        )
    }

    private fun renderVideoList(
        items: List<VideoListItem>,
        pageBytes: Long,
        filteredCount: Int,
        allCount: Int,
        pageIndex: Int,
        favoriteOnly: Boolean
    ) {
        currentVideoItems = items
        val firstNumber = if (filteredCount == 0) 0 else pageIndex * VIDEO_PAGE_SIZE + 1
        val lastNumber = (pageIndex * VIDEO_PAGE_SIZE + items.size).coerceAtMost(filteredCount)
        tvVideoSummary.text = when {
            favoriteOnly && hasVideoFilter() -> getString(
                R.string.video_history_page_favorite_filtered_summary,
                firstNumber,
                lastNumber,
                filteredCount,
                allCount,
                formatBytes(pageBytes)
            )
            favoriteOnly -> getString(
                R.string.video_history_page_favorite_summary,
                firstNumber,
                lastNumber,
                allCount,
                formatBytes(pageBytes)
            )
            hasVideoFilter() -> getString(
                R.string.video_history_page_filtered_summary,
                firstNumber,
                lastNumber,
                filteredCount,
                allCount,
                formatBytes(pageBytes)
            )
            else -> getString(
                R.string.video_history_page_summary,
                firstNumber,
                lastNumber,
                allCount,
                formatBytes(pageBytes)
            )
        }
        val totalPages = pageCount(filteredCount)
        tvVideoPage.text = getString(R.string.page_indicator, if (filteredCount == 0) 0 else pageIndex + 1, totalPages)
        btnVideoPrevPage.isEnabled = pageIndex > 0
        btnVideoNextPage.isEnabled = pageIndex + 1 < totalPages
        layoutVideoList.removeAllViews()
        if (items.isEmpty()) {
            layoutVideoList.addView(TextView(this).apply {
                text = getString(videoEmptyTextRes(favoriteOnly))
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

    private fun videoEmptyTextRes(favoriteOnly: Boolean): Int {
        return when {
            favoriteOnly && hasVideoFilter() -> R.string.video_favorites_filter_empty
            favoriteOnly -> R.string.video_favorites_empty
            hasVideoFilter() -> R.string.video_history_filter_empty
            else -> R.string.video_history_empty
        }
    }

    private fun renderGpsTrackList(records: List<DriveRecord>) {
        tvGpsTrackSummary.text = getString(R.string.gps_track_history_summary, records.size)
        layoutGpsTrackList.removeAllViews()
        if (records.isEmpty()) {
            layoutGpsTrackList.addView(TextView(this).apply {
                text = getString(R.string.gps_track_history_empty)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
            })
            return
        }
        records.forEach { record -> layoutGpsTrackList.addView(gpsTrackCard(record)) }
    }

    private fun gpsTrackCard(record: DriveRecord): View {
        val card = MaterialCardView(this).apply {
            radius = 14f * resources.displayMetrics.density
            cardElevation = 0f
            setCardBackgroundColor(getColorCompat(R.color.surface_primary))
            isClickable = true
            isFocusable = true
            setOnClickListener { replayTrackRecord(record) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        content.addView(TextView(this).apply {
            text = "${fmt.format(Date(record.startTimeMs))} - ${fmt.format(Date(record.endTimeMs))}"
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 14f
        })
        content.addView(TextView(this).apply {
            text = listOf(
                "时长 ${formatDuration(record.durationMs)}",
                "里程 ${formatDistance(record.distanceMeters)}",
                "最高 ${String.format(Locale.US, "%.1f", record.maxSpeedKmh)} km/h",
                "点 ${record.routePoints.size}"
            ).joinToString("  ·  ")
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.gps_track_replay_hint)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        card.addView(content)
        return card
    }

    private fun replayTrackRecord(record: DriveRecord) {
        startActivity(
            Intent(this, VideoReplayActivity::class.java).apply {
                putExtra(VideoReplayActivity.EXTRA_TRACK_ONLY, true)
                putExtra(VideoReplayActivity.EXTRA_TRACK_START_MS, record.startTimeMs)
                putExtra(VideoReplayActivity.EXTRA_TRACK_END_MS, record.endTimeMs)
                putExtra(VideoReplayActivity.EXTRA_TRACK_TITLE, getString(R.string.gps_track_replay_title))
            }
        )
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
            tag = trip.firstVideo.absolutePath
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        loadVideoThumbnail(trip.firstVideo, image)
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
            VideoStorage.setFavorite(this, trip, !item.favorite)
            render()
        })
        if (trip.segments.size == 1) {
            actions.addView(actionText(getString(R.string.video_rename)) { showRenameDialog(trip.firstVideo) })
        } else {
            actions.addView(actionText(getString(R.string.video_segments_count, trip.segments.size)) { replayTrip(trip) })
        }
        actions.addView(actionText(getString(R.string.video_export_share)) { exportTrip(trip) })
        actions.addView(actionText(getString(R.string.video_delete_one)) { confirmDeleteVideo(trip) })
        content.addView(actions)
        row.addView(content)
        card.addView(row)
        return card
    }

    private fun loadVideoThumbnail(videoFile: File, target: ImageView) {
        val key = videoFile.absolutePath
        if (thumbnailCache.containsKey(key)) {
            thumbnailCache[key]?.let { bitmap -> showVideoThumbnail(target, key, bitmap) }
            return
        }
        thumbnailExecutor.execute {
            val bitmap = extractVideoThumbnail(videoFile, dp(132), dp(82))
            mainHandler.post {
                thumbnailCache[key] = bitmap
                bitmap?.let { showVideoThumbnail(target, key, it) }
            }
        }
    }

    private fun showVideoThumbnail(target: ImageView, key: String, bitmap: Bitmap) {
        if (target.tag != key) return
        target.alpha = 1f
        target.scaleType = ImageView.ScaleType.CENTER_CROP
        target.setImageBitmap(bitmap)
    }

    private fun extractVideoThumbnail(videoFile: File, targetWidth: Int, targetHeight: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            centerCrop(frame, targetWidth, targetHeight)
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // 忽略系统解码器释放异常，历史页保留占位图即可。
            }
        }
    }

    private fun centerCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width <= 0 || source.height <= 0 || targetWidth <= 0 || targetHeight <= 0) return source
        val scale = maxOf(targetWidth / source.width.toFloat(), targetHeight / source.height.toFloat())
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(targetWidth)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(targetHeight)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        val left = ((scaled.width - targetWidth) / 2).coerceAtLeast(0)
        val top = ((scaled.height - targetHeight) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(scaled, left, top, targetWidth.coerceAtMost(scaled.width), targetHeight.coerceAtMost(scaled.height)).also {
            if (scaled !== it && !scaled.isRecycled) scaled.recycle()
        }
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

    private fun hasVideoFilter(): Boolean = currentVideoFilter().hasAny()

    private fun currentVideoFilter(): VideoFilter {
        val day = filterSingleDayMs?.let { startOfDay(it) }
        return VideoFilter(
            dayStartMs = day,
            dayEndMs = day?.let { endOfDay(it) },
            rangeStartMs = filterRangeStartMs,
            rangeEndMs = filterRangeEndMs,
            minDurationMs = filterMinDurationMs,
            maxDurationMs = filterMaxDurationMs,
            minDistanceMeters = filterMinDistanceMeters,
            maxDistanceMeters = filterMaxDistanceMeters
        )
    }

    private fun VideoFilter.requiresDetailedVideoData(): Boolean {
        return minDurationMs != null || maxDurationMs != null || minDistanceMeters != null || maxDistanceMeters != null
    }

    private fun VideoStorage.VideoTrip.matchesLightFilter(filter: VideoFilter): Boolean {
        val start = tripStartTimeMs(this, track = null)
        val end = tripEndTimeMs(this, track = null, startTimeMs = start)
        if (!rangeMatches(start, end, filter.dayStartMs, filter.dayEndMs)) return false
        if (!rangeMatches(start, end, filter.rangeStartMs, filter.rangeEndMs)) return false
        return true
    }

    private fun VideoListItem.matchesFilter(filter: VideoFilter): Boolean {
        if (!rangeMatches(startTimeMs, endTimeMs, filter.dayStartMs, filter.dayEndMs)) return false
        if (!rangeMatches(startTimeMs, endTimeMs, filter.rangeStartMs, filter.rangeEndMs)) return false
        if (filter.minDurationMs != null && durationMs < filter.minDurationMs) return false
        if (filter.maxDurationMs != null && durationMs > filter.maxDurationMs) return false
        if (filter.minDistanceMeters != null && distanceMeters < filter.minDistanceMeters) return false
        if (filter.maxDistanceMeters != null && distanceMeters > filter.maxDistanceMeters) return false
        return true
    }

    private fun recordMatchesFilter(record: DriveRecord, filter: VideoFilter): Boolean {
        val end = record.endTimeMs.takeIf { it > 0L } ?: record.startTimeMs
        if (!rangeMatches(record.startTimeMs, end, filter.dayStartMs, filter.dayEndMs)) return false
        if (!rangeMatches(record.startTimeMs, end, filter.rangeStartMs, filter.rangeEndMs)) return false
        if (filter.minDurationMs != null && record.durationMs < filter.minDurationMs) return false
        if (filter.maxDurationMs != null && record.durationMs > filter.maxDurationMs) return false
        if (filter.minDistanceMeters != null && record.distanceMeters < filter.minDistanceMeters) return false
        if (filter.maxDistanceMeters != null && record.distanceMeters > filter.maxDistanceMeters) return false
        return true
    }

    private fun rangeMatches(itemStartMs: Long, itemEndMs: Long, allowedStartMs: Long?, allowedEndMs: Long?): Boolean {
        if (allowedStartMs == null && allowedEndMs == null) return true
        val start = itemStartMs.takeIf { it > 0L } ?: itemEndMs
        val end = itemEndMs.takeIf { it > 0L } ?: start
        if (allowedStartMs != null && end < allowedStartMs) return false
        if (allowedEndMs != null && start > allowedEndMs) return false
        return true
    }

    private fun pageCount(itemCount: Int): Int {
        return ((itemCount + VIDEO_PAGE_SIZE - 1) / VIDEO_PAGE_SIZE).coerceAtLeast(1)
    }

    private fun tripStartTimeMs(trip: VideoStorage.VideoTrip, track: VideoTrack?): Long {
        track?.startTimeMs?.takeIf { it > 0L }?.let { return it }
        parseVideoTimeMs(trip.firstVideo.nameWithoutExtension)?.let { return it }
        parseTripTimeMs(trip.directory.name)?.let { return it }
        return trip.firstVideo.lastModified().takeIf { it > 0L } ?: 0L
    }

    private fun tripEndTimeMs(trip: VideoStorage.VideoTrip, track: VideoTrack?, startTimeMs: Long): Long {
        track?.points?.lastOrNull()?.timeMs?.takeIf { it > 0L }?.let { return it }
        val modified = trip.lastModified.takeIf { it > 0L }
        if (modified != null && modified >= startTimeMs) return modified
        return startTimeMs
    }

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

    private fun parseVideoTimeMs(name: String): Long? {
        val match = VIDEO_NAME_REGEX.find(name) ?: return null
        return parseCompactDateTime(match.groupValues[1], match.groupValues[2])
    }

    private fun parseTripTimeMs(name: String): Long? {
        val match = TRIP_NAME_REGEX.find(name) ?: return null
        return parseCompactDateTime(match.groupValues[1], match.groupValues[2])
    }

    private fun parseCompactDateTime(date: String, time: String): Long? {
        return try {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse("$date-$time")?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultFilterStartMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun defaultFilterEndMs(): Long = System.currentTimeMillis()

    private fun formatFilterTime(ms: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun formatFilterDate(ms: Long): String {
        return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(ms))
    }

    private fun startOfDay(ms: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfDay(ms: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun trimNumber(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
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
                val renamed = VideoStorage.renameVideoGroup(this, video, input.text.toString())
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
        val endPoint: VideoTrackPoint?,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val durationMs: Long,
        val distanceMeters: Float
    )

    private data class VideoFilter(
        val dayStartMs: Long?,
        val dayEndMs: Long?,
        val rangeStartMs: Long?,
        val rangeEndMs: Long?,
        val minDurationMs: Long?,
        val maxDurationMs: Long?,
        val minDistanceMeters: Float?,
        val maxDistanceMeters: Float?
    ) {
        fun hasAny(): Boolean {
            return dayStartMs != null || dayEndMs != null ||
                rangeStartMs != null || rangeEndMs != null ||
                minDurationMs != null || maxDurationMs != null ||
                minDistanceMeters != null || maxDistanceMeters != null
        }
    }

    companion object {
        private const val PREF_HISTORY_SORT_FIELD = "history_sort_field"
        private const val PREF_HISTORY_SORT_ASC = "history_sort_asc"
        private const val PREF_VIDEO_FILTER_DAY_MS = "history_video_filter_day_ms"
        private const val PREF_VIDEO_FILTER_RANGE_START_MS = "history_video_filter_range_start_ms"
        private const val PREF_VIDEO_FILTER_RANGE_END_MS = "history_video_filter_range_end_ms"
        private const val PREF_VIDEO_FILTER_MIN_DURATION_MS = "history_video_filter_min_duration_ms"
        private const val PREF_VIDEO_FILTER_MAX_DURATION_MS = "history_video_filter_max_duration_ms"
        private const val PREF_VIDEO_FILTER_MIN_DISTANCE_METERS = "history_video_filter_min_distance_meters"
        private const val PREF_VIDEO_FILTER_MAX_DISTANCE_METERS = "history_video_filter_max_distance_meters"
        private const val PREF_GEO_CACHE = "history_geo_cache"
        private const val VIDEO_PAGE_SIZE = 5
        private val VIDEO_NAME_REGEX = Regex("carspeed-(\\d{8})-(\\d{6})")
        private val TRIP_NAME_REGEX = Regex("carspeed-trip-(\\d{8})-(\\d{6})")
    }
}

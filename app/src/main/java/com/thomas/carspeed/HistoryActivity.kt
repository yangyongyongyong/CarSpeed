package com.thomas.carspeed

import android.animation.LayoutTransition
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HistoryActivity : AppCompatActivity() {

    private val historyExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = mutableMapOf<String, Bitmap?>()
    private val videoUriByPath = HashMap<String, android.net.Uri>()
    private var videoUrisLoaded = false
    private var routePhotoTimesMs: List<Long> = emptyList()

    private lateinit var btnVideoFilterDialog: Button
    private lateinit var btnVideoFilterClear: Button
    private lateinit var btnVideoDate: MaterialButton
    private lateinit var btnVideoDateClear: MaterialButton
    private lateinit var btnVideoScopeAll: MaterialButton
    private lateinit var btnVideoScopeFavorites: MaterialButton
    private lateinit var btnVideoPrevPage: Button
    private lateinit var btnVideoNextPage: Button
    private lateinit var btnDeleteAllVideos: Button
    private lateinit var btnDeleteCurrentPageVideos: MaterialButton
    private lateinit var btnBatchDelete: MaterialButton
    private lateinit var layoutBatchActions: LinearLayout
    private lateinit var btnBatchExit: MaterialButton
    private lateinit var btnBatchToggleAll: MaterialButton
    private lateinit var btnBatchDeleteSelected: MaterialButton
    private lateinit var tvVideoSummary: TextView
    private lateinit var tvVideoPage: TextView
    private lateinit var layoutVideoList: LinearLayout
    private lateinit var tvGpsTrackSummary: TextView
    private lateinit var btnGpsPrevPage: Button
    private lateinit var btnGpsNextPage: Button
    private lateinit var tvGpsPage: TextView
    private lateinit var layoutGpsTrackList: LinearLayout
    private lateinit var toolbar: MaterialToolbar

    private var filterSingleDayMs: Long? = null
    private var filterRangeStartMs: Long? = null
    private var filterRangeEndMs: Long? = null
    private var filterMinDurationMs: Long? = null
    private var filterMaxDurationMs: Long? = null
    private var filterMinDistanceMeters: Float? = null
    private var filterMaxDistanceMeters: Float? = null
    private var filterMinPhotoCount: Int? = null
    private var showFavoriteVideosOnly = false
    private var videoPageIndex = 0
    private var gpsPageIndex = 0
    private var allGpsTrackRecords: List<DriveRecord> = emptyList()
    @Volatile private var renderRequestId: Int = 0
    private var currentVideoItems: List<VideoListItem> = emptyList()
    private var currentFilteredVideoTrips: List<VideoStorage.VideoTrip> = emptyList()
    private var videoScopeCount: Int = 0
    private var videoListMutation: Int = 0
    private var batchDeleteMode = false
    private val batchSelectedTripIds = mutableSetOf<String>()
    private var videoDateSpans: List<CountSpan> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        btnVideoFilterDialog = findViewById(R.id.btnVideoFilterDialog)
        btnVideoFilterClear = findViewById(R.id.btnVideoFilterClear)
        btnVideoDate = findViewById(R.id.btnVideoDate)
        btnVideoDateClear = findViewById(R.id.btnVideoDateClear)
        btnVideoScopeAll = findViewById(R.id.btnVideoScopeAll)
        btnVideoScopeFavorites = findViewById(R.id.btnVideoScopeFavorites)
        btnVideoPrevPage = findViewById(R.id.btnVideoPrevPage)
        btnVideoNextPage = findViewById(R.id.btnVideoNextPage)
        btnDeleteAllVideos = findViewById(R.id.btnDeleteAllVideos)
        btnDeleteCurrentPageVideos = findViewById(R.id.btnDeleteCurrentPage)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)
        layoutBatchActions = findViewById(R.id.layoutBatchActions)
        btnBatchExit = findViewById(R.id.btnBatchExit)
        btnBatchToggleAll = findViewById(R.id.btnBatchToggleAll)
        btnBatchDeleteSelected = findViewById(R.id.btnBatchDeleteSelected)
        tvVideoSummary = findViewById(R.id.tvVideoSummary)
        tvVideoPage = findViewById(R.id.tvVideoPage)
        layoutVideoList = findViewById(R.id.layoutVideoList)
        tvGpsTrackSummary = findViewById(R.id.tvGpsTrackSummary)
        btnGpsPrevPage = findViewById(R.id.btnGpsPrevPage)
        btnGpsNextPage = findViewById(R.id.btnGpsNextPage)
        tvGpsPage = findViewById(R.id.tvGpsPage)
        layoutGpsTrackList = findViewById(R.id.layoutGpsTrackList)
        toolbar = findViewById(R.id.toolbar)

        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        filterSingleDayMs = prefs.getLong(PREF_VIDEO_FILTER_DAY_MS, 0L).takeIf { it > 0L }
        filterRangeStartMs = prefs.getLong(PREF_VIDEO_FILTER_RANGE_START_MS, 0L).takeIf { it > 0L }
        filterRangeEndMs = prefs.getLong(PREF_VIDEO_FILTER_RANGE_END_MS, 0L).takeIf { it > 0L }
        filterMinDurationMs = prefs.getLong(PREF_VIDEO_FILTER_MIN_DURATION_MS, 0L).takeIf { it > 0L }
        filterMaxDurationMs = prefs.getLong(PREF_VIDEO_FILTER_MAX_DURATION_MS, 0L).takeIf { it > 0L }
        filterMinDistanceMeters = prefs.getFloat(PREF_VIDEO_FILTER_MIN_DISTANCE_METERS, -1f).takeIf { it >= 0f }
        filterMaxDistanceMeters = prefs.getFloat(PREF_VIDEO_FILTER_MAX_DISTANCE_METERS, -1f).takeIf { it >= 0f }
        filterMinPhotoCount = prefs.getInt(PREF_VIDEO_FILTER_MIN_PHOTO_COUNT, -1).takeIf { it >= 0 }
        if (filterSingleDayMs != null && (filterRangeStartMs != null || filterRangeEndMs != null)) {
            filterSingleDayMs = null
        }

        setupTimeFilterUi()
        setupFavoriteFilterUi()
        setupPagingUi()
        btnDeleteAllVideos.setOnClickListener { confirmDeleteAllVideos() }
        btnDeleteCurrentPageVideos.setOnClickListener { confirmDeleteCurrentPageVideos() }
        setupBatchDeleteUi()
        render()
    }

    override fun onDestroy() {
        historyExecutor.shutdownNow()
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupTimeFilterUi() {
        updateTimeFilterButtons()
        btnVideoDate.setOnClickListener { showVideoCalendar() }
        btnVideoDateClear.setOnClickListener {
            filterSingleDayMs = null
            filterRangeStartMs = null
            filterRangeEndMs = null
            videoPageIndex = 0
            persistTimeFilterPref()
            updateTimeFilterButtons()
            render()
        }
        btnVideoFilterDialog.setOnClickListener { showVideoFilterDialog() }
        btnVideoFilterClear.setOnClickListener {
            clearVideoFilter()
            videoPageIndex = 0
            persistTimeFilterPref()
            updateTimeFilterButtons()
            render()
        }
    }

    private fun showVideoCalendar() {
        MonthCalendarDialog.show(
            activity = this,
            spans = videoDateSpans,
            selection = currentDaySelection()
        ) { selection ->
            applyDaySelection(selection)
        }
    }

    private fun currentDaySelection(): DaySelection? {
        val rangeStart = filterRangeStartMs
        val rangeEnd = filterRangeEndMs
        if (rangeStart != null || rangeEnd != null) {
            val from = PhotoCalendar.startOfDay((rangeStart ?: rangeEnd)!!, TimeZone.getDefault())
            val to = PhotoCalendar.startOfDay((rangeEnd ?: rangeStart)!!, TimeZone.getDefault())
            return DaySelection(minOf(from, to), maxOf(from, to))
        }
        val day = filterSingleDayMs ?: return null
        val start = PhotoCalendar.startOfDay(day, TimeZone.getDefault())
        return DaySelection(start, start)
    }

    private fun applyDaySelection(selection: DaySelection) {
        val zone = TimeZone.getDefault()
        if (selection.singleDay) {
            filterSingleDayMs = PhotoCalendar.startOfDay(selection.startMs, zone)
            filterRangeStartMs = null
            filterRangeEndMs = null
        } else {
            filterSingleDayMs = null
            filterRangeStartMs = PhotoCalendar.startOfDay(selection.startMs, zone)
            filterRangeEndMs = PhotoCalendar.endOfDay(selection.endMs, zone)
        }
        videoPageIndex = 0
        persistTimeFilterPref()
        updateTimeFilterButtons()
        render()
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
        btnGpsPrevPage.setOnClickListener {
            if (gpsPageIndex > 0) {
                gpsPageIndex -= 1
                showGpsTrackPage()
            }
        }
        btnGpsNextPage.setOnClickListener {
            val pages = pageCount(allGpsTrackRecords.size)
            if (gpsPageIndex + 1 < pages) {
                gpsPageIndex += 1
                showGpsTrackPage()
            }
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
        val content = layoutInflater.cloneInContext(
            android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_CarSpeed_Dialog)
        ).inflate(R.layout.dialog_video_filter, null)
        val cbDuration = content.findViewById<CheckBox>(R.id.cbFilterDuration)
        val cbDistance = content.findViewById<CheckBox>(R.id.cbFilterDistance)
        val cbPhotos = content.findViewById<CheckBox>(R.id.cbFilterPhotos)
        val minDuration = content.findViewById<TextInputLayout>(R.id.tilFilterMinDuration)
        val maxDuration = content.findViewById<TextInputLayout>(R.id.tilFilterMaxDuration)
        val minDistance = content.findViewById<TextInputLayout>(R.id.tilFilterMinDistance)
        val maxDistance = content.findViewById<TextInputLayout>(R.id.tilFilterMaxDistance)
        val photos = content.findViewById<TextInputLayout>(R.id.tilFilterPhotos)
        val etMinDuration = content.findViewById<TextView>(R.id.etFilterMinDuration)
        val etMaxDuration = content.findViewById<TextView>(R.id.etFilterMaxDuration)
        val etMinDistance = content.findViewById<TextView>(R.id.etFilterMinDistance)
        val etMaxDistance = content.findViewById<TextView>(R.id.etFilterMaxDistance)
        val etPhotos = content.findViewById<TextView>(R.id.etFilterPhotos)
        val error = content.findViewById<TextView>(R.id.tvFilterError)
        cbDuration.isChecked = filterMinDurationMs != null || filterMaxDurationMs != null
        cbDistance.isChecked = filterMinDistanceMeters != null || filterMaxDistanceMeters != null
        cbPhotos.isChecked = filterMinPhotoCount != null
        etMinDuration.text = filterMinDurationMs?.let { (it / 1000L).toString() }.orEmpty()
        etMaxDuration.text = filterMaxDurationMs?.let { (it / 1000L).toString() }.orEmpty()
        etMinDistance.text = filterMinDistanceMeters?.let { trimNumber(it / 1000f) }.orEmpty()
        etMaxDistance.text = filterMaxDistanceMeters?.let { trimNumber(it / 1000f) }.orEmpty()
        etPhotos.text = filterMinPhotoCount?.toString().orEmpty()
        fun syncFields() {
            minDuration.isEnabled = cbDuration.isChecked
            maxDuration.isEnabled = cbDuration.isChecked
            minDistance.isEnabled = cbDistance.isChecked
            maxDistance.isEnabled = cbDistance.isChecked
            photos.isEnabled = cbPhotos.isChecked
            error.visibility = View.GONE
        }
        cbDuration.setOnCheckedChangeListener { _, _ -> syncFields() }
        cbDistance.setOnCheckedChangeListener { _, _ -> syncFields() }
        cbPhotos.setOnCheckedChangeListener { _, _ -> syncFields() }
        syncFields()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.video_filter_dialog_title)
            .setView(content)
            .setPositiveButton(R.string.video_filter_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nextMinDurationMs = if (cbDuration.isChecked) etMinDuration.text.toString().toLongOrNull()?.times(1000L) else null
                val nextMaxDurationMs = if (cbDuration.isChecked) etMaxDuration.text.toString().toLongOrNull()?.times(1000L) else null
                val nextMinDistanceMeters = if (cbDistance.isChecked) etMinDistance.text.toString().toFloatOrNull()?.times(1000f) else null
                val nextMaxDistanceMeters = if (cbDistance.isChecked) etMaxDistance.text.toString().toFloatOrNull()?.times(1000f) else null
                val nextMinPhotoCount = if (cbPhotos.isChecked) etPhotos.text.toString().toIntOrNull() ?: 0 else null
                if (invalidRange(nextMinDurationMs, nextMaxDurationMs) ||
                    invalidRange(nextMinDistanceMeters, nextMaxDistanceMeters) ||
                    (nextMinPhotoCount != null && nextMinPhotoCount < 0)
                ) {
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                filterMinDurationMs = nextMinDurationMs
                filterMaxDurationMs = nextMaxDurationMs
                filterMinDistanceMeters = nextMinDistanceMeters
                filterMaxDistanceMeters = nextMaxDistanceMeters
                filterMinPhotoCount = nextMinPhotoCount
                videoPageIndex = 0
                persistTimeFilterPref()
                updateTimeFilterButtons()
                render()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateTimeFilterButtons() {
        val dateOnly = currentDaySelection() != null
        val extra = hasExtraVideoFilter()
        btnVideoDate.text = currentDaySelection()?.let { PhotoCalendar.formatSelection(it, TimeZone.getDefault()) }
            ?: getString(R.string.photo_filter_all)
        btnVideoDate.contentDescription = btnVideoDate.text
        btnVideoDateClear.isEnabled = dateOnly
        btnVideoFilterDialog.text = if (extra) {
            getString(R.string.video_filter_summary, videoFilterSummary())
        } else {
            getString(R.string.video_filter_none)
        }
        btnVideoFilterDialog.contentDescription = btnVideoFilterDialog.text
        btnVideoFilterClear.isEnabled = extra || dateOnly
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
            .putInt(PREF_VIDEO_FILTER_MIN_PHOTO_COUNT, filterMinPhotoCount ?: -1)
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
        filterMinPhotoCount = null
    }

    private fun videoFilterSummary(): String {
        return listOfNotNull(
            rangeText(filterMinDurationMs, filterMaxDurationMs, "时长") { formatDuration(it) },
            rangeText(filterMinDistanceMeters, filterMaxDistanceMeters, "里程") { trimNumber(it / 1000f) + "km" },
            filterMinPhotoCount?.let { "抓拍>=${it}张" }
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

    private fun tripListTitle(trip: VideoStorage.VideoTrip, startTimeMs: Long = 0L, durationMs: Long = 0L): String {
        val start = startTimeMs.takeIf { it > 0L }
            ?: VideoStorage.tripFolderStartTimeMs(trip.directory.name)
            ?: VideoStorage.tripFolderStartTimeMs(trip.title)
            ?: 0L
        val duration = durationMs.takeIf { it > 0L }
            ?: trip.segments.sumOf { VideoStorage.recordingDurationMs(it) }
        return VideoStorage.tripListTitle(start, duration) ?: trip.title.removePrefix("carspeed-trip-")
    }

    private fun confirmDeleteVideo(trip: VideoStorage.VideoTrip) {
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_one)
            .setMessage(tripListTitle(trip))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = VideoStorage.deleteVideoTrip(this, trip)
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                dropDeletedTrips(setOf(trip.id))
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
                dropDeletedTrips(trips.map { it.id }.toSet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteCurrentPageVideos() {
        val trips = currentVideoItems.map { it.trip }
        if (trips.isEmpty()) {
            Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_current_page)
            .setMessage(getString(R.string.video_delete_all_confirm, trips.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = trips.sumOf { trip -> VideoStorage.deleteVideoTrip(this, trip) }
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                dropDeletedTrips(trips.map { it.id }.toSet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupBatchDeleteUi() {
        btnBatchDelete.setOnClickListener {
            if (currentVideoItems.isEmpty()) {
                Toast.makeText(this, R.string.video_replay_no_video, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            batchDeleteMode = !batchDeleteMode
            batchSelectedTripIds.clear()
            updateBatchModeViews()
            render()
        }
        btnBatchExit.setOnClickListener {
            batchDeleteMode = false
            batchSelectedTripIds.clear()
            updateBatchModeViews()
            render()
        }
        btnBatchToggleAll.setOnClickListener {
            val pageIds = currentVideoItems.map { it.trip.id }
            if (batchSelectedTripIds.containsAll(pageIds)) {
                batchSelectedTripIds.removeAll(pageIds.toSet())
            } else {
                batchSelectedTripIds.addAll(pageIds)
            }
            refreshBatchSelectionViews()
        }
        btnBatchDeleteSelected.setOnClickListener { confirmDeleteSelectedVideos() }
    }

    private fun updateBatchModeViews() {
        layoutBatchActions.visibility = if (batchDeleteMode) View.VISIBLE else View.GONE
        btnBatchDelete.text = getString(if (batchDeleteMode) R.string.video_batch_exit else R.string.video_batch_delete)
        btnDeleteAllVideos.visibility = if (batchDeleteMode) View.GONE else View.VISIBLE
        btnDeleteCurrentPageVideos.visibility = if (batchDeleteMode) View.GONE else View.VISIBLE
    }

    private fun refreshBatchSelectionViews() {
        val pageIds = currentVideoItems.map { it.trip.id }
        val allSelected = pageIds.isNotEmpty() && batchSelectedTripIds.containsAll(pageIds)
        btnBatchToggleAll.text = getString(
            if (allSelected) R.string.video_batch_clear_all else R.string.video_batch_select_all
        )
        btnBatchDeleteSelected.text = if (batchSelectedTripIds.isEmpty()) {
            getString(R.string.video_batch_delete_selected)
        } else {
            getString(R.string.video_batch_delete_selected) + "（" + batchSelectedTripIds.size + "）"
        }
        updateBatchCheckBoxes(layoutVideoList)
    }

    private fun updateBatchCheckBoxes(viewGroup: android.view.ViewGroup) {
        for (index in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(index)
            if (child is CheckBox) {
                val tripId = (child.tag as? String)?.removePrefix(BATCH_CHECK_TAG_PREFIX)
                if (tripId != null) {
                    child.isChecked = tripId in batchSelectedTripIds
                }
            } else if (child is android.view.ViewGroup) {
                updateBatchCheckBoxes(child)
            }
        }
    }

    private fun toggleBatchSelection(item: VideoListItem) {
        if (item.trip.id in batchSelectedTripIds) {
            batchSelectedTripIds.remove(item.trip.id)
        } else {
            batchSelectedTripIds.add(item.trip.id)
        }
        refreshBatchSelectionViews()
    }

    private fun confirmDeleteSelectedVideos() {
        if (batchSelectedTripIds.isEmpty()) {
            Toast.makeText(this, R.string.video_batch_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val selected = currentVideoItems.filter { it.trip.id in batchSelectedTripIds }.map { it.trip }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.video_batch_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_selected)
            .setMessage(getString(R.string.video_delete_selected_confirm, selected.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = selected.sumOf { trip -> VideoStorage.deleteVideoTrip(this, trip) }
                Toast.makeText(this, getString(R.string.video_delete_done, deleted), Toast.LENGTH_SHORT).show()
                dropDeletedTrips(selected.map { it.id }.toSet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render() {
        val requestId = ++renderRequestId
        val filter = currentVideoFilter()
        val favoriteOnly = showFavoriteVideosOnly
        showLoadingState()
        val appContext = applicationContext
        historyExecutor.execute {
            val metadataCache = VideoHistoryCache.load(appContext)
            val loadedTrips = VideoStorage.videoTrips(appContext)
            val favoriteIds = VideoStorage.favoriteTripIds(appContext, loadedTrips)
            val allTrips = loadedTrips.map { trip ->
                TripSortKey(
                    trip = trip,
                    favorite = trip.id in favoriteIds,
                    lastModified = trip.segments.maxOfOrNull { it.lastModified() } ?: 0L
                )
            }.sortedWith(
                compareByDescending<TripSortKey> { it.favorite }.thenByDescending { it.lastModified }
            ).map { it.trip }
            metadataCache.prune(allTrips.map { it.id }.toSet())
            val scopedTrips = if (favoriteOnly) {
                allTrips.filter { trip -> trip.id in favoriteIds }
            } else {
                allTrips
            }
            val photoTimes = VideoStorage.photoFiles(appContext).mapNotNull { file ->
                RoutePhotoMarks.timeFromFileName(file.name)
            }
            routePhotoTimesMs = photoTimes
            val needsDetailedFilter = filter.requiresDetailedVideoData()
            val allVideoItems = if (needsDetailedFilter) {
                scopedTrips.map { trip -> buildVideoListItem(trip, metadataCache, trip.id in favoriteIds) }
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
                pageTrips.map { trip -> buildVideoListItem(trip, metadataCache, trip.id in favoriteIds) }
            }
            val dateSpans = if (needsDetailedFilter) {
                allVideoItems.map { item ->
                    CountSpan(item.startTimeMs, item.endTimeMs.coerceAtLeast(item.startTimeMs))
                }
            } else {
                scopedTrips.map { trip ->
                    val start = tripStartTimeMs(trip, track = null)
                    CountSpan(start, tripEndTimeMs(trip, track = null, startTimeMs = start).coerceAtLeast(start))
                }
            }
            metadataCache.save()
            if (requestId != renderRequestId) return@execute
            val totalBytes = videoItems.sumOf { it.totalBytes }
            mainHandler.post {
                if (requestId != renderRequestId || isFinishing || isDestroyed) return@post
                videoPageIndex = pageIndex
                videoDateSpans = dateSpans
                routePhotoTimesMs = photoTimes
                currentFilteredVideoTrips = filteredTrips
                renderVideoList(
                    videoItems,
                    totalBytes,
                    filteredTrips.size,
                    scopedTrips.size,
                    pageIndex,
                    favoriteOnly
                )
            }
            val storedRecords = DriveRecordStore.loadRecords(appContext)
            val gpsTrackRecords = storedRecords.filter { record ->
                record.source == DriveRecordStore.SOURCE_GPS_TRACK && record.routePoints.isNotEmpty()
            }.sortedByDescending { it.startTimeMs }
            if (requestId != renderRequestId) return@execute
            mainHandler.post {
                if (requestId != renderRequestId || isFinishing || isDestroyed) return@post
                renderGpsTrackList(gpsTrackRecords)
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
        tvGpsPage.text = ""
        btnGpsPrevPage.isEnabled = false
        btnGpsNextPage.isEnabled = false
        layoutGpsTrackList.removeAllViews()
        layoutGpsTrackList.addView(TextView(this).apply {
            text = "正在加载 GPS 轨迹记录…"
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
        })
    }

    private fun buildVideoListItem(
        trip: VideoStorage.VideoTrip,
        metadataCache: VideoHistoryCache.Store,
        favorite: Boolean
    ): VideoListItem {
        val signature = VideoHistoryCache.signatureFor(trip)
        metadataCache.read(trip.id, signature)?.let { cached ->
            return cached.toVideoListItem(trip, favorite)
        }
        val item = buildVideoListItemUncached(trip, favorite)
        metadataCache.write(item.toCacheEntry(trip.id, signature))
        return item
    }

    private fun buildVideoListItemUncached(
        trip: VideoStorage.VideoTrip,
        favorite: Boolean
    ): VideoListItem {
        val track = VideoTrackStorage.readMergedTrack(trip.segments)
        val points = track?.points.orEmpty()
        val trackSummary = points.takeIf { it.isNotEmpty() }?.let { TrackMetrics.summary(it) }
        val startTimeMs = tripStartTimeMs(trip, track)
        val endTimeMs = tripEndTimeMs(trip, track, startTimeMs)
        val durationMs = trip.segments.sumOf { VideoStorage.recordingDurationMs(it) }
            .takeIf { it > 0L }
            ?: trackSummary?.durationMs
            ?: (endTimeMs - startTimeMs).coerceAtLeast(0L)
        return VideoListItem(
            trip = trip,
            favorite = favorite,
            totalBytes = trip.segments.sumOf { it.length() },
            hasTrack = track != null,
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

    private fun VideoHistoryCache.Entry.toVideoListItem(
        trip: VideoStorage.VideoTrip,
        favorite: Boolean
    ): VideoListItem {
        return VideoListItem(
            trip = trip,
            favorite = favorite,
            totalBytes = totalBytes,
            hasTrack = hasTrack,
            hasSubtitle = hasSubtitle,
            track = null,
            trackSummary = trackSummary,
            startPoint = startPoint,
            endPoint = endPoint,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            durationMs = durationMs,
            distanceMeters = distanceMeters
        )
    }

    private fun VideoListItem.toCacheEntry(tripId: String, signature: String): VideoHistoryCache.Entry {
        return VideoHistoryCache.Entry(
            tripId = tripId,
            signature = signature,
            totalBytes = totalBytes,
            hasTrack = hasTrack,
            hasSubtitle = hasSubtitle,
            trackSummary = trackSummary,
            startPoint = startPoint,
            endPoint = endPoint,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            durationMs = durationMs,
            distanceMeters = distanceMeters
        )
    }

    private fun dropDeletedTrips(tripIds: Set<String>) {
        if (tripIds.isEmpty()) return
        videoListMutation++
        val mutation = videoListMutation
        currentFilteredVideoTrips = currentFilteredVideoTrips.filter { it.id !in tripIds }
        currentVideoItems = currentVideoItems.filter { it.trip.id !in tripIds }
        videoScopeCount = (videoScopeCount - tripIds.size).coerceAtLeast(currentFilteredVideoTrips.size)
        batchSelectedTripIds.removeAll(tripIds)
        animateRemoveVideoCards(tripIds)
        val pages = pageCount(currentFilteredVideoTrips.size)
        if (videoPageIndex > pages - 1) videoPageIndex = (pages - 1).coerceAtLeast(0)
        publishVideoPageChrome()
        if (currentFilteredVideoTrips.isEmpty()) {
            showVideoListEmpty()
            return
        }
        fillVideoPageGap(mutation)
    }

    private fun animateRemoveVideoCards(tripIds: Set<String>) {
        layoutVideoList.layoutTransition = videoListTransition()
        val doomed = (0 until layoutVideoList.childCount)
            .map { layoutVideoList.getChildAt(it) }
            .filter { (it.tag as? String) in tripIds }
        doomed.forEach { layoutVideoList.removeView(it) }
    }

    private fun fillVideoPageGap(mutation: Int) {
        val pageTrips = currentFilteredVideoTrips
            .drop(videoPageIndex * VIDEO_PAGE_SIZE)
            .take(VIDEO_PAGE_SIZE)
        val shownIds = currentVideoItems.map { it.trip.id }.toSet()
        val missing = pageTrips.filter { it.id !in shownIds }
        if (missing.isEmpty()) {
            if (currentVideoItems.isEmpty()) showVideoListEmpty()
            return
        }
        val appContext = applicationContext
        historyExecutor.execute {
            val cache = VideoHistoryCache.load(appContext)
            val favoriteIds = VideoStorage.favoriteTripIds(appContext, missing)
            val items = missing.map { trip ->
                buildVideoListItem(trip, cache, trip.id in favoriteIds)
            }
            cache.save()
            mainHandler.post {
                if (mutation != videoListMutation || isFinishing || isDestroyed) return@post
                if (layoutVideoList.childCount == 1 && layoutVideoList.getChildAt(0).tag !is String) {
                    layoutVideoList.layoutTransition = null
                    layoutVideoList.removeAllViews()
                }
                layoutVideoList.layoutTransition = videoListTransition()
                items.forEach { item ->
                    if (currentVideoItems.none { it.trip.id == item.trip.id }) {
                        currentVideoItems = currentVideoItems + item
                        layoutVideoList.addView(videoCard(item))
                    }
                }
                publishVideoPageChrome()
            }
        }
    }

    private fun showVideoListEmpty() {
        layoutVideoList.layoutTransition = videoListTransition()
        layoutVideoList.removeAllViews()
        layoutVideoList.addView(TextView(this).apply {
            text = getString(videoEmptyTextRes(showFavoriteVideosOnly))
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 13f
        })
    }

    private fun videoListTransition(): LayoutTransition {
        return LayoutTransition().apply {
            setAnimateParentHierarchy(false)
            setDuration(220)
        }
    }

    private fun publishVideoPageChrome() {
        bindVideoPageChrome(
            items = currentVideoItems,
            filteredCount = currentFilteredVideoTrips.size,
            allCount = videoScopeCount,
            pageIndex = videoPageIndex,
            favoriteOnly = showFavoriteVideosOnly
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
        videoListMutation++
        currentVideoItems = items
        videoScopeCount = allCount
        bindVideoPageChrome(items, filteredCount, allCount, pageIndex, favoriteOnly, pageBytes)
        layoutVideoList.layoutTransition = null
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
    }

    private fun bindVideoPageChrome(
        items: List<VideoListItem>,
        filteredCount: Int,
        allCount: Int,
        pageIndex: Int,
        favoriteOnly: Boolean,
        pageBytes: Long = items.sumOf { it.totalBytes }
    ) {
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
        btnDeleteAllVideos.isEnabled = filteredCount > 0
        btnDeleteCurrentPageVideos.isEnabled = items.isNotEmpty()
        btnBatchDelete.isEnabled = items.isNotEmpty()
        updateBatchModeViews()
        refreshBatchSelectionViews()
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
        allGpsTrackRecords = records
        val pages = pageCount(records.size)
        gpsPageIndex = gpsPageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
        showGpsTrackPage()
    }

    private fun showGpsTrackPage() {
        val total = allGpsTrackRecords.size
        val pages = pageCount(total)
        val pageIndex = gpsPageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
        gpsPageIndex = pageIndex
        val page = if (total == 0) {
            emptyList()
        } else {
            allGpsTrackRecords.drop(pageIndex * VIDEO_PAGE_SIZE).take(VIDEO_PAGE_SIZE)
        }
        if (total == 0) {
            tvGpsTrackSummary.text = getString(R.string.gps_track_history_summary, 0)
        } else {
            val first = pageIndex * VIDEO_PAGE_SIZE + 1
            val last = (pageIndex * VIDEO_PAGE_SIZE + page.size).coerceAtMost(total)
            tvGpsTrackSummary.text = getString(R.string.gps_track_history_page_summary, first, last, total)
        }
        tvGpsPage.text = getString(R.string.page_indicator, if (total == 0) 0 else pageIndex + 1, pages)
        btnGpsPrevPage.isEnabled = pageIndex > 0
        btnGpsNextPage.isEnabled = total > 0 && pageIndex + 1 < pages
        layoutGpsTrackList.removeAllViews()
        if (page.isEmpty()) {
            layoutGpsTrackList.addView(TextView(this).apply {
                text = getString(R.string.gps_track_history_empty)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
            })
            return
        }
        page.forEach { record -> layoutGpsTrackList.addView(gpsTrackCard(record)) }
    }

    private fun gpsTrackCard(record: DriveRecord): View {
        val card = MaterialCardView(this).apply {
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 0
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
        content.addView(TextView(this).apply {
            text = GpsTrackTimeLabel.format(record.startTimeMs, record.endTimeMs)
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
            tag = trip.id
            radius = 16f * density
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(getColorCompat(R.color.surface_primary))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (batchDeleteMode) {
                    toggleBatchSelection(item)
                } else {
                    replayTrip(trip)
                }
            }
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
        if (batchDeleteMode) {
            row.addView(MaterialCheckBox(this).apply {
                tag = BATCH_CHECK_TAG_PREFIX + trip.id
                isChecked = trip.id in batchSelectedTripIds
                isClickable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            })
        }
        val thumbWrap = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8f * density
                setColor(android.graphics.Color.BLACK)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            layoutParams = LinearLayout.LayoutParams(dp(132), dp(82))
        }
        val image = ImageView(this).apply {
            alpha = 0.28f
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(android.R.drawable.ic_menu_slideshow)
            tag = thumbnailKey(trip.firstVideo)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        loadVideoThumbnail(trip.firstVideo, image)
        val play = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
            text = tripListTitle(item.trip, item.startTimeMs, item.durationMs)
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
            text = getString(R.string.video_photo_count, item.photoCount())
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        if (item.favorite) {
            content.addView(TextView(this).apply {
                text = getString(R.string.video_favorite_badge)
                setTextColor(getColorCompat(R.color.brand_teal))
                textSize = 12f
            })
        }
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
        actions.addView(actionText(getString(R.string.video_delete_one), danger = true) { confirmDeleteVideo(trip) })
        content.addView(actions)
        row.addView(content)
        card.addView(row)
        return card
    }

    private fun loadVideoThumbnail(videoFile: File, target: ImageView) {
        val key = thumbnailKey(videoFile)
        target.tag = key
        if (thumbnailCache.containsKey(key)) {
            thumbnailCache[key]?.let { bitmap -> showVideoThumbnail(target, key, bitmap) }
            return
        }
        val width = dp(132)
        val height = dp(82)
        thumbnailExecutor.execute {
            val cached = readCachedThumbnail(videoFile)
            val bitmap = cached
                ?: mediaStoreThumbnail(videoFile, width, height)
                ?: extractVideoThumbnail(videoFile, width, height)
            if (cached == null && bitmap != null) storeCachedThumbnail(videoFile, bitmap)
            mainHandler.post {
                thumbnailCache[key] = bitmap
                bitmap?.let { showVideoThumbnail(target, key, it) }
            }
        }
    }

    private fun mediaStoreThumbnail(videoFile: File, width: Int, height: Int): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val uri = videoContentUri(videoFile) ?: return null
        return try {
            contentResolver.loadThumbnail(uri, android.util.Size(width, height), null)
        } catch (_: Exception) {
            null
        }
    }

    private fun videoContentUri(videoFile: File): android.net.Uri? {
        synchronized(videoUriByPath) {
            if (!videoUrisLoaded) {
                videoUrisLoaded = true
                val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                runCatching {
                    contentResolver.query(
                        collection,
                        arrayOf(
                            android.provider.MediaStore.MediaColumns._ID,
                            android.provider.MediaStore.MediaColumns.DATA
                        ),
                        "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("DCIM/CarSpeed/%"),
                        null
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                        val dataColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns.DATA)
                        while (cursor.moveToNext()) {
                            val path = cursor.getString(dataColumn) ?: continue
                            videoUriByPath[path] = android.content.ContentUris.withAppendedId(
                                collection,
                                cursor.getLong(idColumn)
                            )
                        }
                    }
                }
            }
            return videoUriByPath[videoFile.absolutePath]
        }
    }

    private fun thumbnailKey(videoFile: File): String {
        return "${videoFile.absolutePath}:${videoFile.length()}:${videoFile.lastModified()}"
    }

    private fun thumbnailCacheFile(videoFile: File): File {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(thumbnailKey(videoFile).toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(20)
        return File(cacheDir, "video-thumbs/$digest.jpg")
    }

    private fun readCachedThumbnail(videoFile: File): Bitmap? {
        val file = thumbnailCacheFile(videoFile)
        if (!file.isFile) return null
        return android.graphics.BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun storeCachedThumbnail(videoFile: File, bitmap: Bitmap) {
        val file = thumbnailCacheFile(videoFile)
        file.parentFile?.mkdirs()
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
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
        val distance = item.trackSummary?.totalDistanceMeters?.takeIf { it > 0f }?.let { "GPS ${formatDistance(it)}" }
        val segments = if (item.trip.segments.size > 1) getString(R.string.video_segments_count, item.trip.segments.size) else null
        return listOfNotNull(segments, formatBytes(item.totalBytes), distance).joinToString("  ·  ")
    }

    private fun VideoListItem.photoCount(): Int {
        val start = startPoint?.timeMs?.takeIf { it > 0L } ?: startTimeMs
        val end = endPoint?.timeMs?.takeIf { it > 0L } ?: endTimeMs
        return RoutePhotoMarks.countBetween(routePhotoTimesMs, start, end)
    }

    private fun hasVideoFilter(): Boolean = currentVideoFilter().hasAny()

    private fun hasExtraVideoFilter(): Boolean {
        return filterMinDurationMs != null || filterMaxDurationMs != null ||
            filterMinDistanceMeters != null || filterMaxDistanceMeters != null ||
            filterMinPhotoCount != null
    }

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
            maxDistanceMeters = filterMaxDistanceMeters,
            minPhotoCount = filterMinPhotoCount
        )
    }

    private fun VideoFilter.requiresDetailedVideoData(): Boolean {
        return minDurationMs != null || maxDurationMs != null ||
            minDistanceMeters != null || maxDistanceMeters != null ||
            (minPhotoCount != null && minPhotoCount > 0)
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
        if (filter.minPhotoCount != null && photoCount() < filter.minPhotoCount) return false
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

    private fun parseVideoTimeMs(name: String): Long? {
        val match = VIDEO_NAME_REGEX.find(name) ?: return null
        return parseCompactDateTime(match.groupValues[1], match.groupValues[2])
    }

    private fun parseTripTimeMs(name: String): Long? {
        return VideoStorage.tripFolderStartTimeMs(name)
    }

    private fun parseCompactDateTime(date: String, time: String): Long? {
        return try {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse("$date-$time")?.time
        } catch (_: Exception) {
            null
        }
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

    private fun actionText(text: String, danger: Boolean = false, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(if (danger) R.color.text_danger else R.color.brand_blue))
            textSize = 12f
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun showRenameDialog(video: File) {
        val content = layoutInflater.cloneInContext(
            android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_CarSpeed_Dialog)
        ).inflate(R.layout.dialog_text_field, null)
        val field = content.findViewById<TextInputLayout>(R.id.tilDialogInput)
        val input = content.findViewById<TextInputEditText>(R.id.etDialogInput)
        field.hint = getString(R.string.video_rename_hint)
        input.setText(video.nameWithoutExtension)
        input.selectAll()
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        AlertDialog.Builder(this)
            .setTitle(R.string.video_rename_title)
            .setView(content)
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

    private fun formatDistance(meters: Float): String {
        return TrackMetrics.formatDistance(meters)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private data class TripSortKey(
        val trip: VideoStorage.VideoTrip,
        val favorite: Boolean,
        val lastModified: Long
    )

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
        val maxDistanceMeters: Float?,
        val minPhotoCount: Int?
    ) {
        fun hasAny(): Boolean {
            return dayStartMs != null || dayEndMs != null ||
                rangeStartMs != null || rangeEndMs != null ||
                minDurationMs != null || maxDurationMs != null ||
                minDistanceMeters != null || maxDistanceMeters != null ||
                minPhotoCount != null
        }
    }

    companion object {
        private const val PREF_VIDEO_FILTER_DAY_MS = "history_video_filter_day_ms"
        private const val PREF_VIDEO_FILTER_RANGE_START_MS = "history_video_filter_range_start_ms"
        private const val PREF_VIDEO_FILTER_RANGE_END_MS = "history_video_filter_range_end_ms"
        private const val PREF_VIDEO_FILTER_MIN_DURATION_MS = "history_video_filter_min_duration_ms"
        private const val PREF_VIDEO_FILTER_MAX_DURATION_MS = "history_video_filter_max_duration_ms"
        private const val PREF_VIDEO_FILTER_MIN_DISTANCE_METERS = "history_video_filter_min_distance_meters"
        private const val PREF_VIDEO_FILTER_MAX_DISTANCE_METERS = "history_video_filter_max_distance_meters"
        private const val PREF_VIDEO_FILTER_MIN_PHOTO_COUNT = "history_video_filter_min_photo_count"
        private const val VIDEO_PAGE_SIZE = 5
        private const val BATCH_CHECK_TAG_PREFIX = "batch_check_"
        private val VIDEO_NAME_REGEX = Regex("carspeed-(\\d{8})-(\\d{6})")
    }
}

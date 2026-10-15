package com.thomas.carspeed

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class PhotoGalleryActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val zone = TimeZone.getDefault()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnPhotoDate: MaterialButton
    private lateinit var btnPhotoDateClear: MaterialButton
    private lateinit var tvPhotoSummary: TextView
    private lateinit var btnPhotoPrevPage: MaterialButton
    private lateinit var btnPhotoNextPage: MaterialButton
    private lateinit var tvPhotoPage: TextView
    private lateinit var layoutPhotoBatchActions: LinearLayout
    private lateinit var btnPhotoBatchExit: MaterialButton
    private lateinit var btnPhotoBatchToggleAll: MaterialButton
    private lateinit var btnPhotoBatchDeleteSelected: MaterialButton
    private lateinit var layoutPhotoList: LinearLayout
    private lateinit var btnPhotoBatch: MaterialButton
    private lateinit var btnPhotoDeletePage: MaterialButton
    private lateinit var btnPhotoDeleteAll: MaterialButton

    private var photos: List<GalleryPhoto> = emptyList()
    private var pageIndex = 0
    private var daySelection: DaySelection? = null
    private var batchMode = false
    private val selectedPaths = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_gallery)
        toolbar = findViewById(R.id.toolbar)
        btnPhotoDate = findViewById(R.id.btnPhotoDate)
        btnPhotoDateClear = findViewById(R.id.btnPhotoDateClear)
        tvPhotoSummary = findViewById(R.id.tvPhotoSummary)
        btnPhotoPrevPage = findViewById(R.id.btnPhotoPrevPage)
        btnPhotoNextPage = findViewById(R.id.btnPhotoNextPage)
        tvPhotoPage = findViewById(R.id.tvPhotoPage)
        layoutPhotoBatchActions = findViewById(R.id.layoutPhotoBatchActions)
        btnPhotoBatchExit = findViewById(R.id.btnPhotoBatchExit)
        btnPhotoBatchToggleAll = findViewById(R.id.btnPhotoBatchToggleAll)
        btnPhotoBatchDeleteSelected = findViewById(R.id.btnPhotoBatchDeleteSelected)
        layoutPhotoList = findViewById(R.id.layoutPhotoList)
        btnPhotoBatch = findViewById(R.id.btnPhotoBatch)
        btnPhotoDeletePage = findViewById(R.id.btnPhotoDeletePage)
        btnPhotoDeleteAll = findViewById(R.id.btnPhotoDeleteAll)

        toolbar.setNavigationOnClickListener { finish() }
        btnPhotoDate.setOnClickListener { showCalendar() }
        btnPhotoDateClear.setOnClickListener {
            daySelection = null
            pageIndex = 0
            selectedPaths.clear()
            render()
        }
        btnPhotoPrevPage.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex -= 1
                render()
            }
        }
        btnPhotoNextPage.setOnClickListener {
            if (pageIndex + 1 < PhotoCalendar.pageCount(filtered().size)) {
                pageIndex += 1
                render()
            }
        }
        btnPhotoBatch.setOnClickListener {
            if (filtered().isEmpty()) {
                Toast.makeText(this, R.string.photo_history_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            batchMode = !batchMode
            selectedPaths.clear()
            render()
        }
        btnPhotoBatchExit.setOnClickListener {
            batchMode = false
            selectedPaths.clear()
            render()
        }
        btnPhotoBatchToggleAll.setOnClickListener { togglePageSelection() }
        btnPhotoBatchDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        btnPhotoDeletePage.setOnClickListener { confirmDeletePage() }
        btnPhotoDeleteAll.setOnClickListener { confirmDeleteAll() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val alive = VideoStorage.photoFiles(this).map { file ->
            GalleryPhoto(file, PhotoCalendar.captureTimeMs(file.name, file.lastModified()))
        }.sortedByDescending { it.timeMs }
        photos = alive
        selectedPaths.retainAll(alive.map { it.file.absolutePath }.toSet())
        render()
    }

    private fun showCalendar() {
        MonthCalendarDialog.show(
            activity = this,
            spans = photos.map { CountSpan(it.timeMs, it.timeMs) },
            selection = daySelection
        ) { selection ->
            if (selection == daySelection) return@show
            daySelection = selection
            pageIndex = 0
            selectedPaths.clear()
            render()
        }
    }

    private fun filtered(): List<GalleryPhoto> {
        val selection = daySelection ?: return photos
        return photos.filter { PhotoCalendar.contains(selection, it.timeMs, zone) }
    }

    private fun render() {
        val all = photos
        val visible = filtered()
        val pages = PhotoCalendar.pageCount(visible.size)
        pageIndex = pageIndex.coerceIn(0, pages - 1)
        val page = PhotoCalendar.pageOf(visible, pageIndex)
        if (visible.isEmpty()) {
            batchMode = false
            selectedPaths.clear()
        }
        val pageBytes = page.sumOf { it.file.length() }
        val first = if (visible.isEmpty()) 0 else pageIndex * PhotoCalendar.PAGE_SIZE + 1
        val last = (pageIndex * PhotoCalendar.PAGE_SIZE + page.size).coerceAtMost(visible.size)
        tvPhotoSummary.text = if (daySelection == null) {
            getString(R.string.photo_history_page_summary, first, last, all.size, formatBytes(pageBytes))
        } else {
            getString(
                R.string.photo_history_page_filtered_summary,
                first,
                last,
                visible.size,
                all.size,
                formatBytes(pageBytes)
            )
        }
        tvPhotoPage.text = getString(
            R.string.page_indicator,
            if (visible.isEmpty()) 0 else pageIndex + 1,
            pages
        )
        btnPhotoPrevPage.isEnabled = pageIndex > 0
        btnPhotoNextPage.isEnabled = visible.isNotEmpty() && pageIndex + 1 < pages
        btnPhotoDate.text = daySelection?.let { PhotoCalendar.formatSelection(it, zone) }
            ?: getString(R.string.photo_filter_all)
        btnPhotoDate.contentDescription = btnPhotoDate.text
        btnPhotoDateClear.isEnabled = daySelection != null
        btnPhotoDeletePage.isEnabled = page.isNotEmpty()
        btnPhotoDeleteAll.isEnabled = visible.isNotEmpty()
        btnPhotoBatch.isEnabled = page.isNotEmpty()
        layoutPhotoBatchActions.visibility = if (batchMode) View.VISIBLE else View.GONE
        btnPhotoBatch.text = getString(if (batchMode) R.string.video_batch_exit else R.string.video_batch_delete)
        btnPhotoDeletePage.visibility = if (batchMode) View.GONE else View.VISIBLE
        btnPhotoDeleteAll.visibility = if (batchMode) View.GONE else View.VISIBLE
        val pagePaths = page.map { it.file.absolutePath }
        val allPageSelected = pagePaths.isNotEmpty() && selectedPaths.containsAll(pagePaths)
        btnPhotoBatchToggleAll.text = getString(
            if (allPageSelected) R.string.video_batch_clear_all else R.string.video_batch_select_all
        )
        val selectedCount = visible.count { it.file.absolutePath in selectedPaths }
        btnPhotoBatchDeleteSelected.text = if (selectedCount == 0) {
            getString(R.string.video_batch_delete_selected)
        } else {
            getString(R.string.video_batch_delete_selected) + "（" + selectedCount + "）"
        }

        layoutPhotoList.removeAllViews()
        if (page.isEmpty()) {
            layoutPhotoList.addView(TextView(this).apply {
                text = getString(if (daySelection == null) R.string.photo_history_empty else R.string.photo_filter_empty)
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            })
            return
        }
        page.forEach { photo -> layoutPhotoList.addView(photoRow(photo)) }
    }

    private fun photoRow(photo: GalleryPhoto): MaterialCardView {
        val path = photo.file.absolutePath
        val card = MaterialCardView(this).apply {
            radius = 16f * resources.displayMetrics.density
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(getColor(R.color.surface_primary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                if (batchMode) toggleSelection(path) else openPhoto(photo.file)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        if (batchMode) {
            row.addView(MaterialCheckBox(this).apply {
                isChecked = path in selectedPaths
                isClickable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            })
        }
        val thumb = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(android.graphics.Color.BLACK)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        thumb.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(loadScaledBitmap(photo.file, dp(96)))
        }, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        row.addView(thumb, LinearLayout.LayoutParams(dp(96), dp(96)))
        row.addView(TextView(this).apply {
            text = fmt.format(Date(photo.timeMs))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(dp(12), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!batchMode) {
            row.addView(TextView(this).apply {
                text = getString(R.string.video_delete_one)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(getColor(R.color.text_danger))
                setPadding(dp(8), dp(8), dp(4), dp(8))
                setOnClickListener { confirmDelete(photo) }
            })
        }
        card.addView(row)
        return card
    }

    private fun toggleSelection(path: String) {
        if (!selectedPaths.add(path)) selectedPaths.remove(path)
        render()
    }

    private fun togglePageSelection() {
        val pagePaths = PhotoCalendar.pageOf(filtered(), pageIndex).map { it.file.absolutePath }
        if (selectedPaths.containsAll(pagePaths)) {
            selectedPaths.removeAll(pagePaths.toSet())
        } else {
            selectedPaths.addAll(pagePaths)
        }
        render()
    }

    private fun confirmDelete(photo: GalleryPhoto) {
        AlertDialog.Builder(this)
            .setTitle(R.string.photo_delete_one)
            .setMessage(fmt.format(Date(photo.timeMs)))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                deletePhotos(listOf(photo))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeletePage() {
        val page = PhotoCalendar.pageOf(filtered(), pageIndex)
        if (page.isEmpty()) {
            Toast.makeText(this, R.string.photo_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_current_page)
            .setMessage(getString(R.string.photo_delete_page_confirm, page.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ -> deletePhotos(page) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAll() {
        val visible = filtered()
        if (visible.isEmpty()) {
            Toast.makeText(this, R.string.photo_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.photo_delete_all)
            .setMessage(getString(R.string.photo_delete_all_confirm, visible.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ -> deletePhotos(visible) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        val selected = filtered().filter { it.file.absolutePath in selectedPaths }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.photo_batch_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.video_delete_selected)
            .setMessage(getString(R.string.photo_delete_selected_confirm, selected.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ -> deletePhotos(selected) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deletePhotos(targets: List<GalleryPhoto>) {
        val deleted = targets.count { VideoStorage.deletePhoto(it.file) }
        Toast.makeText(this, getString(R.string.photo_delete_done, deleted), Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun openPhoto(photo: File) {
        startActivity(
            Intent(this, PhotoPreviewActivity::class.java)
                .putExtra(PhotoPreviewActivity.EXTRA_PATH, photo.absolutePath)
        )
    }

    private fun loadScaledBitmap(file: File, maxSize: Int) = BitmapFactory.Options().run {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
        var sample = 1
        while (outWidth / sample > maxSize || outHeight / sample > maxSize) sample *= 2
        inJustDecodeBounds = false
        inSampleSize = sample
        BitmapFactory.decodeFile(file.absolutePath, this)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class GalleryPhoto(val file: File, val timeMs: Long)
}

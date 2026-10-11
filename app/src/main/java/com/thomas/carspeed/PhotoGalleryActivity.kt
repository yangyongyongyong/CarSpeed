package com.thomas.carspeed

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoGalleryActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvPhotoSummary: TextView
    private lateinit var layoutPhotoList: LinearLayout
    private lateinit var btnDeleteAllPhotos: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_gallery)

        toolbar = findViewById(R.id.toolbar)
        tvPhotoSummary = findViewById(R.id.tvPhotoSummary)
        layoutPhotoList = findViewById(R.id.layoutPhotoList)
        btnDeleteAllPhotos = findViewById(R.id.btnDeleteAllPhotos)

        toolbar.setNavigationOnClickListener { finish() }
        btnDeleteAllPhotos.setOnClickListener { confirmDeleteAllPhotos() }
    }

    override fun onResume() {
        super.onResume()
        renderPhotos()
    }

    private fun renderPhotos() {
        layoutPhotoList.removeAllViews()
        val photos = VideoStorage.photoFiles(this).sortedByDescending { it.lastModified() }
        val totalBytes = photos.sumOf { it.length() }
        tvPhotoSummary.text = if (photos.isEmpty()) {
            getString(R.string.photo_history_empty)
        } else {
            getString(R.string.photo_history_summary, photos.size, formatBytes(totalBytes))
        }
        btnDeleteAllPhotos.isEnabled = photos.isNotEmpty()

        photos.forEach { photo ->
            layoutPhotoList.addView(photoRow(photo))
        }
    }

    private fun photoRow(photo: File): MaterialCardView {
        val card = MaterialCardView(this).apply {
            useCompatPadding = true
            radius = dp(16).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val thumbnail = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(loadScaledBitmap(photo, dp(96)))
            setOnClickListener { showPhoto(photo) }
        }
        row.addView(thumbnail, LinearLayout.LayoutParams(dp(96), dp(96)))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        detail.addView(TextView(this).apply {
            text = photo.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        })
        detail.addView(TextView(this).apply {
            text = "${fmt.format(Date(photo.lastModified()))}\n${formatBytes(photo.length())}\n${photo.absolutePath}"
            textSize = 12f
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.photo_view)
            setOnClickListener { showPhoto(photo) }
        })
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.photo_share)
            setOnClickListener { sharePhoto(photo) }
        })
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.video_delete_one)
            setOnClickListener { confirmDeletePhoto(photo) }
        })
        detail.addView(actions)

        row.addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
    }

    private fun showPhoto(photo: File) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(loadScaledBitmap(photo, 1600))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val container = ScrollView(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(image)
        }
        AlertDialog.Builder(this)
            .setTitle(photo.name)
            .setView(container)
            .setPositiveButton(R.string.photo_share) { _, _ -> sharePhoto(photo) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sharePhoto(photo: File) {
        val uri = photoUri(photo)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.photo_share_title)))
    }

    private fun confirmDeletePhoto(photo: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.photo_delete_one)
            .setMessage(photo.name)
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = if (VideoStorage.deletePhoto(photo)) 1 else 0
                Toast.makeText(this, getString(R.string.photo_delete_done, deleted), Toast.LENGTH_SHORT).show()
                renderPhotos()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllPhotos() {
        val photos = VideoStorage.photoFiles(this)
        if (photos.isEmpty()) {
            Toast.makeText(this, R.string.photo_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.photo_delete_all)
            .setMessage(getString(R.string.photo_delete_all_confirm, photos.size))
            .setPositiveButton(R.string.video_delete_confirm) { _, _ ->
                val deleted = VideoStorage.deleteAllPhotos(this)
                Toast.makeText(this, getString(R.string.photo_delete_done, deleted), Toast.LENGTH_SHORT).show()
                renderPhotos()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun photoUri(photo: File): Uri {
        return FileProvider.getUriForFile(this, "$packageName.files", photo)
    }

    private fun loadScaledBitmap(file: File, maxSize: Int) = BitmapFactory.Options().run {
        inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, this)
        var sample = 1
        while (outWidth / sample > maxSize || outHeight / sample > maxSize) {
            sample *= 2
        }
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
}

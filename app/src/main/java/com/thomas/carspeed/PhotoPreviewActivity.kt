package com.thomas.carspeed

import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class PhotoPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        val timeMs = file?.let { RoutePhotoMarks.timeFromFileName(it.name) }
        if (file == null || !file.exists() || timeMs == null) {
            Toast.makeText(this, R.string.photo_preview_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_photo_preview)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
        toolbar.setNavigationOnClickListener { finish() }
        val image = findViewById<ImageView>(R.id.ivPhoto)
        decoder.execute {
            val bitmap = PhotoBitmapLoader.decode(file, 1600)
            runOnUiThread {
                if (isDestroyed) {
                    bitmap?.recycle()
                } else {
                    image.setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onDestroy() {
        decoder.shutdownNow()
        super.onDestroy()
    }

    private val decoder = Executors.newSingleThreadExecutor()

    companion object {
        const val EXTRA_PATH = "photo_path"
    }
}

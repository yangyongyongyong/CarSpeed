package com.thomas.carspeed

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.io.File

class PhotoPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        if (file == null || !file.exists() || RoutePhotoMarks.timeFromFileName(file.name) == null) {
            Toast.makeText(this, R.string.photo_preview_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.activity_photo_preview)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = file.name
        toolbar.setNavigationOnClickListener { finish() }
        findViewById<ImageView>(R.id.ivPhoto).setImageBitmap(loadScaledBitmap(file, 1600))
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

    companion object {
        const val EXTRA_PATH = "photo_path"
    }
}

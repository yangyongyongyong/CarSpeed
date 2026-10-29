package com.thomas.carspeed

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 旧入口。导出已经并进播放页，这里只负责把原来的参数页转过去。 */
class ShareExportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, VideoReplayActivity::class.java).apply {
            putExtras(intent)
            putExtra(VideoReplayActivity.EXTRA_OPEN_EXPORT, true)
        })
        finish()
    }

    companion object {
        const val EXTRA_VIDEO_PATH = VideoReplayActivity.EXTRA_VIDEO_PATH
        const val EXTRA_VIDEO_PATHS = VideoReplayActivity.EXTRA_VIDEO_PATHS
    }
}

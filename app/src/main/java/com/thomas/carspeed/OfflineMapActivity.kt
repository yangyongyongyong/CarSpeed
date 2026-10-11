package com.thomas.carspeed

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMapException
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapProvince
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.google.android.material.appbar.MaterialToolbar

class OfflineMapActivity : AppCompatActivity(), OfflineMapManager.OfflineMapDownloadListener,
    OfflineMapManager.OfflineLoadedListener {
    private lateinit var listContainer: LinearLayout
    private var offlineManager: OfflineMapManager? = null
    private val rows = mutableMapOf<String, RowViews>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        offlineManager = OfflineMapManager(this, this).apply {
            setOnOfflineLoadedListener(this@OfflineMapActivity)
        }
        renderRows()
    }

    override fun onDestroy() {
        offlineManager?.destroy()
        offlineManager = null
        super.onDestroy()
    }

    override fun onDownload(status: Int, completeCode: Int, downName: String?) {
        runOnUiThread {
            val name = downName.orEmpty()
            rows[name]?.let { row ->
                row.progress.progress = completeCode.coerceIn(0, 100)
                row.detail.text = "${stateText(status)} · $completeCode%"
            }
            renderRows()
        }
    }

    override fun onCheckUpdate(hasNew: Boolean, name: String?) {
        runOnUiThread { renderRows() }
    }

    override fun onRemove(success: Boolean, name: String?, describe: String?) {
        runOnUiThread {
            renderRows()
            Toast.makeText(this, if (success) "已删除${name.orEmpty()}离线包" else "删除失败：${describe.orEmpty()}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onVerifyComplete() {
        runOnUiThread { renderRows() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
            setBackgroundColor(ContextCompat.getColor(this@OfflineMapActivity, R.color.brand_sand))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "离线地图"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTitleTextColor(ContextCompat.getColor(this@OfflineMapActivity, R.color.text_primary))
            setNavigationIcon(android.R.drawable.ic_menu_revert)
            setNavigationOnClickListener { finish() }
        }
        val summary = TextView(this).apply {
            text = "高德官方离线地图包。回放时若缺少当前轨迹所在省份，会自动下载对应离线包；在线状态下先显示高德地图，不会空白。"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@OfflineMapActivity, R.color.text_secondary))
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root.addView(toolbar)
        root.addView(summary)
        root.addView(scroll)
        setContentView(root)
    }

    private fun renderRows() {
        rows.clear()
        listContainer.removeAllViews()
        val provinces = offlineManager?.offlineMapProvinceList.orEmpty()
        if (provinces.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "正在加载高德离线包列表。若一直为空，请确认已配置高德 API Key 且网络可用。"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@OfflineMapActivity, R.color.text_secondary))
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        provinces.forEach { province -> listContainer.addView(packageRow(province)) }
    }

    private fun packageRow(province: OfflineMapProvince): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val title = TextView(this).apply {
            text = province.provinceName
            textSize = if (province.provinceName.contains("全国")) 17f else 16f
            setTextColor(ContextCompat.getColor(this@OfflineMapActivity, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val detail = TextView(this).apply {
            text = detailText(province)
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@OfflineMapActivity, R.color.text_secondary))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = province.getcompleteCode().coerceIn(0, 100)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val primary = Button(this).apply {
            text = primaryText(province.state)
            visibility = if (province.state == OfflineMapStatus.SUCCESS) View.GONE else View.VISIBLE
            setOnClickListener { downloadOrUpdate(province) }
        }
        val delete = Button(this).apply {
            text = "删除"
            isEnabled = province.state == OfflineMapStatus.SUCCESS || province.getcompleteCode() > 0
            setOnClickListener { offlineManager?.remove(province.provinceName) }
        }
        actions.addView(primary)
        actions.addView(delete)
        row.addView(title)
        row.addView(detail)
        row.addView(progress)
        row.addView(actions)
        rows[province.provinceName] = RowViews(detail, progress, primary, delete)
        return row
    }

    private fun downloadOrUpdate(province: OfflineMapProvince) {
        try {
            val manager = offlineManager ?: return
            if (province.state == OfflineMapStatus.SUCCESS || province.state == OfflineMapStatus.NEW_VERSION) {
                manager.updateOfflineMapProvinceByName(province.provinceName)
            } else {
                manager.downloadByProvinceName(province.provinceName)
            }
            Toast.makeText(this, "开始处理${province.provinceName}离线包", Toast.LENGTH_SHORT).show()
        } catch (error: AMapException) {
            Toast.makeText(this, "高德离线包操作失败：${error.errorMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun detailText(province: OfflineMapProvince): String {
        val version = province.version.takeIf { it.isNotBlank() } ?: "版本未知"
        return "${stateText(province.state)} · ${AmapOfflineMapStore.formatBytes(province.size)} · $version"
    }

    private fun primaryText(state: Int): String {
        return when (state) {
            OfflineMapStatus.NEW_VERSION -> "更新"
            OfflineMapStatus.LOADING -> "下载中"
            OfflineMapStatus.UNZIP -> "解压中"
            OfflineMapStatus.WAITING -> "等待中"
            OfflineMapStatus.PAUSE -> "继续"
            else -> "下载"
        }
    }

    private fun stateText(state: Int): String {
        return when (state) {
            OfflineMapStatus.CHECKUPDATES -> "检查更新"
            OfflineMapStatus.ERROR -> "下载异常"
            OfflineMapStatus.STOP -> "已停止"
            OfflineMapStatus.LOADING -> "下载中"
            OfflineMapStatus.UNZIP -> "解压中"
            OfflineMapStatus.WAITING -> "等待中"
            OfflineMapStatus.PAUSE -> "已暂停"
            OfflineMapStatus.SUCCESS -> "已下载"
            OfflineMapStatus.NEW_VERSION -> "有更新"
            OfflineMapStatus.EXCEPTION_NETWORK_LOADING -> "网络异常"
            OfflineMapStatus.EXCEPTION_AMAP -> "鉴权异常"
            OfflineMapStatus.EXCEPTION_SDCARD -> "存储异常"
            OfflineMapStatus.START_DOWNLOAD_FAILD -> "启动失败"
            else -> "未下载"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class RowViews(
        val detail: TextView,
        val progress: ProgressBar,
        val primary: Button,
        val delete: Button
    )
}

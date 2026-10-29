package com.thomas.carspeed

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * 播放页上的导出参数。长按画面或点「导出」打开。预览就是正在播的画面。
 */
internal class ReplayExportPanel(
    private val activity: AppCompatActivity,
    initial: ReplayExportSettings,
    private val onSpeedSelected: (Int) -> Unit,
    private val onChanged: (ReplayExportSettings) -> Unit,
    private val onExport: (Boolean) -> Unit
) {
    val view: ScrollView
    private val tvTimeRange: TextView
    private val tvSpeed: TextView
    private val tvFps: TextView
    private val seekStart: SeekBar
    private val seekEnd: SeekBar
    private val seekSpeed: SeekBar
    private val seekFps: SeekBar
    private val includeMapCheck: MaterialCheckBox
    private val fieldChecks: List<MaterialCheckBox>
    private val btnExportOnly: MaterialButton
    private val btnExportShare: MaterialButton
    private val tvStatus: TextView
    private val progress: ProgressBar
    private var totalDurationMs = 1_000L
    private var fpsTouched = initial.fpsCustomized
    private var applyingSpeed = false
    private var restoring = true

    init {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
        }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.share_export_subtitle)
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
        })
        tvTimeRange = label()
        box.addView(tvTimeRange)
        seekStart = SeekBar(activity).apply {
            setOnSeekBarChangeListener(seekListener { clampRange(); updateLabels() })
        }
        box.addView(seekStart)
        seekEnd = SeekBar(activity).apply {
            setOnSeekBarChangeListener(seekListener { clampRange(); updateLabels() })
        }
        box.addView(seekEnd)
        tvSpeed = label()
        box.addView(tvSpeed)
        seekSpeed = SeekBar(activity).apply {
            max = ExportFps.SPEEDS.lastIndex
            setOnSeekBarChangeListener(seekListener {
                updateLabels(notifySpeed = true)
                if (!applyingSpeed) emit()
            })
        }
        box.addView(seekSpeed)
        tvFps = label()
        box.addView(tvFps)
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.share_export_fps_hint)
            setTextColor(color(R.color.text_secondary))
            textSize = 12f
        })
        seekFps = SeekBar(activity).apply {
            max = ExportFps.CHOICES.lastIndex
            progress = ExportFps.CHOICES.indexOf(initial.fps).coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) fpsTouched = true
                    updateLabels()
                    if (fromUser) emit()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        box.addView(seekFps)
        box.addView(section(activity.getString(R.string.share_export_subtitle_fields)))
        val checks = listOf(
            R.string.share_export_field_time,
            R.string.share_export_field_speed,
            R.string.share_export_field_stationary_wait,
            R.string.share_export_field_gps,
            R.string.share_export_field_satellites,
            R.string.share_export_field_radar,
            R.string.share_export_field_address,
            R.string.share_export_field_focus,
            R.string.share_export_field_video_mode,
            R.string.share_export_field_road_layer
        )
        fieldChecks = checks.map { labelRes ->
            MaterialCheckBox(activity).apply {
                text = activity.getString(labelRes)
                isChecked = true
                setTextColor(color(R.color.text_primary))
                setOnCheckedChangeListener { _, _ -> emit() }
            }
        }
        fieldChecks.forEach { box.addView(it) }
        box.addView(section(activity.getString(R.string.share_export_route_map_title)))
        includeMapCheck = MaterialCheckBox(activity).apply {
            text = activity.getString(R.string.share_export_route_map_toggle)
            isChecked = initial.includeMap
            setTextColor(color(R.color.text_primary))
            setOnCheckedChangeListener { _, _ -> emit() }
        }
        box.addView(includeMapCheck)
        val buttonRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        btnExportOnly = MaterialButton(activity).apply {
            text = activity.getString(R.string.share_export_only)
            setOnClickListener { onExport(false) }
        }
        btnExportShare = MaterialButton(activity).apply {
            text = activity.getString(R.string.share_export_start)
            setOnClickListener { onExport(true) }
        }
        buttonRow.addView(btnExportOnly, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        buttonRow.addView(btnExportShare, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        box.addView(buttonRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        tvStatus = TextView(activity).apply {
            text = activity.getString(R.string.share_export_ready)
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }
        box.addView(tvStatus)
        box.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        val card = MaterialCardView(activity).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(color(R.color.surface_primary))
            addView(box)
        }
        view = ScrollView(activity).apply {
            setBackgroundColor(color(R.color.surface_primary))
            isClickable = true
            visibility = android.view.View.GONE
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        applyFields(initial.fields)
        applyingSpeed = true
        seekSpeed.progress = initial.speedIndex.coerceIn(0, ExportFps.SPEEDS.lastIndex)
        applyingSpeed = false
        restoring = false
    }

    fun isShowing(): Boolean = view.visibility == android.view.View.VISIBLE

    fun show(root: FrameLayout, totalDurationMs: Long, speedIndex: Int) {
        this.totalDurationMs = totalDurationMs.coerceAtLeast(1_000L)
        val seconds = durationSeconds()
        seekStart.max = seconds
        seekEnd.max = seconds
        if (seekEnd.progress <= seekStart.progress) seekEnd.progress = seconds
        if (view.parent == null) {
            val height = (root.height * 0.68f).toInt().coerceAtLeast(dp(240))
            root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height, Gravity.BOTTOM))
        }
        view.elevation = dp(24).toFloat()
        view.bringToFront()
        view.visibility = android.view.View.VISIBLE
        // 面板只建一次，用户可能在设置页改过字幕开关，打开时重新同步一遍。
        restoring = true
        applyFields(DashcamSettings.subtitleFields(activity))
        restoring = false
        syncSpeed(speedIndex)
    }

    fun keepAboveMap() {
        if (!isShowing()) return
        view.elevation = dp(24).toFloat()
        view.bringToFront()
    }

    fun hide() {
        view.visibility = android.view.View.GONE
    }

    fun syncSpeed(index: Int) {
        val clamped = index.coerceIn(0, ExportFps.SPEEDS.lastIndex)
        applyingSpeed = true
        seekSpeed.progress = clamped
        applyingSpeed = false
        if (!fpsTouched) {
            val fps = ExportFps.defaultFor(ExportFps.SPEEDS[clamped])
            val fpsIndex = ExportFps.CHOICES.indexOf(fps).coerceAtLeast(0)
            seekFps.progress = fpsIndex
        }
        updateLabels()
        if (!restoring) emit()
    }

    fun selectedSpeed(): Float = ExportFps.SPEEDS[seekSpeed.progress.coerceIn(0, ExportFps.SPEEDS.lastIndex)]

    fun selectedFps(): Int = ExportFps.CHOICES[seekFps.progress.coerceIn(0, ExportFps.CHOICES.lastIndex)]

    fun selectedStartMs(): Long = seekStart.progress * 1_000L

    fun selectedEndMs(): Long {
        return (seekEnd.progress * 1_000L).coerceAtLeast(selectedStartMs() + 1_000L).coerceAtMost(totalDurationMs)
    }

    fun includeMap(): Boolean = includeMapCheck.isChecked

    fun snapshot(): ReplayExportSettings {
        return ReplayExportSettings(
            speedIndex = seekSpeed.progress.coerceIn(0, ExportFps.SPEEDS.lastIndex),
            fps = selectedFps(),
            fpsCustomized = fpsTouched,
            includeMap = includeMap(),
            fields = subtitleFields()
        )
    }

    private fun emit() {
        if (restoring) return
        onChanged(snapshot())
    }

    private fun applyFields(fields: ShareSubtitleFields) {
        val values = listOf(
            fields.showTime,
            fields.showSpeed,
            fields.showStationaryWait,
            fields.showGps,
            fields.showSatellites,
            fields.showRadarSummary,
            fields.showAddress,
            fields.showFocus,
            fields.showVideoMode,
            fields.showRoadLayer
        )
        fieldChecks.forEachIndexed { index, box ->
            box.isChecked = values.getOrElse(index) { true }
        }
    }

    fun subtitleFields(): ShareSubtitleFields {
        val checked = fieldChecks.map { it.isChecked }
        return ShareSubtitleFields(
            showTime = checked[0],
            showSpeed = checked[1],
            showStationaryWait = checked[2],
            showGps = checked[3],
            showSatellites = checked[4],
            showRadarSummary = checked[5],
            showAddress = checked[6],
            showFocus = checked[7],
            showVideoMode = checked[8],
            showRoadLayer = checked[9]
        )
    }

    fun setBusy(busy: Boolean) {
        btnExportOnly.isEnabled = !busy
        btnExportShare.isEnabled = !busy
        if (busy) {
            progress.visibility = android.view.View.VISIBLE
        } else {
            progress.visibility = android.view.View.GONE
        }
    }

    fun setProgress(percent: Int, status: String) {
        progress.visibility = android.view.View.VISIBLE
        progress.progress = percent
        tvStatus.text = status
    }

    fun setStatus(status: String) {
        tvStatus.text = status
    }

    private fun updateLabels(notifySpeed: Boolean = false) {
        val startMs = selectedStartMs()
        val endMs = selectedEndMs()
        tvTimeRange.text = activity.getString(
            R.string.share_export_time_range,
            ReplayTimeline.formatDuration(startMs),
            ReplayTimeline.formatDuration(endMs),
            ReplayTimeline.formatDuration((endMs - startMs).coerceAtLeast(0L))
        )
        tvSpeed.text = activity.getString(R.string.share_export_speed, speedLabel(selectedSpeed()))
        tvFps.text = activity.getString(R.string.share_export_fps, selectedFps())
        if (notifySpeed && !applyingSpeed) onSpeedSelected(seekSpeed.progress)
    }

    private fun clampRange() {
        if (seekEnd.progress <= seekStart.progress) {
            seekEnd.progress = (seekStart.progress + 1).coerceAtMost(seekEnd.max)
        }
        if (seekStart.progress >= seekEnd.progress) {
            seekStart.progress = (seekEnd.progress - 1).coerceAtLeast(0)
        }
    }

    private fun durationSeconds(): Int = ((totalDurationMs + 999L) / 1_000L).toInt().coerceAtLeast(1)

    private fun speedLabel(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
    }

    private fun label(): TextView {
        return TextView(activity).apply {
            setTextColor(color(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun section(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            setTextColor(color(R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun seekListener(onChange: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange()

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(activity, resId)

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

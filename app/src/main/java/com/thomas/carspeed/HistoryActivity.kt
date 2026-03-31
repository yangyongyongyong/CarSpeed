package com.thomas.carspeed

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private lateinit var spSortField: Spinner
    private lateinit var btnSortOrder: Button
    private lateinit var tvHistory: TextView
    private lateinit var toolbar: MaterialToolbar

    private var currentField: HistorySortField = HistorySortField.START_TIME
    private var ascending: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        spSortField = findViewById(R.id.spSortField)
        btnSortOrder = findViewById(R.id.btnSortOrder)
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
        render()
    }

    private fun setupSortUi() {
        val items = listOf("启动时间", "结束时间", "驾驶时长", "最高速度")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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

    private fun persistSortPref() {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putInt(PREF_HISTORY_SORT_FIELD, currentField.ordinal)
            .putBoolean(PREF_HISTORY_SORT_ASC, ascending)
            .apply()
    }

    private fun render() {
        val records = DriveRecordStore.loadRecords(this)
        if (records.isEmpty()) {
            tvHistory.text = getString(R.string.history_empty)
            return
        }

        val sorted = HistorySort.sort(records, currentField, ascending)
        val text = buildString {
            appendLine("历史驾驶记录（最多100条）")
            appendLine("排序字段: ${fieldLabel(currentField)}  顺序: ${if (ascending) "升序" else "降序"}")
            appendLine("====================")
            sorted.forEachIndexed { idx, r ->
                appendLine("#${idx + 1}")
                appendLine("启动时间: ${fmt.format(Date(r.startTimeMs))}")
                appendLine("结束时间: ${fmt.format(Date(r.endTimeMs))}")
                appendLine("本次耗时: ${formatDuration(r.durationMs)}")
                appendLine("静止等待: ${formatDuration(r.waitDurationMs)}")
                appendLine("最高速度: ${String.format("%.1f", r.maxSpeedKmh)} km/h")
                appendLine("--------------------")
            }
        }
        tvHistory.text = text
    }

    private fun fieldLabel(field: HistorySortField): String {
        return when (field) {
            HistorySortField.START_TIME -> "启动时间"
            HistorySortField.END_TIME -> "结束时间"
            HistorySortField.DURATION -> "驾驶时长"
            HistorySortField.MAX_SPEED -> "最高速度"
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    companion object {
        private const val PREF_HISTORY_SORT_FIELD = "history_sort_field"
        private const val PREF_HISTORY_SORT_ASC = "history_sort_asc"
    }
}

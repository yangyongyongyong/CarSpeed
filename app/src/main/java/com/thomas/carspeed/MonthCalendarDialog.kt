package com.thomas.carspeed

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.TimeZone

internal object MonthCalendarDialog {
    fun show(
        activity: AppCompatActivity,
        spans: List<CountSpan>,
        selection: DaySelection?,
        onSelection: (DaySelection) -> Unit
    ) {
        val zone = TimeZone.getDefault()
        val anchor = selection?.startMs
            ?: spans.maxOfOrNull { maxOf(it.startMs, it.endMs) }?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        var shown = PhotoCalendar.yearMonth(anchor, zone)
        var current = selection
        val panelColor = activity.getColor(R.color.surface_primary)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panelColor)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 4))
            minimumWidth = activity.resources.displayMetrics.widthPixels - dp(activity, 48)
        }
        val title = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 16f
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previous = monthButton(activity, activity.getString(R.string.photo_calendar_prev))
        val next = monthButton(activity, activity.getString(R.string.photo_calendar_next))
        header.addView(previous)
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(next)
        val hint = TextView(activity).apply {
            text = activity.getString(R.string.photo_calendar_hint)
            setTextColor(activity.getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, dp(activity, 4), 0, dp(activity, 8))
        }
        val weekdays = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        activity.resources.getStringArray(R.array.photo_weekdays).forEach { label ->
            weekdays.addView(TextView(activity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(activity.getColor(R.color.text_secondary))
                textSize = 12f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val grid = GridLayout(activity).apply { columnCount = 7 }
        root.addView(header)
        root.addView(hint)
        root.addView(weekdays)
        root.addView(grid)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_CarSpeed_CalendarDialog)
            .setView(root)
            .setPositiveButton(R.string.photo_calendar_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun paint() {
            val (year, month) = shown
            title.text = activity.getString(R.string.photo_calendar_title, year, month)
            grid.removeAllViews()
            PhotoCalendar.monthGridSpans(year, month, spans, zone).forEach { cell ->
                grid.addView(dayCell(activity, cell, current, zone) {
                    val nextSelection = PhotoCalendar.selectDay(current, cell.dayStartMs)
                    if (nextSelection != current) {
                        current = nextSelection
                        paint()
                    }
                })
            }
        }
        previous.setOnClickListener {
            shown = PhotoCalendar.addMonth(shown.first, shown.second, -1)
            paint()
        }
        next.setOnClickListener {
            shown = PhotoCalendar.addMonth(shown.first, shown.second, 1)
            paint()
        }
        paint()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(panelColor))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialog.window?.decorView?.isForceDarkAllowed = false
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(activity.getColor(R.color.white))
                setBackgroundColor(activity.getColor(R.color.brand_blue))
                setOnClickListener {
                    current?.let(onSelection)
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(activity.getColor(R.color.text_primary))
        }
        dialog.show()
    }

    private fun dayCell(
        activity: AppCompatActivity,
        cell: PhotoDayCell,
        selection: DaySelection?,
        zone: TimeZone,
        onClick: () -> Unit
    ): LinearLayout {
        val day = cell.dayOfMonth
        val selected = day != null && selection != null &&
            cell.dayStartMs in selection.startMs..selection.endMs
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(activity, 52)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(activity, 1), dp(activity, 1), dp(activity, 1), dp(activity, 1))
            }
            layoutParams = params
            if (day == null) return@apply
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 8).toFloat()
                setColor(
                    if (selected) activity.getColor(R.color.brand_blue) else activity.getColor(R.color.surface_secondary)
                )
            }
            val dayColor = if (selected) activity.getColor(R.color.white) else activity.getColor(R.color.text_primary)
            val countColor = when {
                selected -> activity.getColor(R.color.white)
                cell.count > 0 -> activity.getColor(R.color.brand_blue)
                else -> activity.getColor(R.color.text_secondary)
            }
            addView(TextView(activity).apply {
                text = day.toString()
                gravity = Gravity.CENTER
                setTextColor(dayColor)
                textSize = 14f
            })
            addView(TextView(activity).apply {
                text = cell.count.toString()
                gravity = Gravity.CENTER
                setTextColor(countColor)
                textSize = 11f
            })
            setOnClickListener { onClick() }
        }
    }

    private fun monthButton(activity: AppCompatActivity, label: String): TextView {
        return TextView(activity).apply {
            text = label
            setTextColor(activity.getColor(R.color.brand_blue))
            textSize = 14f
            setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
        }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int {
        return (value * activity.resources.displayMetrics.density).toInt()
    }
}

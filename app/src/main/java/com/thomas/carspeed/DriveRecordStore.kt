package com.thomas.carspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DriveRecord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val maxSpeedKmh: Float
)

object DriveRecordStore {
    private const val PREF_NAME = "overlay_prefs"
    private const val KEY_RECORDS = "drive_records"
    private const val MAX_RECORDS = 100

    fun addRecord(context: Context, record: DriveRecord) {
        val list = loadRecords(context).toMutableList()
        list.add(0, record)
        if (list.size > MAX_RECORDS) {
            list.subList(MAX_RECORDS, list.size).clear()
        }
        saveRecords(context, list)
    }

    fun loadRecords(context: Context): List<DriveRecord> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val result = mutableListOf<DriveRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            result += DriveRecord(
                startTimeMs = o.optLong("startTimeMs", 0L),
                endTimeMs = o.optLong("endTimeMs", 0L),
                durationMs = o.optLong("durationMs", 0L),
                maxSpeedKmh = o.optDouble("maxSpeedKmh", 0.0).toFloat()
            )
        }
        return result
    }

    private fun saveRecords(context: Context, records: List<DriveRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("startTimeMs", r.startTimeMs)
                    .put("endTimeMs", r.endTimeMs)
                    .put("durationMs", r.durationMs)
                    .put("maxSpeedKmh", r.maxSpeedKmh)
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, arr.toString())
            .apply()
    }
}

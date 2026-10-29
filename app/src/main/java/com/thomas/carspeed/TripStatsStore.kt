package com.thomas.carspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

internal object TripStatsStore {
    private const val FILE_NAME = "trip_stats.json"
    private val lock = Any()

    fun record(context: Context, record: DriveRecord) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val zone = ZoneId.systemDefault()
            val updated = TripStats.apply(
                snapshot = read(appContext),
                startMs = record.startTimeMs,
                endMs = record.endTimeMs,
                distanceMeters = record.distanceMeters.toDouble(),
                durationMs = record.durationMs,
                zone = zone
            )
            write(appContext, updated)
        }
    }

    fun loadForReading(context: Context, nowMs: Long = System.currentTimeMillis()): TripStatsSnapshot {
        val appContext = context.applicationContext
        synchronized(lock) {
            return TripStats.alignForReading(read(appContext), nowMs, ZoneId.systemDefault())
        }
    }

    private fun read(context: Context): TripStatsSnapshot {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return TripStatsSnapshot()
        return runCatching { decode(JSONObject(file.readText())) }.getOrDefault(TripStatsSnapshot())
    }

    private fun write(context: Context, snapshot: TripStatsSnapshot) {
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(encode(snapshot).toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun encode(snapshot: TripStatsSnapshot): JSONObject {
        val spans = JSONArray()
        snapshot.recentSpans.forEach { span ->
            spans.put(JSONArray().put(span.startMs).put(span.endMs))
        }
        return JSONObject()
            .put("dayKey", snapshot.dayKey)
            .put("dayDistance", snapshot.day.distanceMeters)
            .put("dayDuration", snapshot.day.durationMs)
            .put("monthKey", snapshot.monthKey)
            .put("monthDistance", snapshot.month.distanceMeters)
            .put("monthDuration", snapshot.month.durationMs)
            .put("totalDistance", snapshot.cumulative.distanceMeters)
            .put("totalDuration", snapshot.cumulative.durationMs)
            .put("spans", spans)
    }

    private fun decode(json: JSONObject): TripStatsSnapshot {
        val spansJson = json.optJSONArray("spans")
        val spans = mutableListOf<CountedSpan>()
        if (spansJson != null) {
            for (i in 0 until spansJson.length()) {
                val pair = spansJson.optJSONArray(i) ?: continue
                spans += CountedSpan(pair.optLong(0), pair.optLong(1))
            }
        }
        return TripStatsSnapshot(
            dayKey = json.optString("dayKey"),
            day = TripStatsBucket(json.optFiniteDouble("dayDistance"), json.optLong("dayDuration", 0L)),
            monthKey = json.optString("monthKey"),
            month = TripStatsBucket(json.optFiniteDouble("monthDistance"), json.optLong("monthDuration", 0L)),
            cumulative = TripStatsBucket(json.optFiniteDouble("totalDistance"), json.optLong("totalDuration", 0L)),
            recentSpans = spans
        )
    }

    private fun JSONObject.optFiniteDouble(key: String): Double {
        val value = optDouble(key, 0.0)
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }
}

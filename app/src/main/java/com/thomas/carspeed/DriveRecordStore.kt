package com.thomas.carspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DriveRecord(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val maxSpeedKmh: Float,
    val waitDurationMs: Long = 0L,
    val distanceMeters: Float = 0f,
    val averageSpeedKmh: Float = 0f,
    val maxSpeedLatitude: Double? = null,
    val maxSpeedLongitude: Double? = null,
    val maxSpeedAccuracyMeters: Float? = null,
    val averageAccuracyMeters: Float? = null,
    val goodFixRatio: Float = 0f,
    val routePoints: List<DriveRoutePoint> = emptyList(),
    val source: String = "legacy"
)

data class DriveRoutePoint(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val accuracyMeters: Float?,
    val bearingDegrees: Float?
)

object DriveRecordStore {
    private const val PREF_NAME = "overlay_prefs"
    private const val KEY_RECORDS = "drive_records"
    private const val MAX_RECORDS = 100
    const val SOURCE_LEGACY = "legacy"
    const val SOURCE_OVERLAY = "overlay"
    const val SOURCE_VIDEO = "video"
    const val SOURCE_GPS_TRACK = "gps_track"

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
                maxSpeedKmh = o.optDouble("maxSpeedKmh", 0.0).toFloat(),
                waitDurationMs = o.optLong("waitDurationMs", 0L),
                distanceMeters = o.optDouble("distanceMeters", 0.0).toFloat(),
                averageSpeedKmh = o.optDouble("averageSpeedKmh", 0.0).toFloat(),
                maxSpeedLatitude = o.optNullableDouble("maxSpeedLatitude"),
                maxSpeedLongitude = o.optNullableDouble("maxSpeedLongitude"),
                maxSpeedAccuracyMeters = o.optNullableFloat("maxSpeedAccuracyMeters"),
                averageAccuracyMeters = o.optNullableFloat("averageAccuracyMeters"),
                goodFixRatio = o.optDouble("goodFixRatio", 0.0).toFloat().coerceIn(0f, 1f),
                routePoints = parseRoutePoints(o.optJSONArray("routePoints")),
                source = o.optString("source", SOURCE_LEGACY).ifBlank { SOURCE_LEGACY }
            )
        }
        return result
    }

    fun averageSpeedKmh(distanceMeters: Float, durationMs: Long, waitDurationMs: Long): Float {
        val movingMs = (durationMs - waitDurationMs).coerceAtLeast(0L)
        val baseMs = if (movingMs > 0L) movingMs else durationMs
        if (baseMs <= 0L || distanceMeters <= 0f) return 0f
        return (distanceMeters / (baseMs / 1000f) * 3.6f).coerceIn(0f, 9999f)
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
                    .put("waitDurationMs", r.waitDurationMs)
                    .put("distanceMeters", r.distanceMeters)
                    .put("averageSpeedKmh", r.averageSpeedKmh)
                    .putNullable("maxSpeedLatitude", r.maxSpeedLatitude)
                    .putNullable("maxSpeedLongitude", r.maxSpeedLongitude)
                    .putNullable("maxSpeedAccuracyMeters", r.maxSpeedAccuracyMeters)
                    .putNullable("averageAccuracyMeters", r.averageAccuracyMeters)
                    .put("goodFixRatio", r.goodFixRatio)
                    .put("routePoints", routePointsToJson(r.routePoints))
                    .put("source", r.source)
            )
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, arr.toString())
            .apply()
    }

    private fun parseRoutePoints(arr: JSONArray?): List<DriveRoutePoint> {
        if (arr == null) return emptyList()
        val result = mutableListOf<DriveRoutePoint>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("latitude", Double.NaN)
            val lon = o.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            result += DriveRoutePoint(
                timeMs = o.optLong("timeMs", 0L),
                latitude = lat,
                longitude = lon,
                speedKmh = o.optDouble("speedKmh", 0.0).toFloat(),
                accuracyMeters = o.optNullableFloat("accuracyMeters"),
                bearingDegrees = o.optNullableFloat("bearingDegrees")
            )
        }
        return result
    }

    private fun routePointsToJson(points: List<DriveRoutePoint>): JSONArray {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(
                JSONObject()
                    .put("timeMs", p.timeMs)
                    .put("latitude", p.latitude)
                    .put("longitude", p.longitude)
                    .put("speedKmh", p.speedKmh)
                    .putNullable("accuracyMeters", p.accuracyMeters)
                    .putNullable("bearingDegrees", p.bearingDegrees)
            )
        }
        return arr
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        return if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }
}

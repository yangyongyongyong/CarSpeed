package com.thomas.carspeed

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private const val FILE_NAME = "drive_records.json"
    private const val MAX_RECORDS = 100
    private const val MAX_OTHER_RECORDS = 50
    private const val TAG = "DriveRecordStore"
    private val lock = Any()
    const val SOURCE_LEGACY = "legacy"
    const val SOURCE_OVERLAY = "overlay"
    const val SOURCE_VIDEO = "video"
    const val SOURCE_GPS_TRACK = "gps_track"

    /** 驾驶记录文件读写都很重，统一放到这个单线程里，保证先后顺序。 */
    internal val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "drive-record-io")
    }

    fun addRecord(context: Context, record: DriveRecord) {
        val appContext = context.applicationContext
        val saved = synchronized(lock) {
            val loaded = loadRecordsLocked(appContext)
            if (loaded.readFailed) {
                // 读不出旧记录时不能用"只有一条"的列表覆盖文件，否则整个历史会被清空。
                Log.w(TAG, "drive records unreadable; skip saving to protect existing history")
                false
            } else {
                val list = loaded.records.toMutableList()
                list.add(0, record)
                saveRecordsLocked(appContext, compactRecords(list))
                true
            }
        }
        if (saved) TripStatsStore.record(appContext, record)
    }

    fun addRecordAsync(context: Context, record: DriveRecord, afterSaved: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        ioExecutor.execute {
            try {
                addRecord(appContext, record)
            } catch (error: Exception) {
                Log.w(TAG, "save drive record failed", error)
            }
            afterSaved?.invoke()
        }
    }

    fun loadRecords(context: Context): List<DriveRecord> {
        val appContext = context.applicationContext
        synchronized(lock) {
            return loadRecordsLocked(appContext).records
        }
    }

    private class LoadResult(val records: List<DriveRecord>, val readFailed: Boolean = false)

    internal fun compactRecords(records: List<DriveRecord>): List<DriveRecord> {
        val gpsTracks = records.filter { it.source == SOURCE_GPS_TRACK }.take(MAX_RECORDS)
        val others = records.filter { it.source != SOURCE_GPS_TRACK }.take(MAX_OTHER_RECORDS)
        val latestOtherWithRoute = others.maxByOrNull { if (it.routePoints.isNotEmpty()) it.startTimeMs else Long.MIN_VALUE }
            ?.takeIf { it.routePoints.isNotEmpty() }
        val compactedOthers = others.map { record ->
            if (record === latestOtherWithRoute) record else record.copy(routePoints = emptyList())
        }
        return (gpsTracks + compactedOthers).sortedByDescending { it.startTimeMs }
    }

    private fun loadRecordsLocked(context: Context): LoadResult {
        val file = File(context.filesDir, FILE_NAME)
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (file.isFile) {
            if (prefs.contains(KEY_RECORDS)) {
                prefs.edit().remove(KEY_RECORDS).apply()
            }
            val raw = try {
                file.readText(Charsets.UTF_8)
            } catch (error: IOException) {
                Log.w(TAG, "read drive records failed", error)
                return LoadResult(emptyList(), readFailed = true)
            } catch (error: OutOfMemoryError) {
                Log.w(TAG, "drive records too large to read", error)
                return LoadResult(emptyList(), readFailed = true)
            }
            if (raw.isBlank()) return LoadResult(emptyList())
            val parsed = parseRecordsJsonOrNull(raw)
            if (parsed == null) {
                // 内容已损坏：先备份坏文件，再允许从空列表重新开始，旧数据仍可从备份找回。
                backupCorruptFile(context, file)
                return LoadResult(emptyList())
            }
            return LoadResult(parsed)
        }
        val legacyRaw = prefs.getString(KEY_RECORDS, null) ?: return LoadResult(emptyList())
        val migrated = compactRecords(parseRecordsJsonOrNull(legacyRaw).orEmpty())
        saveRecordsLocked(context, migrated)
        prefs.edit().remove(KEY_RECORDS).apply()
        return LoadResult(migrated)
    }

    private fun backupCorruptFile(context: Context, file: File) {
        val backup = File(context.filesDir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")
        val moved = file.renameTo(backup) || runCatching { file.copyTo(backup, overwrite = true); true }.getOrDefault(false)
        Log.w(TAG, "drive records corrupt, backed up=$moved to ${backup.name}")
    }

    internal fun parseRecordsJsonOrNull(raw: String): List<DriveRecord>? {
        val arr = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            return null
        }
        val result = mutableListOf<DriveRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            result += recordFromJson(o)
        }
        return result
    }

    internal fun recordFromJson(o: JSONObject): DriveRecord {
        return DriveRecord(
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

    internal fun recordToJson(r: DriveRecord): JSONObject {
        return JSONObject()
            .put("startTimeMs", r.startTimeMs)
            .put("endTimeMs", r.endTimeMs)
            .put("durationMs", r.durationMs)
            .put("maxSpeedKmh", r.maxSpeedKmh.finiteOrZero())
            .put("waitDurationMs", r.waitDurationMs)
            .put("distanceMeters", r.distanceMeters.finiteOrZero())
            .put("averageSpeedKmh", r.averageSpeedKmh.finiteOrZero())
            .putNullable("maxSpeedLatitude", r.maxSpeedLatitude)
            .putNullable("maxSpeedLongitude", r.maxSpeedLongitude)
            .putNullable("maxSpeedAccuracyMeters", r.maxSpeedAccuracyMeters?.takeIf { it.isFinite() })
            .putNullable("averageAccuracyMeters", r.averageAccuracyMeters?.takeIf { it.isFinite() })
            .put("goodFixRatio", r.goodFixRatio.finiteOrZero())
            .put("routePoints", routePointsToJson(r.routePoints))
            .put("source", r.source)
    }

    private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

    fun averageSpeedKmh(distanceMeters: Float, durationMs: Long, waitDurationMs: Long): Float {
        val movingMs = (durationMs - waitDurationMs).coerceAtLeast(0L)
        val baseMs = if (movingMs > 0L) movingMs else durationMs
        if (baseMs <= 0L || distanceMeters <= 0f) return 0f
        return (distanceMeters / (baseMs / 1000f) * 3.6f).coerceIn(0f, 9999f)
    }

    private fun saveRecordsLocked(context: Context, records: List<DriveRecord>) {
        val arr = JSONArray()
        records.forEach { r -> arr.put(recordToJson(r)) }
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
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
                    .put("speedKmh", p.speedKmh.finiteOrZero())
                    .putNullable("accuracyMeters", p.accuracyMeters?.takeIf { it.isFinite() })
                    .putNullable("bearingDegrees", p.bearingDegrees?.takeIf { it.isFinite() })
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

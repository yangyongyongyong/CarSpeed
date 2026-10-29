package com.thomas.carspeed

import android.content.Context
import org.json.JSONObject
import java.io.File

object VideoHistoryCache {
    data class Entry(
        val tripId: String,
        val signature: String,
        val totalBytes: Long,
        val hasTrack: Boolean,
        val hasSubtitle: Boolean,
        val trackSummary: TrackSummary?,
        val startPoint: VideoTrackPoint?,
        val endPoint: VideoTrackPoint?,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val durationMs: Long,
        val distanceMeters: Float
    )

    class Store(
        private val context: Context,
        private val entries: JSONObject
    ) {
        private var dirty = false

        fun read(tripId: String, signature: String): Entry? {
            val entry = entries.optJSONObject(tripId)?.toEntry() ?: return null
            return entry.takeIf { it.signature == signature }
        }

        fun write(entry: Entry) {
            entries.put(entry.tripId, entry.toJson())
            dirty = true
        }

        fun prune(activeTripIds: Set<String>) {
            val keys = entries.keys().asSequence().toList()
            keys.filterNot { it in activeTripIds }.forEach { key ->
                entries.remove(key)
                dirty = true
            }
        }

        fun save() {
            if (!dirty) return
            writeCacheFile(context, entries.toString())
            dirty = false
        }
    }

    /**
     * 缓存存成单独的文件。以前整份 JSON 塞在 SharedPreferences 里，记录一多，
     * 每次读写都要把整个偏好文件解析、重写一遍。
     */
    fun load(context: Context): Store {
        val appContext = context.applicationContext
        val raw = readCacheFile(appContext) ?: migrateFromPrefs(appContext)
        val entries = try {
            raw?.let { JSONObject(it) } ?: JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        return Store(appContext, entries)
    }

    private val fileLock = Any()

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE_NAME)

    private fun readCacheFile(context: Context): String? {
        synchronized(fileLock) {
            val file = cacheFile(context)
            if (!file.isFile) return null
            return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        }
    }

    private fun writeCacheFile(context: Context, raw: String) {
        synchronized(fileLock) {
            val file = cacheFile(context)
            val temp = File(file.parentFile, "${file.name}.tmp")
            runCatching {
                temp.writeText(raw, Charsets.UTF_8)
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
        }
    }

    private fun migrateFromPrefs(context: Context): String? {
        val prefs = prefs(context)
        val legacy = prefs.getString(KEY_ENTRIES, null) ?: return null
        writeCacheFile(context, legacy)
        prefs.edit().remove(KEY_ENTRIES).apply()
        return legacy
    }

    fun signatureFor(trip: VideoStorage.VideoTrip): String {
        return signatureForFiles(trip.segments)
    }

    fun signatureForFiles(files: List<File>): String {
        return files.distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
            .joinToString("|") { file ->
                val exists = file.exists()
                listOf(
                    file.absolutePath,
                    if (exists) file.length().toString() else "-1",
                    if (exists) file.lastModified().toString() else "-1"
                ).joinToString(":")
            }
    }

    private fun Entry.toJson(): JSONObject {
        return JSONObject()
            .put("version", CACHE_VERSION)
            .put("tripId", tripId)
            .put("signature", signature)
            .put("totalBytes", totalBytes)
            .put("hasTrack", hasTrack)
            .put("hasSubtitle", hasSubtitle)
            .putNullable("trackSummary", trackSummary?.toJson())
            .putNullable("startPoint", startPoint?.toJson())
            .putNullable("endPoint", endPoint?.toJson())
            .put("startTimeMs", startTimeMs)
            .put("endTimeMs", endTimeMs)
            .put("durationMs", durationMs)
            .put("distanceMeters", distanceMeters.toDouble())
    }

    private fun JSONObject.toEntry(): Entry? {
        if (optInt("version", 0) != CACHE_VERSION) return null
        val tripId = optString("tripId").takeIf { it.isNotBlank() } ?: return null
        val signature = optString("signature").takeIf { it.isNotBlank() } ?: return null
        return Entry(
            tripId = tripId,
            signature = signature,
            totalBytes = optLong("totalBytes", 0L),
            hasTrack = optBoolean("hasTrack", false),
            hasSubtitle = optBoolean("hasSubtitle", false),
            trackSummary = optJSONObject("trackSummary")?.toTrackSummary(),
            startPoint = optJSONObject("startPoint")?.toVideoTrackPoint(),
            endPoint = optJSONObject("endPoint")?.toVideoTrackPoint(),
            startTimeMs = optLong("startTimeMs", 0L),
            endTimeMs = optLong("endTimeMs", 0L),
            durationMs = optLong("durationMs", 0L),
            distanceMeters = optDouble("distanceMeters", 0.0).toFloat()
        )
    }

    private fun TrackSummary.toJson(): JSONObject {
        return JSONObject()
            .put("durationMs", durationMs)
            .put("totalDistanceMeters", totalDistanceMeters.toDouble())
            .put("maxSpeedKmh", maxSpeedKmh.toDouble())
            .put("averageSpeedKmh", averageSpeedKmh.toDouble())
            .putNullable("averageAccuracyMeters", averageAccuracyMeters)
            .put("goodFixRatio", goodFixRatio.toDouble())
    }

    private fun JSONObject.toTrackSummary(): TrackSummary {
        return TrackSummary(
            durationMs = optLong("durationMs", 0L),
            totalDistanceMeters = optDouble("totalDistanceMeters", 0.0).toFloat(),
            maxSpeedKmh = optDouble("maxSpeedKmh", 0.0).toFloat(),
            averageSpeedKmh = optDouble("averageSpeedKmh", 0.0).toFloat(),
            averageAccuracyMeters = optNullableFloat("averageAccuracyMeters"),
            goodFixRatio = optDouble("goodFixRatio", 0.0).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun VideoTrackPoint.toJson(): JSONObject {
        return JSONObject()
            .put("elapsedMs", elapsedMs)
            .put("timeMs", timeMs)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("speedKmh", speedKmh.toDouble())
            .putNullable("accuracyMeters", accuracyMeters)
            .putNullable("bearingDegrees", bearingDegrees)
    }

    private fun JSONObject.toVideoTrackPoint(): VideoTrackPoint? {
        val lat = optDouble("latitude", Double.NaN)
        val lon = optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return VideoTrackPoint(
            elapsedMs = optLong("elapsedMs", 0L),
            timeMs = optLong("timeMs", 0L),
            latitude = lat,
            longitude = lon,
            speedKmh = optDouble("speedKmh", 0.0).toFloat(),
            accuracyMeters = optNullableFloat("accuracyMeters"),
            bearingDegrees = optNullableFloat("bearingDegrees")
        )
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        return if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private const val PREF_NAME = "video_history_cache"
    private const val KEY_ENTRIES = "entries"
    private const val CACHE_VERSION = 2
    private const val CACHE_FILE_NAME = "video_history_cache.json"
}

package com.thomas.carspeed

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VideoTrack(
    val version: Int = 1,
    val videoFileName: String,
    val startTimeMs: Long,
    val points: List<VideoTrackPoint>
)

data class VideoTrackPoint(
    val elapsedMs: Long,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val accuracyMeters: Float?,
    val bearingDegrees: Float?
)

object VideoTrackStorage {
    fun writeTrack(videoFile: File, track: VideoTrack): File {
        val file = VideoStorage.trackFileFor(videoFile)
        file.parentFile?.mkdirs()
        file.writeText(toJson(track).toString(), Charsets.UTF_8)
        return file
    }

    fun writeTrack(context: Context, videoFile: File, track: VideoTrack): File {
        val file = VideoStorage.trackFileFor(context, videoFile)
        file.parentFile?.mkdirs()
        file.writeText(toJson(track).toString(), Charsets.UTF_8)
        return file
    }

    fun readTrack(videoFile: File): VideoTrack? {
        val file = VideoStorage.trackFileFor(videoFile)
        if (!file.exists()) return null
        return try {
            fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }

    fun readTrack(context: Context, videoFile: File): VideoTrack? {
        return VideoStorage.trackFilesFor(context, videoFile).firstNotNullOfOrNull { file ->
            if (!file.exists()) return@firstNotNullOfOrNull null
            try {
                fromJson(JSONObject(file.readText(Charsets.UTF_8)))
            } catch (_: Exception) {
                null
            }
        }
    }

    fun readMergedTrack(videoFiles: List<File>): VideoTrack? {
        val tracks = videoFiles.mapNotNull { readTrack(it) }.sortedBy { it.startTimeMs }
        if (tracks.isEmpty()) return null
        val firstStartMs = tracks.first().startTimeMs.takeIf { it > 0L }
            ?: tracks.first().points.firstOrNull()?.timeMs
            ?: 0L
        val mergedPoints = tracks.flatMap { track ->
            val segmentOffsetMs = (track.startTimeMs - firstStartMs).coerceAtLeast(0L)
            track.points.map { point ->
                point.copy(elapsedMs = segmentOffsetMs + point.elapsedMs)
            }
        }.sortedBy { it.elapsedMs }
        return VideoTrack(
            videoFileName = videoFiles.firstOrNull()?.parentFile?.name.orEmpty(),
            startTimeMs = firstStartMs,
            points = mergedPoints
        )
    }

    fun readMergedTrack(context: Context, videoFiles: List<File>): VideoTrack? {
        val tracks = videoFiles.mapNotNull { readTrack(context, it) }.sortedBy { it.startTimeMs }
        if (tracks.isEmpty()) return null
        val firstStartMs = tracks.first().startTimeMs.takeIf { it > 0L }
            ?: tracks.first().points.firstOrNull()?.timeMs
            ?: 0L
        val mergedPoints = tracks.flatMap { track ->
            val segmentOffsetMs = (track.startTimeMs - firstStartMs).coerceAtLeast(0L)
            track.points.map { point ->
                point.copy(elapsedMs = segmentOffsetMs + point.elapsedMs)
            }
        }.sortedBy { it.elapsedMs }
        return VideoTrack(
            videoFileName = videoFiles.firstOrNull()?.parentFile?.name.orEmpty(),
            startTimeMs = firstStartMs,
            points = mergedPoints
        )
    }

    fun readMergedTrack(
        context: Context,
        videoFiles: List<File>,
        offsetsMs: List<Long>,
        durationsMs: List<Long>
    ): VideoTrack? {
        val tracks = videoFiles.mapIndexedNotNull { index, video ->
            readTrack(context, video)?.let { track -> index to track }
        }
        if (tracks.isEmpty()) return null
        val firstTrack = tracks.first().second
        val firstStartMs = firstTrack.startTimeMs.takeIf { it > 0L }
            ?: firstTrack.points.firstOrNull()?.timeMs
            ?: 0L
        val mergedPoints = tracks.flatMap { (index, track) ->
            val segmentOffsetMs = offsetsMs.getOrElse(index) { 0L }
            val durationMs = durationsMs.getOrElse(index) { 0L }
            track.points
                .filter { point -> durationMs <= 0L || point.elapsedMs <= durationMs + SEGMENT_SIDE_CAR_TOLERANCE_MS }
                .map { point -> point.copy(elapsedMs = segmentOffsetMs + point.elapsedMs) }
        }.sortedBy { it.elapsedMs }
        return VideoTrack(
            videoFileName = videoFiles.firstOrNull()?.parentFile?.name.orEmpty(),
            startTimeMs = firstStartMs,
            points = mergedPoints
        )
    }

    private const val SEGMENT_SIDE_CAR_TOLERANCE_MS = 500L

    private fun toJson(track: VideoTrack): JSONObject {
        val points = JSONArray()
        track.points.forEach { point ->
            points.put(
                JSONObject()
                    .put("elapsedMs", point.elapsedMs)
                    .put("timeMs", point.timeMs)
                    .put("latitude", point.latitude)
                    .put("longitude", point.longitude)
                    .put("speedKmh", point.speedKmh)
                    .putNullable("accuracyMeters", point.accuracyMeters)
                    .putNullable("bearingDegrees", point.bearingDegrees)
            )
        }
        return JSONObject()
            .put("version", track.version)
            .put("videoFileName", track.videoFileName)
            .put("startTimeMs", track.startTimeMs)
            .put("points", points)
    }

    private fun fromJson(json: JSONObject): VideoTrack {
        val pointsJson = json.optJSONArray("points") ?: JSONArray()
        val points = mutableListOf<VideoTrackPoint>()
        for (i in 0 until pointsJson.length()) {
            val point = pointsJson.optJSONObject(i) ?: continue
            val lat = point.optDouble("latitude", Double.NaN)
            val lon = point.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            points += VideoTrackPoint(
                elapsedMs = point.optLong("elapsedMs", 0L),
                timeMs = point.optLong("timeMs", 0L),
                latitude = lat,
                longitude = lon,
                speedKmh = point.optDouble("speedKmh", 0.0).toFloat(),
                accuracyMeters = point.optNullableFloat("accuracyMeters"),
                bearingDegrees = point.optNullableFloat("bearingDegrees")
            )
        }
        return VideoTrack(
            version = json.optInt("version", 1),
            videoFileName = json.optString("videoFileName"),
            startTimeMs = json.optLong("startTimeMs", 0L),
            points = points.sortedBy { it.elapsedMs }
        )
    }

    private fun JSONObject.optNullableFloat(key: String): Float? {
        return if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }
}

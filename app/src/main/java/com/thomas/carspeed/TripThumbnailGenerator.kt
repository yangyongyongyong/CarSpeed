package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object TripThumbnailGenerator {
    const val FILE_NAME = "carspeed-trip-thumbnail.jpg"
    const val TARGET_WIDTH = 640
    const val TARGET_HEIGHT = 360
    private const val PREFERRED_FRAME_MS = 60_000L
    private const val JPEG_QUALITY = 86
    private const val TAG = "TripThumbnail"
    private const val EXISTING_WORK_NAME = "car_speed_trip_thumbnails_backfill"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "trip-thumbnail").apply { isDaemon = true }
    }

    fun thumbnailFile(firstVideo: File): File {
        return File(firstVideo.parentFile, FILE_NAME)
    }

    fun frameTimeMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return minOf(PREFERRED_FRAME_MS, durationMs / 2L).coerceAtLeast(0L)
    }

    fun generate(firstVideo: File, overwrite: Boolean = false): File? {
        if (!firstVideo.isFile || firstVideo.length() <= 0L) return null
        val output = thumbnailFile(firstVideo)
        if (!overwrite && output.isFile && output.length() > 0L) return output

        val retriever = MediaMetadataRetriever()
        var frame: Bitmap? = null
        var cropped: Bitmap? = null
        return try {
            retriever.setDataSource(firstVideo.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val requestedUs = frameTimeMs(durationMs) * 1_000L
            frame = retriever.getFrameAtTime(requestedUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(requestedUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.frameAtTime
                ?: return null
            cropped = centerCrop(frame, TARGET_WIDTH, TARGET_HEIGHT)
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { stream ->
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IllegalStateException("JPEG compression failed")
                }
            }
            output.setLastModified(System.currentTimeMillis())
            output
        } catch (error: Exception) {
            Log.w(TAG, "无法生成行程缩略图: ${firstVideo.absolutePath}", error)
            if (output.exists() && output.length() == 0L) output.delete()
            null
        } finally {
            if (cropped != null && cropped !== frame && !cropped.isRecycled) cropped.recycle()
            if (frame != null && !frame.isRecycled) frame.recycle()
            runCatching { retriever.release() }
        }
    }

    fun generateAsync(
        context: Context,
        firstVideo: File,
        overwrite: Boolean = false,
        onComplete: ((File?) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        return try {
            executor.execute {
                val thumbnail = generate(firstVideo, overwrite)
                if (thumbnail != null) {
                    MediaScannerConnection.scanFile(
                        appContext,
                        arrayOf(thumbnail.absolutePath),
                        arrayOf("image/jpeg"),
                        null
                    )
                }
                onComplete?.invoke(thumbnail)
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun enqueueMissing(context: Context) {
        val request = OneTimeWorkRequest.Builder(TripThumbnailBackfillWorker::class.java).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            EXISTING_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    internal fun centerCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width <= 0 || source.height <= 0 || targetWidth <= 0 || targetHeight <= 0) return source
        val scale = maxOf(targetWidth / source.width.toFloat(), targetHeight / source.height.toFloat())
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(targetWidth)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(targetHeight)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        val left = ((scaled.width - targetWidth) / 2).coerceAtLeast(0)
        val top = ((scaled.height - targetHeight) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(
            scaled,
            left,
            top,
            targetWidth.coerceAtMost(scaled.width),
            targetHeight.coerceAtMost(scaled.height)
        ).also {
            if (scaled !== it && !scaled.isRecycled) scaled.recycle()
        }
    }
}

class TripThumbnailBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (!PrivacyConsent.isAgreed(applicationContext)) return Result.success()
        return runCatching {
            VideoStorage.videoTrips(applicationContext).forEach { trip ->
                val firstVideo = trip.firstVideo
                if (!TripThumbnailGenerator.thumbnailFile(firstVideo).isFile) {
                    TripThumbnailGenerator.generate(firstVideo)
                }
            }
            Result.success()
        }.getOrElse { error ->
            Log.w("TripThumbnail", "旧行程缩略图补齐失败", error)
            Result.retry()
        }
    }
}

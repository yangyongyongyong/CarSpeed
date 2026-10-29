package com.thomas.carspeed

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 进行中的行程定期落盘。进程被杀或崩溃后，下次启动可以把它恢复成一条驾驶记录，
 * 或者接着记录。所有读写都排在 [DriveRecordStore.ioExecutor] 上，和保存记录保持先后顺序。
 */
internal object DriveCheckpointStore {
    const val OVERLAY_TRIP = "overlay_trip"
    const val GPS_TRACK = "gps_track"
    private const val TAG = "DriveCheckpointStore"

    class Checkpoint(
        val record: DriveRecord,
        val savedAtMs: Long,
        val meta: JSONObject
    )

    fun saveAsync(context: Context, name: String, record: DriveRecord, meta: JSONObject = JSONObject()) {
        val appContext = context.applicationContext
        val savedAtMs = System.currentTimeMillis()
        DriveRecordStore.ioExecutor.execute {
            try {
                val json = JSONObject()
                    .put("savedAtMs", savedAtMs)
                    .put("meta", meta)
                    .put("record", DriveRecordStore.recordToJson(record))
                val file = file(appContext, name)
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                    tmp.delete()
                }
            } catch (error: Exception) {
                Log.w(TAG, "save checkpoint $name failed", error)
            }
        }
    }

    fun clearAsync(context: Context, name: String) {
        val appContext = context.applicationContext
        DriveRecordStore.ioExecutor.execute { file(appContext, name).delete() }
    }

    /** 在 IO 线程读取断点并删除文件，回调同样在 IO 线程执行。 */
    fun takeAsync(context: Context, name: String, onLoaded: (Checkpoint?) -> Unit) {
        val appContext = context.applicationContext
        DriveRecordStore.ioExecutor.execute {
            val checkpoint = read(appContext, name)
            file(appContext, name).delete()
            onLoaded(checkpoint)
        }
    }

    /** 把上次中断的行程直接存成一条驾驶记录。 */
    fun recoverAsRecordAsync(context: Context, name: String, minDurationMs: Long = 10_000L) {
        val appContext = context.applicationContext
        takeAsync(appContext, name) { checkpoint ->
            val record = checkpoint?.record ?: return@takeAsync
            if (record.durationMs < minDurationMs && record.distanceMeters <= 0f) return@takeAsync
            try {
                DriveRecordStore.addRecord(appContext, record)
            } catch (error: Exception) {
                Log.w(TAG, "recover checkpoint $name failed", error)
            }
        }
    }

    private fun read(context: Context, name: String): Checkpoint? {
        val file = file(context, name)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val recordJson = json.optJSONObject("record") ?: return null
            Checkpoint(
                record = DriveRecordStore.recordFromJson(recordJson),
                savedAtMs = json.optLong("savedAtMs", 0L),
                meta = json.optJSONObject("meta") ?: JSONObject()
            )
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun file(context: Context, name: String): File = File(context.filesDir, "checkpoint_$name.json")
}

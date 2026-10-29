package com.thomas.carspeed

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.amap.api.maps.AMapException
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapProvince
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 定期让高德对比一次已下载的离线地图包，有新版就顺手更新掉。
 *
 * 只在不计费网络（Wi-Fi）下运行，一周一次；App 界面在前台时不抢离线的回调，
 * 让路给 MapRouteView / OfflineMapActivity 自己的 OfflineMapManager。
 */
class OfflineMapUpdateWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params),
    OfflineMapManager.OfflineMapDownloadListener,
    OfflineMapManager.OfflineLoadedListener {

    /** 每查完一个省，高德在主线程回调一次 onCheckUpdate，这里发一个许可。 */
    private val checkSignals = Semaphore(0)

    override fun doWork(): Result {
        val context = applicationContext
        if (!PrivacyConsent.isAgreed(context)) return Result.success()
        // 界面开着时这件事由 MapRouteView / OfflineMapActivity 自己在做，
        // 这里再建一个 OfflineMapManager 只会跟它们抢同一个高德单例。
        // 用 retry 而不是 success，是为了过几小时再看一眼，别把这一周整个跳过去。
        if (CarSpeedApplication.isUiVisible()) return Result.retry()

        var manager: OfflineMapManager? = null
        return try {
            val created = OfflineMapManager(context, this).also { manager = it }
            created.setOnOfflineLoadedListener(this)

            // 这个列表高德已经过滤过，只留 SUCCESS 和 NEW_VERSION 的省
            val downloaded = created.downloadOfflineMapProvinceList.orEmpty()
            if (downloaded.isNotEmpty()) {
                requestUpdates(created, downloaded)
                awaitChecks(downloaded.size)
                awaitDownloads(created)
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "离线地图定期对比失败", t)
            Result.retry()
        } finally {
            runCatching { manager?.destroy() }
        }
    }

    private fun requestUpdates(manager: OfflineMapManager, provinces: List<OfflineMapProvince>) {
        for (province in provinces) {
            try {
                manager.updateOfflineMapProvinceByName(province.provinceName)
            } catch (e: AMapException) {
                Log.w(TAG, "请求对比离线包失败: ${province.provinceName}", e)
            }
        }
    }

    /** 等每个省都回一次 onCheckUpdate；超时就先走下载阶段，不卡死。 */
    private fun awaitChecks(count: Int) {
        val deadline = SystemClock.elapsedRealtime() + CHECK_TIMEOUT_MS
        repeat(count) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            checkSignals.tryAcquire(remaining, TimeUnit.MILLISECONDS)
        }
    }

    /** 有新版时高德会在后台把包拉回来，这里等到下载队列安静下来再收工。 */
    private fun awaitDownloads(manager: OfflineMapManager) {
        val deadline = SystemClock.elapsedRealtime() + DOWNLOAD_TIMEOUT_MS
        var idleTicks = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            val busy = manager.downloadingProvinceList.orEmpty().isNotEmpty() ||
                manager.downloadingCityList.orEmpty().isNotEmpty()
            idleTicks = if (busy) 0 else idleTicks + 1
            if (idleTicks * POLL_INTERVAL_MS >= IDLE_CONFIRM_MS) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    override fun onCheckUpdate(hasNew: Boolean, name: String?) {
        checkSignals.release()
    }

    override fun onDownload(status: Int, completeCode: Int, downName: String?) = Unit

    override fun onRemove(success: Boolean, name: String?, describe: String?) = Unit

    override fun onVerifyComplete() = Unit

    companion object {
        private const val TAG = "OfflineMapUpdate"

        /** WorkManager 单个 Worker 限 10 分钟，两段加起来留足余量。 */
        const val CHECK_TIMEOUT_MS = 2L * 60 * 1000
        const val DOWNLOAD_TIMEOUT_MS = 5L * 60 * 1000
        const val POLL_INTERVAL_MS = 3000L
        const val IDLE_CONFIRM_MS = 15L * 1000

        const val WORK_NAME = "offline_map_update"
        const val INTERVAL_DAYS = 7L

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequest
                .Builder(OfflineMapUpdateWorker::class.java, INTERVAL_DAYS, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

package com.thomas.carspeed

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicInteger

class CarSpeedApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PrivacyConsent.applyToSdk(this)
        OfflineMapUpdateWorker.enqueue(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                keepScreenOnIfDriving(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                visibleActivities.incrementAndGet()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) {
                visibleActivities.decrementAndGet()
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun keepScreenOnIfDriving(activity: Activity) {
        if (activity::class.java in SCREEN_ON_ACTIVITIES) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    companion object {
        private val visibleActivities = AtomicInteger(0)

        /** 界面在前台时不跟页面里的离线地图管理器抢回调，让 Worker 下次再来。 */
        fun isUiVisible(): Boolean = visibleActivities.get() > 0

        private val SCREEN_ON_ACTIVITIES: Set<Class<out Activity>> = setOf(
            CameraRecordActivity::class.java,
            CameraFullscreenActivity::class.java,
            SpeedHudActivity::class.java,
            VideoReplayActivity::class.java,
            RadarQueryActivity::class.java
        )
    }
}

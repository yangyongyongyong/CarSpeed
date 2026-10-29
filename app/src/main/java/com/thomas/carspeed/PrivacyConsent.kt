package com.thomas.carspeed

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.NaviSetting
import com.amap.api.services.core.ServiceSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 高德 SDK 要求用户真正看过并同意隐私政策后才能告知它“已同意”。
 * 未同意前高德相关功能不可用，后台服务应先用 [isAgreed] 判断再碰高德。
 */
object PrivacyConsent {
    private const val PREFS = "privacy_consent"
    private const val KEY_AGREED = "agreed_v1"

    fun isAgreed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AGREED, false)
    }

    fun applyToSdk(context: Context) {
        val appContext = context.applicationContext
        val agreed = isAgreed(appContext)
        runCatching {
            MapsInitializer.updatePrivacyShow(appContext, true, true)
            MapsInitializer.updatePrivacyAgree(appContext, agreed)
        }
        runCatching {
            ServiceSettings.updatePrivacyShow(appContext, true, true)
            ServiceSettings.updatePrivacyAgree(appContext, agreed)
        }
        runCatching {
            AMapLocationClient.updatePrivacyShow(appContext, true, true)
            AMapLocationClient.updatePrivacyAgree(appContext, agreed)
        }
        runCatching {
            NaviSetting.updatePrivacyShow(appContext, true, true)
            NaviSetting.updatePrivacyAgree(appContext, agreed)
        }
    }

    fun ensure(activity: AppCompatActivity, onAgreed: () -> Unit) {
        if (isAgreed(activity)) {
            applyToSdk(activity)
            onAgreed()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_message)
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_agree) { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AGREED, true)
                    .apply()
                applyToSdk(activity)
                if (!activity.isFinishing && !activity.isDestroyed) onAgreed()
            }
            .setNegativeButton(R.string.privacy_disagree) { _, _ ->
                activity.finish()
            }
            .show()
    }
}

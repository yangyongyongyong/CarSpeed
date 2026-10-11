package com.thomas.carspeed

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

object CameraFocus {
    fun isManualEnabled(context: Context): Boolean {
        return context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(DashcamSettings.PREF_MANUAL_FOCUS_ENABLED, false)
    }

    fun percent(context: Context): Int {
        return context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(DashcamSettings.PREF_FOCUS_PERCENT, DashcamSettings.DEFAULT_FOCUS_PERCENT)
            .coerceIn(0, 100)
    }

    fun save(context: Context, manualEnabled: Boolean, percent: Int, commit: Boolean = false) {
        val editor = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DashcamSettings.PREF_MANUAL_FOCUS_ENABLED, manualEnabled)
            .putInt(DashcamSettings.PREF_FOCUS_PERCENT, percent.coerceIn(0, 100))
        if (commit) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun apply(context: Context, camera: Camera?) {
        val activeCamera = camera ?: return
        val options = CaptureRequestOptions.Builder()
        if (isManualEnabled(context)) {
            val maxDistance = maxFocusDistanceDiopters(activeCamera)
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistanceDiopters(context, maxDistance))
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        Camera2CameraControl.from(activeCamera.cameraControl).setCaptureRequestOptions(options.build())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun supportsManualFocus(camera: Camera?): Boolean {
        return maxFocusDistanceDiopters(camera) > 0.01f
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun maxFocusDistanceDiopters(camera: Camera?): Float {
        val activeCamera = camera ?: return 0f
        return Camera2CameraInfo.from(activeCamera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?.coerceAtLeast(0f)
            ?: 0f
    }

    fun focusDistanceDiopters(context: Context, maxFocusDistanceDiopters: Float): Float {
        if (maxFocusDistanceDiopters <= 0f) return 0f
        return maxFocusDistanceDiopters * (percent(context) / 100f)
    }
}

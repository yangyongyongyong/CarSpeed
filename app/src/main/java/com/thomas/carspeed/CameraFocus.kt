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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import java.util.concurrent.TimeUnit

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

    fun focusOnPlate(context: Context, camera: Camera?, frame: PlateOverlayFrame): Boolean {
        val activeCamera = camera ?: return false
        if (isManualEnabled(context)) return false
        val plate = frame.plates.maxByOrNull { plate ->
            (plate.x2 - plate.x1).coerceAtLeast(0f) * (plate.y2 - plate.y1).coerceAtLeast(0f)
        } ?: return false
        val sourceWidth = if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) frame.imageHeight else frame.imageWidth
        val sourceHeight = if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) frame.imageWidth else frame.imageHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        val centerX = ((plate.x1 + plate.x2) / 2f).coerceIn(0f, sourceWidth.toFloat())
        val centerY = ((plate.y1 + plate.y2) / 2f).coerceIn(0f, sourceHeight.toFloat())
        return try {
            val point = SurfaceOrientedMeteringPointFactory(sourceWidth.toFloat(), sourceHeight.toFloat())
                .createPoint(centerX, centerY)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(2, TimeUnit.SECONDS).build()
            activeCamera.cameraControl.startFocusAndMetering(action)
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}

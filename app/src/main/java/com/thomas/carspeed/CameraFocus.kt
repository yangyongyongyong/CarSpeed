package com.thomas.carspeed

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import java.util.Locale
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

object CameraFocus {
    /**
     * 判断是否启用行车记录仪手动超焦距对焦。
     * 默认开启（DEFAULT_MANUAL_FOCUS_ENABLED = true），防止大底主摄在车内被前挡玻璃/中控台吸走焦点。
     */
    fun isManualEnabled(context: Context): Boolean {
        return context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(DashcamSettings.PREF_MANUAL_FOCUS_ENABLED, DashcamSettings.DEFAULT_MANUAL_FOCUS_ENABLED)
    }

    fun stepPercent(current: Int, delta: Int): Int = (current + delta).coerceIn(0, 100)

    /**
     * 读取手动对焦百分比（0..100），默认值为 1%（对应约 14m 超焦距甜点位）。
     */
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
        }
        val info = Camera2CameraInfo.from(activeCamera.cameraInfo)
        val aeRange = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val aeStep = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (aeRange != null && aeStep != null) {
            val telemetry = DashcamService.currentTelemetry()
            val targetEv = DashcamShutterPolicy.targetEvForSpeed(telemetry.speedKmh, telemetry.exposureTimeNs)
            val aeComp = DashcamShutterPolicy.computeAeCompensationSteps(
                targetEv = targetEv,
                stepRatio = aeStep.toDouble(),
                minIndex = aeRange.lower,
                maxIndex = aeRange.upper
            )
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeComp)
        }
        if (isManualEnabled(context)) {
            val maxDistance = maxFocusDistanceDiopters(activeCamera)
            val hyperfocal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE)
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistanceDiopters(context, maxDistance, hyperfocal)
            )
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

    /**
     * 将手动对焦百分比转换为屈光度（Diopters）。
     * 当百分比为 0（无穷远）或 1（默认超焦距档位）时，优先取 0.07D（约 14.3 米，覆盖 5m~∞ 景深），
     * 避免 0.0D 在部分大底传感器上过无穷远，也避免连续自动对焦锁死在中控台。
     */
    fun focusDistanceDiopters(
        context: Context,
        maxFocusDistanceDiopters: Float,
        hyperfocalDistanceDiopters: Float? = null
    ): Float {
        if (maxFocusDistanceDiopters <= 0f) return 0f
        val p = percent(context)
        if (p <= 1) {
            val sweetSpot = ((hyperfocalDistanceDiopters ?: 0.10f) * 0.7f).coerceIn(0.05f, 0.08f)
            return if (p == 0) 0f else sweetSpot.coerceAtMost(maxFocusDistanceDiopters)
        }
        return maxFocusDistanceDiopters * (p / 100f)
    }

    fun maxFocusDistanceDiopters(context: Context): Float? {
        cachedMaxDiopters?.let { return it }
        val value = readMaxFocusDistanceDiopters(context) ?: return null
        cachedMaxDiopters = value
        return value
    }

    fun distanceLabel(percent: Int, maxDiopters: Float?, infinityLabel: String): String {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == 0) return infinityLabel
        val max = maxDiopters ?: return UNKNOWN_DISTANCE
        if (max <= 0f) return UNKNOWN_DISTANCE
        val diopters = if (clamped == 1 && max >= 5f) {
            0.07f
        } else {
            max * (clamped / 100f)
        }
        if (diopters <= 0.01f) return infinityLabel
        val meters = 1f / diopters
        return when {
            meters < 1f -> String.format(Locale.US, "%.0fcm", meters * 100f)
            meters >= 10f -> String.format(Locale.US, "%.0fm", meters)
            else -> String.format(Locale.US, "%.1fm", meters)
        }
    }

    private fun readMaxFocusDistanceDiopters(context: Context): Float? {
        return runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = CameraCapabilities.selectPrimaryBackCameraId(manager) ?: return null
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.coerceAtLeast(0f)
        }.getOrNull()
    }

    private const val UNKNOWN_DISTANCE = "—"
    @Volatile private var cachedMaxDiopters: Float? = null
}

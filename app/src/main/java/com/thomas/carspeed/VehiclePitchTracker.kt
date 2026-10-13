package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * 用加速度计和陀螺仪估计车头俯仰角。
 *
 * 手机在车上的安装方向不需要预设。加速时，线性加速度指向车头，用来学习车头在手机坐标系里的方向。
 * 俯仰角是车头轴和水平面的夹角，抬头为正。陀螺仪负责积分，只在匀速且加速度接近重力时才用加速度计校正，
 * 避免刹车把坡度“伪装”出来。
 *
 * 时间戳使用 [android.os.SystemClock.elapsedRealtime] 同一时间基。
 */
internal class VehiclePitchTracker {
    private var upX = 0f
    private var upY = 0f
    private var upZ = 1f
    private var upReady = false

    private var forwardX = 0f
    private var forwardY = 0f
    private var forwardZ = 0f
    private var forwardSamples = 0
    private var forwardKnown = false

    private var lastTimeMs: Long? = null
    private var lastPitchDegrees: Float? = null

    fun update(sample: ImuSample): PitchEstimate {
        val accelMag = hypot3(sample.ax, sample.ay, sample.az)
        if (!upReady) {
            if (accelMag in MIN_GRAVITY_MAG..MAX_GRAVITY_MAG) {
                upX = sample.ax / accelMag
                upY = sample.ay / accelMag
                upZ = sample.az / accelMag
                upReady = true
            }
            lastTimeMs = sample.timeMs
            return PitchEstimate(pitchDegrees = null, forwardKnown = false, phoneMoved = false)
        }

        val previousTime = lastTimeMs
        lastTimeMs = sample.timeMs
        val dt = if (previousTime == null) 0f else (sample.timeMs - previousTime) / 1_000f
        if (dt in MIN_DT_SECONDS..MAX_DT_SECONDS) {
            integrateGyro(sample.gx, sample.gy, sample.gz, dt)
        }

        val gyroMag = hypot3(sample.gx, sample.gy, sample.gz)
        val pitch = currentPitchDegrees()
        val pitchStep = if (pitch != null && lastPitchDegrees != null) abs(pitch - lastPitchDegrees!!) else 0f
        if (gyroMag >= PHONE_MOVED_GYRO_RAD_S || pitchStep >= PHONE_MOVED_PITCH_STEP_DEG) {
            resetForward()
            if (accelMag in MIN_GRAVITY_MAG..MAX_GRAVITY_MAG) {
                upX = sample.ax / accelMag
                upY = sample.ay / accelMag
                upZ = sample.az / accelMag
            }
            lastPitchDegrees = null
            return PitchEstimate(pitchDegrees = null, forwardKnown = false, phoneMoved = true)
        }

        val alongUp = sample.ax * upX + sample.ay * upY + sample.az * upZ
        val horizontalAccel = hypot3(
            sample.ax - upX * alongUp,
            sample.ay - upY * alongUp,
            sample.az - upZ * alongUp
        )
        if (!sample.speedIncreasing && !sample.speedBraking &&
            abs(accelMag - GRAVITY_MPS2) <= COAST_ACCEL_TOLERANCE_MPS2 &&
            horizontalAccel <= MAX_COAST_HORIZONTAL_MPS2
        ) {
            val measuredX = sample.ax / accelMag
            val measuredY = sample.ay / accelMag
            val measuredZ = sample.az / accelMag
            upX = (1f - CORRECTION_ALPHA) * upX + CORRECTION_ALPHA * measuredX
            upY = (1f - CORRECTION_ALPHA) * upY + CORRECTION_ALPHA * measuredY
            upZ = (1f - CORRECTION_ALPHA) * upZ + CORRECTION_ALPHA * measuredZ
            normalizeUp()
        }

        if (sample.speedIncreasing && !forwardKnown) {
            learnForward(sample.ax, sample.ay, sample.az)
        }

        val stablePitch = currentPitchDegrees()
        lastPitchDegrees = stablePitch
        return PitchEstimate(
            pitchDegrees = stablePitch,
            forwardKnown = forwardKnown,
            phoneMoved = false
        )
    }

    private fun learnForward(ax: Float, ay: Float, az: Float) {
        val alongUp = ax * upX + ay * upY + az * upZ
        var hx = ax - upX * alongUp
        var hy = ay - upY * alongUp
        var hz = az - upZ * alongUp
        val mag = hypot3(hx, hy, hz)
        if (mag < MIN_FORWARD_ACCEL_MPS2) return
        hx /= mag
        hy /= mag
        hz /= mag
        if (forwardSamples == 0) {
            forwardX = hx
            forwardY = hy
            forwardZ = hz
            forwardSamples = 1
            return
        }
        val agreement = hx * forwardX + hy * forwardY + hz * forwardZ
        if (agreement < FORWARD_AGREEMENT) {
            forwardX = hx
            forwardY = hy
            forwardZ = hz
            forwardSamples = 1
            forwardKnown = false
            return
        }
        val weight = forwardSamples.toFloat()
        forwardX = forwardX * weight + hx
        forwardY = forwardY * weight + hy
        forwardZ = forwardZ * weight + hz
        forwardSamples += 1
        val forwardMag = hypot3(forwardX, forwardY, forwardZ)
        if (forwardMag > 0f) {
            forwardX /= forwardMag
            forwardY /= forwardMag
            forwardZ /= forwardMag
        }
        if (forwardSamples >= MIN_FORWARD_SAMPLES) forwardKnown = true
    }

    private fun currentPitchDegrees(): Float? {
        if (!forwardKnown) return null
        val dot = (forwardX * upX + forwardY * upY + forwardZ * upZ).coerceIn(-1f, 1f)
        return Math.toDegrees(asin(dot.toDouble())).toFloat()
    }

    private fun integrateGyro(gx: Float, gy: Float, gz: Float, dt: Float) {
        val cx = gy * upZ - gz * upY
        val cy = gz * upX - gx * upZ
        val cz = gx * upY - gy * upX
        upX -= cx * dt
        upY -= cy * dt
        upZ -= cz * dt
        normalizeUp()
    }

    private fun normalizeUp() {
        val mag = hypot3(upX, upY, upZ)
        if (mag <= 0f) return
        upX /= mag
        upY /= mag
        upZ /= mag
    }

    private fun resetForward() {
        forwardX = 0f
        forwardY = 0f
        forwardZ = 0f
        forwardSamples = 0
        forwardKnown = false
    }

    companion object {
        const val GRAVITY_MPS2 = 9.80665f
        private const val MIN_GRAVITY_MAG = 7f
        private const val MAX_GRAVITY_MAG = 13f
        private const val MIN_DT_SECONDS = 0.001f
        private const val MAX_DT_SECONDS = 0.25f
        private const val COAST_ACCEL_TOLERANCE_MPS2 = 1.5f
        private const val MAX_COAST_HORIZONTAL_MPS2 = 0.35f
        private const val CORRECTION_ALPHA = 0.02f
        private const val MIN_FORWARD_ACCEL_MPS2 = 0.6f
        private const val FORWARD_AGREEMENT = 0.5f
        private const val MIN_FORWARD_SAMPLES = 5
        private const val PHONE_MOVED_GYRO_RAD_S = 2.5f
        private const val PHONE_MOVED_PITCH_STEP_DEG = 8f

        private fun hypot3(x: Float, y: Float, z: Float): Float {
            return sqrt(x * x + y * y + z * z)
        }
    }
}

internal data class ImuSample(
    val timeMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val speedIncreasing: Boolean = false,
    val speedBraking: Boolean = false
)

internal data class PitchEstimate(
    val pitchDegrees: Float?,
    val forwardKnown: Boolean,
    val phoneMoved: Boolean
)

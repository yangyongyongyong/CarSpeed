package com.thomas.carspeed

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import androidx.camera.video.Quality

data class VideoMode(
    val qualityKey: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val source: String = CameraCapabilities.SOURCE_ENCODER_PROFILE
) {
    val label: String
        get() = "${resolutionLabel()} ${width}x$height · ${fps}fps"

    private fun resolutionLabel(): String {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return when {
            longEdge >= 7600 && shortEdge >= 4200 -> "8K"
            longEdge >= 3800 && shortEdge >= 2100 -> "4K"
            longEdge >= 2500 && shortEdge >= 1400 -> "1440p"
            longEdge >= 1900 && shortEdge >= 1000 -> "1080p"
            longEdge >= 1200 && shortEdge >= 700 -> "720p"
            longEdge >= 700 && shortEdge >= 400 -> "480p"
            else -> "Video"
        }
    }
}

object CameraCapabilities {
    const val QUALITY_8K = "8K"
    const val QUALITY_UHD = "UHD"
    const val QUALITY_FHD = "FHD"
    const val QUALITY_HD = "HD"
    const val QUALITY_SD = "SD"
    const val SOURCE_ENCODER_PROFILE = "encoder_profile"
    const val SOURCE_CAMERA2_OUTPUT = "camera2_output"

    private data class ProfileQuery(val fallbackKey: String, val profileQuality: Int)

    fun supportedVideoModes(context: Context): List<VideoMode> {
        val cameraId = backCameraId(context) ?: return fallbackModes()
        val numericCameraId = cameraId.toIntOrNull()
        val profileModes = queryProfileModes(cameraId, numericCameraId)
        val camera2OutputModes = queryMediaRecorderOutputModes(context, cameraId, profileModes)
        val baseModes = profileModes + camera2OutputModes
        val highSpeedModes = queryHighSpeedModes(context, cameraId, baseModes)
        return (baseModes + highSpeedModes)
            .distinctBy { "${it.width}-${it.height}-${it.fps}" }
            .sortedWith(compareByDescending<VideoMode> { it.width * it.height }.thenByDescending { it.fps })
            .ifEmpty { fallbackModes() }
    }

    fun selectedVideoMode(context: Context): VideoMode {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val selectedKey = prefs.getString(DashcamSettings.PREF_VIDEO_QUALITY, QUALITY_FHD) ?: QUALITY_FHD
        val selectedWidth = prefs.getInt(DashcamSettings.PREF_VIDEO_WIDTH, 0)
        val selectedHeight = prefs.getInt(DashcamSettings.PREF_VIDEO_HEIGHT, 0)
        val selectedFps = prefs.getInt(DashcamSettings.PREF_VIDEO_FPS, 30)
        val modes = supportedVideoModes(context)
        return modes.firstOrNull {
            it.width == selectedWidth && it.height == selectedHeight && it.fps == selectedFps
        }
            ?: modes.firstOrNull { it.qualityKey == selectedKey && it.fps == selectedFps }
            ?: modes.firstOrNull { it.qualityKey == selectedKey }
            ?: modes.first()
    }

    fun toCameraXQuality(mode: VideoMode): Quality {
        if (mode.width.toLong() * mode.height.toLong() > 3840L * 2160L) return Quality.HIGHEST
        return when (mode.qualityKey) {
            QUALITY_UHD -> Quality.UHD
            QUALITY_FHD -> Quality.FHD
            QUALITY_HD -> Quality.HD
            QUALITY_SD -> Quality.SD
            else -> closestCameraXQuality(mode)
        }
    }

    fun debugModes(context: Context): List<VideoMode> = supportedVideoModes(context)

    private fun backCameraId(context: Context): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.firstOrNull { id ->
            val chars = manager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun profileQueries(): List<ProfileQuery> {
        return buildList {
            add(ProfileQuery("HIGH", CamcorderProfile.QUALITY_HIGH))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(ProfileQuery(QUALITY_8K, CamcorderProfile.QUALITY_8KUHD))
            }
            add(ProfileQuery(QUALITY_UHD, CamcorderProfile.QUALITY_2160P))
            add(ProfileQuery(QUALITY_FHD, CamcorderProfile.QUALITY_1080P))
            add(ProfileQuery(QUALITY_HD, CamcorderProfile.QUALITY_720P))
            add(ProfileQuery(QUALITY_SD, CamcorderProfile.QUALITY_480P))
        }
    }

    private fun queryProfileModes(cameraId: String, numericCameraId: Int?): List<VideoMode> {
        val result = mutableListOf<VideoMode>()
        profileQueries().forEach { query ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val profiles = CamcorderProfile.getAll(cameraId, query.profileQuality) ?: return@forEach
                profiles.videoProfiles.forEach { video ->
                    result += VideoMode(
                        qualityKey = qualityKeyFor(video.width, video.height, query.fallbackKey),
                        width = video.width,
                        height = video.height,
                        fps = video.frameRate,
                        source = SOURCE_ENCODER_PROFILE
                    )
                }
            } else {
                if (numericCameraId == null) return@forEach
                if (!CamcorderProfile.hasProfile(numericCameraId, query.profileQuality)) return@forEach
                @Suppress("DEPRECATION")
                val profile = CamcorderProfile.get(numericCameraId, query.profileQuality)
                result += VideoMode(
                    qualityKey = qualityKeyFor(profile.videoFrameWidth, profile.videoFrameHeight, query.fallbackKey),
                    width = profile.videoFrameWidth,
                    height = profile.videoFrameHeight,
                    fps = profile.videoFrameRate,
                    source = SOURCE_ENCODER_PROFILE
                )
            }
        }
        return result
    }

    private fun queryMediaRecorderOutputModes(
        context: Context,
        cameraId: String,
        profileModes: List<VideoMode>
    ): List<VideoMode> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val fpsBySize = profileModes.associateBy({ "${it.width}x${it.height}" }, { it.fps })
        val profileSizes = profileModes.map { "${it.width}x${it.height}" }.toSet()
        return map.getOutputSizes(MediaRecorder::class.java)
            ?.filter { size -> size.width >= 640 && size.height >= 360 }
            ?.filter { size -> isVideoAspect(size.width, size.height) }
            ?.filter { size -> "${size.width}x${size.height}" !in profileSizes }
            ?.map { size ->
                VideoMode(
                    qualityKey = camera2QualityKey(size.width, size.height),
                    width = size.width,
                    height = size.height,
                    fps = fpsBySize["${size.width}x${size.height}"] ?: 30,
                    source = SOURCE_CAMERA2_OUTPUT
                )
            }
            ?.sortedByDescending { it.width * it.height }
            ?.take(16)
            .orEmpty()
    }

    private fun queryHighSpeedModes(context: Context, cameraId: String, profileModes: List<VideoMode>): List<VideoMode> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return profileModes.flatMap { mode ->
            val size = Size(mode.width, mode.height)
            try {
                map.getHighSpeedVideoFpsRangesFor(size)
                    .mapNotNull { range -> range.upper.takeIf { it > mode.fps } }
                    .map { fps -> mode.copy(fps = fps) }
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
    }

    private fun fallbackModes(): List<VideoMode> {
        return listOf(
            VideoMode(QUALITY_FHD, 1920, 1080, 30),
            VideoMode(QUALITY_HD, 1280, 720, 30),
            VideoMode(QUALITY_SD, 720, 480, 30)
        )
    }

    private fun qualityKeyFor(width: Int, height: Int, fallback: String): String {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return when {
            longEdge >= 7600 && shortEdge >= 4200 -> QUALITY_8K
            longEdge >= 3800 && shortEdge >= 2100 -> QUALITY_UHD
            longEdge >= 1900 && shortEdge >= 1000 -> QUALITY_FHD
            longEdge >= 1200 && shortEdge >= 700 -> QUALITY_HD
            longEdge >= 700 && shortEdge >= 400 -> QUALITY_SD
            else -> "${fallback}_${width}x$height"
        }
    }

    private fun closestCameraXQuality(mode: VideoMode): Quality {
        val longEdge = maxOf(mode.width, mode.height)
        val shortEdge = minOf(mode.width, mode.height)
        return when {
            longEdge >= 3800 && shortEdge >= 2100 -> Quality.UHD
            longEdge >= 1900 && shortEdge >= 1000 -> Quality.FHD
            longEdge >= 1200 && shortEdge >= 700 -> Quality.HD
            else -> Quality.SD
        }
    }

    private fun camera2QualityKey(width: Int, height: Int): String {
        return "CAMERA2_${width}x$height"
    }

    private fun isVideoAspect(width: Int, height: Int): Boolean {
        val longEdge = maxOf(width, height).toDouble()
        val shortEdge = minOf(width, height).toDouble().coerceAtLeast(1.0)
        val ratio = longEdge / shortEdge
        return ratio in 1.70..1.90
    }
}

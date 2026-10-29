package com.thomas.carspeed

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DashcamTelemetry(
    val speedKmh: Float = 0f,
    val stationaryWaitMs: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val satelliteCount: Int? = null,
    val address: String? = null,
    val focusDistanceDiopters: Float? = null,
    val autofocusState: String? = null
)

data class DashcamInfoBarText(
    val title: String,
    val label: String,
    val parameters: String,
    val caption: String
) {
    /** 四个位置都空时整条白栏不占位，等于把所有字幕开关都关了。 */
    fun isEmpty(): Boolean =
        title.isBlank() && label.isBlank() && parameters.isBlank() && caption.isBlank()
}

/**
 * 对外取数据的外壳。内容怎么拼全在 [SubtitleContent] 里，
 * 这里只负责读 Context 和全局单例，然后用 [SubtitleContent.Facts] 调过去。
 *
 * 两条路径必须走同一个 [SubtitleContent]。以前各写一遍，改了一边漏另一边，
 * 结果就是同一个开关在预览和 SRT 字幕里表现不一样。
 */
object DashcamOverlayText {
    /** 白栏一行放不下太多字符，日期用点分更紧凑。 */
    private const val PATTERN_INFO_BAR = "yyyy.MM.dd HH:mm:ss"
    /** SRT 字幕是给人读的独立文案，用标准格式。 */
    private const val PATTERN_LINES = "yyyy-MM-dd HH:mm:ss"

    /** 白栏空间窄，用短一点的占位文案；SRT 字幕沿用历史的「位置解析中」。 */
    private const val INFO_BAR_ADDRESS_PENDING = "等待定位"

    fun infoBar(
        context: Context,
        mode: VideoMode,
        forceTime: Boolean = false,
        timeMs: Long? = null
    ): DashcamInfoBarText {
        val settings = DashcamSettings.load(context)
        return SubtitleContent.infoBar(
            facts(context, settings, mode, forceTime, timeMs, PATTERN_INFO_BAR, placeholdersWhenMissing = false)
                .copy(addressPendingText = INFO_BAR_ADDRESS_PENDING)
        )
    }

    fun text(
        context: Context,
        mode: VideoMode = CameraCapabilities.selectedVideoMode(context),
        forceTime: Boolean = false
    ): String {
        return lines(context, mode, forceTime).joinToString("\n").ifBlank { mode.label }
    }

    fun lines(
        context: Context,
        mode: VideoMode = CameraCapabilities.selectedVideoMode(context),
        forceTime: Boolean = false,
        timeMs: Long? = null
    ): List<String> {
        val settings = DashcamSettings.load(context)
        // SRT 里一行一项，缺值必须写明占位，否则用户会以为功能被删了。
        return SubtitleContent.lines(
            facts(context, settings, mode, forceTime, timeMs, PATTERN_LINES, placeholdersWhenMissing = true)
        )
    }

    /** 把 Context 和全局单例读成纯数据。所有 Android 依赖都收在这一处。 */
    private fun facts(
        context: Context,
        settings: WatermarkSettings,
        mode: VideoMode,
        forceTime: Boolean,
        timeMs: Long?,
        timePattern: String,
        placeholdersWhenMissing: Boolean
    ): SubtitleContent.Facts {
        val telemetry = DashcamService.currentTelemetry()
        val showTime = forceTime || settings.showTime
        return SubtitleContent.Facts(
            fields = settings.toSubtitleFields(),
            timeText = if (showTime) {
                SimpleDateFormat(timePattern, Locale.getDefault())
                    .format(Date(timeMs ?: System.currentTimeMillis()))
            } else {
                null
            },
            speedKmh = telemetry.speedKmh,
            stationaryWaitMs = telemetry.stationaryWaitMs,
            latitude = telemetry.latitude,
            longitude = telemetry.longitude,
            satelliteCount = telemetry.satelliteCount,
            address = telemetry.address,
            roadLayer = if (settings.showRoadLayer) RoadLayerEngine.subtitleLabel() else null,
            radarLimit = if (settings.showRadarSummary) DashcamService.radarLimitSummary() else null,
            videoModeLabel = mode.label,
            fps = mode.fps,
            hdrActive = DashcamService.isHdrActive(),
            manualFocus = CameraFocus.isManualEnabled(context),
            focusDistanceDiopters = telemetry.focusDistanceDiopters,
            autofocusState = telemetry.autofocusState,
            placeholdersWhenMissing = placeholdersWhenMissing,
            addressPendingText = context.getString(R.string.watermark_address_pending)
        )
    }

    fun formatStationaryWait(waitDurationMs: Long): String = SubtitleContent.stationaryWaitText(waitDurationMs)
}

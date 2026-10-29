package com.thomas.carspeed

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 雷达预警留档。
 *
 * 真要查「播报位置和现场雷达对不上」时，logcat 早被冲掉了，得有一份能事后拿出来的记录。
 * 这里分成两个文件：
 * - `radar-alert.csv`：一条预警一行，表头一次，Excel 直接能开。
 * - `radar-alert-log.txt`：和 logcat 一模一样的一行文本，方便直接贴出来聊。
 *
 * 每记录一条就落一次盘：预警一秒最多一条，量很小，没必要攒着；
 * 万一 app 被系统杀掉，最后那几条才是用户正在抱怨的那几条。
 */
internal object RadarAlertTrace {
    private const val ALERT_CSV = "radar-alert.csv"
    private const val ALERT_TEXT = "radar-alert-log.txt"
    internal const val MAX_BYTES = 2 * 1024 * 1024L
    internal const val HEADER = "时间,事件,播报,触发秒,提前秒,原始ETA,采用ETA,速度km/h," +
        "速度来源,高德距离m,实算距离m,车纬度,车经度,车方向,道路方向,精度m,定位年龄ms," +
        "雷达纬度,雷达经度,高德原始纬度,高德原始经度,修正偏移m,类型,限速," +
        "道路层级,默认层级,分歧修正,坐标来源,key,文案"

    fun record(context: Context, event: RadarAlertLog.Event) {
        appendLine(context, ALERT_TEXT, RadarAlertLog.line(event))
        ensureHeader(context)
        appendLine(context, ALERT_CSV, csv(event))
    }

    fun clear(context: Context) {
        file(context, ALERT_CSV).delete()
        file(context, ALERT_TEXT).delete()
    }

    /** 给设置页显示用：留档写到哪、多大。 */
    fun summary(context: Context): String {
        val csvFile = file(context, ALERT_CSV)
        val textFile = file(context, ALERT_TEXT)
        if (!csvFile.exists() && !textFile.exists()) return "还没有留档"
        val bytes = csvFile.length() + textFile.length()
        return "留档 ${csvFile.name} / ${textFile.name}，共 ${bytes / 1024} KB，在 app 私有目录"
    }

    /**
     * 按 CSV 规则把一行拆成单元格（认引号，引号内的逗号不算分隔）。
     * 测试用它核对表头和数据的列数，留档本身不读回文件。
     */
    internal fun parseCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        cells += current.toString()
        return cells
    }

    internal fun csvRow(event: RadarAlertLog.Event): String = csv(event)

    private fun csv(event: RadarAlertLog.Event): String {
        return Csv.row(
            listOf(
                RadarAlertLog.wallText(event.timeMs),
                event.kind,
                event.speech,
                event.thresholdSeconds?.toString().orEmpty(),
                event.leadSeconds.toString(),
                event.rawEtaSeconds?.toString().orEmpty(),
                event.etaSeconds?.toString().orEmpty(),
                RadarAlertLog.formatDecimal(event.speedKmh),
                event.speedSource,
                event.amapDistanceMeters?.toString().orEmpty(),
                RadarAlertLog.distanceMeters(
                    event.carLatitude,
                    event.carLongitude,
                    event.radarLatitude,
                    event.radarLongitude
                )?.toString().orEmpty(),
                RadarAlertLog.formatCoordDecimal(event.carLatitude),
                RadarAlertLog.formatCoordDecimal(event.carLongitude),
                RadarAlertLog.formatDecimal(event.carBearing),
                RadarAlertLog.formatDecimal(event.roadBearing),
                RadarAlertLog.formatDecimal(event.carAccuracyMeters),
                event.carAgeMs.toString(),
                RadarAlertLog.formatCoordDecimal(event.radarLatitude),
                RadarAlertLog.formatCoordDecimal(event.radarLongitude),
                RadarAlertLog.formatCoordDecimal(event.reportedRadarLatitude),
                RadarAlertLog.formatCoordDecimal(event.reportedRadarLongitude),
                RadarAlertLog.distanceMeters(
                    event.radarLatitude,
                    event.radarLongitude,
                    event.reportedRadarLatitude,
                    event.reportedRadarLongitude
                )?.toString().orEmpty(),
                event.cameraType.toString(),
                event.speedLimit?.toString().orEmpty(),
                event.roadLayer,
                event.usingDefault.toString(),
                event.correctedByDivergence.toString(),
                event.coordinateFix,
                event.cameraKey,
                event.text
            )
        )
    }

    private fun ensureHeader(context: Context) {
        val target = file(context, ALERT_CSV)
        if (target.exists() && target.length() > 0L) return
        appendLine(context, ALERT_CSV, HEADER)
    }

    private fun appendLine(context: Context, name: String, line: String) {
        val target = file(context, name)
        trimIfOversized(target, line.length)
        runCatching {
            target.parentFile?.mkdirs()
            target.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    /**
     * 留档超上限就从头砍掉一半，保留最近的一半。
     * 只留最近的比留最开始的有用：用户抱怨的一定是刚发生的那次。
     */
    private fun trimIfOversized(target: File, incomingBytes: Int) {
        if (!target.exists()) return
        if (target.length() + incomingBytes <= MAX_BYTES) return
        val lines = runCatching { target.readLines(Charsets.UTF_8) }.getOrNull() ?: return
        val kept = lines.takeLast((lines.size / 2).coerceAtLeast(1))
        runCatching { target.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8) }
    }

    private fun file(context: Context, name: String): File = File(context.filesDir, name)
}

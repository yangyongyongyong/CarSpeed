package com.thomas.carspeed

/**
 * 字幕内容的唯一来源。
 *
 * 以前预览/照片走 [DashcamOverlayText.infoBar]，SRT 字幕走 [DashcamOverlayText.lines]，
 * 两份实现各写一遍，改了一边忘另一边就会出现「同一个开关在两个地方表现不同」——
 * 经纬度被地址顶掉就是这么来的。
 *
 * 这里只做纯数据到文本的转换：不碰 Context、不读全局单例，因此可以穷举测试。
 * 需要系统信息的部分（地址、道路图层、对焦、是否 HDR、时间）由调用方查好后传进来。
 */
internal object SubtitleContent {
    private const val SEPARATOR = "  "
    /** 硬断行：让经纬度独占一行，见 [SubtitleLineWrap]。 */
    private const val HARD_BREAK = "\n"
    /**
     * 坐标小数位：预览和 SRT 统一 5 位（约 1 米精度，行车记录够用）。
     * 以前白栏 5 位、SRT 6 位，同一趟录像两处对不上，纯粹给自己找麻烦。
     */
    private const val COORDINATE_DECIMALS = 5

    /**
     * 生成字幕时用到的外部事实，全部由调用方查好后传进来。
     *
     * @param timeText 已格式化好的时间；null 表示这一项不显示（开关关了）。
     * @param placeholdersWhenMissing SRT 里一行一项，缺值要写明「GPS --」；白栏空间窄，
     *   缺值直接不占位，否则会把自己挤成满屏占位符。这是两条路径唯一的正当差异。
     * @param addressPendingText 地址还没解析出来时的占位文案。白栏和 SRT 历史上用词不同，
     *   这里显式传，不靠两条路径各写一遍来「碰巧保持一致」。
     */
    data class Facts(
        val fields: ShareSubtitleFields = ShareSubtitleFields(),
        val timeText: String? = null,
        val speedKmh: Float = 0f,
        val stationaryWaitMs: Long = 0L,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val satelliteCount: Int? = null,
        val address: String? = null,
        val roadLayer: String? = null,
        val radarLimit: String? = null,
        /** 完整分辨率描述，例如 "1080p 1920x1080 · 30fps"。白栏只用它开头那一段。 */
        val videoModeLabel: String = "",
        val fps: Int = 0,
        val hdrActive: Boolean = false,
        val manualFocus: Boolean = false,
        val focusDistanceDiopters: Float? = null,
        val autofocusState: String? = null,
        val placeholdersWhenMissing: Boolean = false,
        val addressPendingText: String = ADDRESS_PENDING
    )

    /**
     * 白栏：左边速度，中间参数，右边时间/地址/经纬度。
     * 每一块是否出现只看对应的开关，任何一块都不会因为另一个开关而被顶掉。
     */
    fun infoBar(facts: Facts): DashcamInfoBarText {
        val fields = facts.fields
        val parameters = buildList {
            if (fields.showVideoMode) {
                // 白栏里只用分辨率那一段（"1080p"），完整描述留给 SRT 的一行。
                add("${facts.videoModeLabel.substringBefore(' ')}${SEPARATOR}${facts.fps}fps")
                if (facts.hdrActive) add("HDR")
            }
            if (fields.showFocus) add(if (facts.manualFocus) "MF" else "AF")
            if (fields.showRadarSummary) facts.radarLimit?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (fields.showStationaryWait) {
                // 停留为 0 也照常显示，否则同一段录像前后几秒会忽隐忽现。
                add(stationaryWaitText(facts.stationaryWaitMs).replace("静止等待：", "停留 "))
            }
            if (fields.showSatellites) {
                val count = facts.satelliteCount
                if (count != null) {
                    add("卫星 $count")
                } else if (facts.placeholdersWhenMissing) {
                    add("卫星 --")
                }
            }
        }
        val caption = buildList {
            facts.timeText?.let { add(it) }
            val address = visibleAddress(facts)
            if (address != null) {
                add(PlaceSubtitle.withLayer(address, facts.roadLayer.takeIf { fields.showRoadLayer })!!)
            }
            if (address == null && fields.showAddress && !fields.showGps) add(facts.addressPendingText)
            if (address == null && facts.roadLayer != null && fields.showRoadLayer) add(facts.roadLayer)
            // 经纬度放最后并独占一行：它是一对必须成对读的坐标，
            // 跟时间地址挤在一起只会被拆成「前半截在上行、后半截在下行」。
            // 放在末行还有个好处——真到了三行上限，被省略号收掉的是它，不会把地址截掉。
            if (fields.showGps) add(HARD_BREAK + coordinatesText(facts.latitude, facts.longitude))
        }
        return DashcamInfoBarText(
            // 速度开关关掉时左侧整块留空，不拿品牌名占位，否则看起来像没生效。
            title = if (fields.showSpeed) String.format(java.util.Locale.US, "%.1f", facts.speedKmh) else "",
            label = if (fields.showSpeed) "km/h" else "",
            parameters = parameters.joinToString(SEPARATOR),
            caption = joinCaption(caption)
        )
    }

    /**
     * 拼 caption：硬换行前后不加分隔空格。
     * 否则经纬度那一行的行首会多出两个空格，右对齐时看着像没对齐。
     */
    private fun joinCaption(parts: List<String>): String {
        val result = StringBuilder()
        for (part in parts) {
            val ownLine = part.startsWith(HARD_BREAK)
            val text = if (ownLine) part.removePrefix(HARD_BREAK) else part
            if (ownLine) {
                result.append(HARD_BREAK)
            } else if (result.isNotEmpty()) {
                result.append(SEPARATOR)
            }
            result.append(text)
        }
        return result.toString()
    }

    /**
     * SRT 字幕：一行一项，顺序固定。
     * 每一项的出现条件必须和 [infoBar] 完全一致，否则录像里的字幕和画面上的白栏会对不上。
     */
    fun lines(facts: Facts): List<String> {
        val fields = facts.fields
        val result = mutableListOf<String>()
        facts.timeText?.let { result += it }
        if (fields.showSpeed) result += String.format(java.util.Locale.US, "%.1f km/h", facts.speedKmh)
        if (fields.showStationaryWait) result += stationaryWaitText(facts.stationaryWaitMs)
        if (fields.showGps) {
            result += coordinatesText(facts.latitude, facts.longitude)
        }
        if (fields.showSatellites) {
            val count = facts.satelliteCount
            result += if (count != null) "GPS卫星：$count" else "GPS卫星：--"
        }
        if (fields.showRadarSummary) facts.radarLimit?.takeIf { it.isNotBlank() }?.let { result += it }
        val address = visibleAddress(facts)
        if (address != null) {
            result += PlaceSubtitle.withLayer(address, facts.roadLayer.takeIf { fields.showRoadLayer })
                ?: address
        } else if (fields.showAddress) {
            result += facts.addressPendingText
        }
        if (fields.showRoadLayer && !fields.showAddress && address == null) {
            facts.roadLayer?.takeIf { it.isNotBlank() }?.let { result += it }
        }
        if (fields.showFocus) result += focusText(facts)
        if (fields.showVideoMode) {
            result += facts.videoModeLabel + if (facts.hdrActive) " · HDR" else ""
        }
        return result
    }

    internal const val ADDRESS_PENDING = "位置解析中"

    /** 地址只有"开关打开且确实拿到"时才显示；空白串当没拿到。 */
    private fun visibleAddress(facts: Facts): String? =
        facts.address?.takeIf { facts.fields.showAddress && it.isNotBlank() }

    /**
     * 经纬度的唯一格式。两条路径都保留「GPS --」占位：没有定位时必须看得出是「还没定位到」，
     * 直接不显示的话用户会以为经纬度功能被删了。
     *
     * 带北纬/南纬、东经/西经和 N/S/E/W，光看两个数字分不清哪一半是纬度、正负又在哪个半球。
     * 中间用一个普通空格连接：这样它在换行时是**一个不可拆的片段**，不会被劈成两半。
     */
    /**
     * 经纬度的唯一格式。两条路径都保留「GPS --」占位：没有定位时必须看得出是「还没定位到」，
     * 直接不显示的话用户会以为经纬度功能被删了。
     *
     * 只留 N/S/E/W 字母，不再重复写「北纬/东经」——两者是一个意思，都写上既啰嗦又白占宽度。
     * 两个半段之间用单个空格连接，这样它在换行时是**一整个不可拆的片段**。
     */
    fun coordinatesText(latitude: Double?, longitude: Double?): String {
        if (latitude == null || longitude == null) return "GPS --"
        return "${axisText(latitude, 'N', 'S')} " +
            axisText(longitude, 'E', 'W')
    }

    /** 负半轴换成对应字母并取绝对值，否则会写成「-33.8°N」这种自相矛盾的读数。 */
    private fun axisText(value: Double, positiveMark: Char, negativeMark: Char): String {
        val mark = if (value < 0) negativeMark else positiveMark
        return String.format(
            java.util.Locale.US,
            "%.${COORDINATE_DECIMALS}f°$mark",
            kotlin.math.abs(value)
        )
    }

    fun stationaryWaitText(waitDurationMs: Long): String {
        val totalSec = (waitDurationMs / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(java.util.Locale.US, "静止等待：%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun focusText(facts: Facts): String {
        val focus = facts.focusDistanceDiopters
        val focusValue = when {
            focus == null -> "--"
            focus <= 0.01f -> "∞(${String.format(java.util.Locale.US, "%.2f", focus)}D)"
            else -> {
                val meters = 1f / focus
                val distance = when {
                    meters < 1f -> String.format(java.util.Locale.US, "%.0fcm", meters * 100f)
                    meters >= 10f -> String.format(java.util.Locale.US, "%.0fm", meters)
                    else -> String.format(java.util.Locale.US, "%.1fm", meters)
                }
                "${String.format(java.util.Locale.US, "%.2f", focus)}D($distance)"
            }
        }
        val state = if (facts.manualFocus) "MF" else facts.autofocusState ?: "AF--"
        return "对焦 $focusValue · $state"
    }
}

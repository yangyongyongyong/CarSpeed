package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕内容的穷举测试。
 *
 * 用户的原话是「改代码总是偷偷删除我的东西」：改了一处，另一个开关的表现就悄悄变了，
 * 而测试还是绿的。所以这里不做「抽查几个例子」，而是把十个开关的所有 2^10 种组合
 * 全部跑一遍，逐项确认「开着就出现、关掉就消失」，并确认白栏和 SRT 两条路径一致。
 */
class SubtitleContentTest {

    private val time = "2026.10.23 18:01:00"
    private val address = "杭州市滨江区"
    private val layer = "滨康路"
    private val coordinates = "30.20840°N 120.21230°E"
    private val radar = "限速 60"
    private val modeLabel = "1080p 1920x1080 · 30fps"
    private val speed = 62.5f

    /** 所有数据齐全，这样「某项没出现」只可能是开关关掉了，不是数据缺失。 */
    private fun fullFacts(fields: ShareSubtitleFields, placeholders: Boolean = false) = SubtitleContent.Facts(
        fields = fields,
        timeText = if (fields.showTime) time else null,
        speedKmh = speed,
        stationaryWaitMs = 3_661_000L,
        latitude = 30.2084,
        longitude = 120.2123,
        satelliteCount = 12,
        address = address,
        roadLayer = layer,
        radarLimit = radar,
        videoModeLabel = modeLabel,
        fps = 30,
        hdrActive = false,
        manualFocus = false,
        focusDistanceDiopters = 0.1f,
        autofocusState = "AF",
        placeholdersWhenMissing = placeholders
    )

    /** 每个开关在信息栏里留下的可识别痕迹。 */
    internal data class Probe(
        val name: String,
        val fieldName: String,
        val enabled: (ShareSubtitleFields) -> Boolean,
        val inInfoBar: (DashcamInfoBarText) -> Boolean,
        val inLines: (List<String>) -> Boolean
    )

    private val probes = listOf(
        Probe(
            "时间", "showTime",
            { it.showTime },
            { info -> info.caption.contains(time) },
            { lines -> lines.any { it == time } }
        ),
        Probe(
            "速度", "showSpeed",
            { it.showSpeed },
            { info -> info.title.startsWith("62.5") && info.label == "km/h" },
            { lines -> lines.any { it.startsWith("62.5 km/h") } }
        ),
        Probe(
            "停留", "showStationaryWait",
            { it.showStationaryWait },
            { info -> info.parameters.contains("停留 ") },
            { lines -> lines.any { it.startsWith("静止等待：") } }
        ),
        Probe(
            "经纬度", "showGps",
            { it.showGps },
            { info -> info.caption.contains(coordinates) },
            { lines -> lines.any { it.startsWith("30.20840°N") } }
        ),
        Probe(
            "卫星", "showSatellites",
            { it.showSatellites },
            { info -> info.parameters.contains("卫星 12") },
            { lines -> lines.any { it.startsWith("GPS卫星：") } }
        ),
        Probe(
            "雷达", "showRadarSummary",
            { it.showRadarSummary },
            { info -> info.parameters.contains(radar) },
            { lines -> lines.any { it == radar } }
        ),
        Probe(
            "地址", "showAddress",
            { it.showAddress },
            { info -> info.caption.contains(address) },
            { lines -> lines.any { it.contains(address) } }
        ),
        Probe(
            "对焦", "showFocus",
            { it.showFocus },
            { info -> info.parameters.contains("AF") },
            { lines -> lines.any { it.startsWith("对焦") } }
        ),
        Probe(
            "视频模式", "showVideoMode",
            { it.showVideoMode },
            { info -> info.parameters.contains("30fps") },
            { lines -> lines.any { it.startsWith("1080p") } }
        ),
        Probe(
            "道路图层", "showRoadLayer",
            { it.showRoadLayer },
            { info -> (info.caption + info.parameters).contains(layer) },
            { lines -> lines.any { it.contains(layer) } }
        )
    )

    @Test
    fun the_probe_list_covers_every_subtitle_toggle() {
        // 有人加了新开关却忘了在这里加探针，穷举测试就会「全绿但漏测」。
        val declared = ShareSubtitleFields::class.java.declaredFields
            .filter { it.type == Boolean::class.javaPrimitiveType }
            .map { it.name }
            .toSet()
        assertEquals(
            "字幕开关和测试覆盖的开关对不上，新增开关要同时加上探针",
            declared,
            probes.map { it.fieldName }.toSet()
        )
    }

    /** 十个开关的所有组合。 */
    private fun allCombinations(): List<ShareSubtitleFields> {
        val result = mutableListOf<ShareSubtitleFields>()
        for (mask in 0 until (1 shl 10)) {
            result += ShareSubtitleFields(
                showTime = mask and (1 shl 0) != 0,
                showSpeed = mask and (1 shl 1) != 0,
                showStationaryWait = mask and (1 shl 2) != 0,
                showGps = mask and (1 shl 3) != 0,
                showSatellites = mask and (1 shl 4) != 0,
                showRadarSummary = mask and (1 shl 5) != 0,
                showAddress = mask and (1 shl 6) != 0,
                showFocus = mask and (1 shl 7) != 0,
                showVideoMode = mask and (1 shl 8) != 0,
                showRoadLayer = mask and (1 shl 9) != 0
            )
        }
        return result
    }

    @Test
    fun every_toggle_shows_up_exactly_when_it_is_on_in_the_info_bar() {
        for (fields in allCombinations()) {
            val info = SubtitleContent.infoBar(fullFacts(fields))
            for (probe in probes) {
                val expected = probe.enabled(fields)
                assertEquals(
                    "白栏「${probe.name}」开关=$expected 时表现不对，组合=$fields，结果=$info",
                    expected,
                    probe.inInfoBar(info)
                )
            }
        }
    }

    @Test
    fun every_toggle_shows_up_exactly_when_it_is_on_in_the_srt_lines() {
        for (fields in allCombinations()) {
            val lines = SubtitleContent.lines(fullFacts(fields, placeholders = true))
            for (probe in probes) {
                val expected = probe.enabled(fields)
                assertEquals(
                    "SRT「${probe.name}」开关=$expected 时表现不对，组合=$fields，结果=$lines",
                    expected,
                    probe.inLines(lines)
                )
            }
        }
    }

    @Test
    fun both_paths_agree_on_which_toggles_are_on() {
        // 两条路径各自拼文本，但「哪一项该出现」必须完全一致，
        // 否则录像时看到的和存下来的字幕会对不上。
        for (fields in allCombinations()) {
            val info = SubtitleContent.infoBar(fullFacts(fields))
            val lines = SubtitleContent.lines(fullFacts(fields, placeholders = true))
            for (probe in probes) {
                assertEquals(
                    "「${probe.name}」在两条路径上不一致，组合=$fields，白栏=$info，SRT=$lines",
                    probe.inInfoBar(info),
                    probe.inLines(lines)
                )
            }
        }
    }

    @Test
    fun all_toggles_off_leaves_the_bar_empty() {
        val info = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields(
            showTime = false,
            showSpeed = false,
            showStationaryWait = false,
            showGps = false,
            showSatellites = false,
            showRadarSummary = false,
            showAddress = false,
            showFocus = false,
            showVideoMode = false,
            showRoadLayer = false
        )))
        assertTrue("十个开关全关时白栏不该占位，实际=$info", info.isEmpty())
        assertTrue(SubtitleContent.lines(fullFacts(ShareSubtitleFields(
            showTime = false,
            showSpeed = false,
            showStationaryWait = false,
            showGps = false,
            showSatellites = false,
            showRadarSummary = false,
            showAddress = false,
            showFocus = false,
            showVideoMode = false,
            showRoadLayer = false
        ), placeholders = true)).isEmpty())
    }

    // ---- 用户实际报过的回归 ----

    @Test
    fun address_never_hides_the_coordinates() {
        // 原样复现用户 10-26 报的那个问题：拿到地址之后经纬度就不见了。
        val fields = ShareSubtitleFields()
        val info = SubtitleContent.infoBar(fullFacts(fields))
        val lines = SubtitleContent.lines(fullFacts(fields, placeholders = true))

        assertTrue("有地址时经纬度仍必须显示，实际白栏=$info", info.caption.contains(coordinates))
        assertTrue("有地址时经纬度仍必须显示，实际 SRT=$lines", lines.any { it.startsWith("30.20840°N") })
    }

    @Test
    fun coordinates_never_hide_the_address() {
        val info = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields()))
        assertTrue("有经纬度时地址仍必须显示，实际=$info", info.caption.contains(address))
    }

    @Test
    fun address_on_but_not_ready_yet_shows_a_placeholder_instead_of_silence() {
        // 地址开关开着、经纬度关掉、地址还没解析出来：必须写明「等待定位」，
        // 否则这一块整段消失，看起来就像功能被删了。
        val fields = ShareSubtitleFields(showGps = false, showAddress = true)
        val notReady = fullFacts(fields).copy(address = null, addressPendingText = "等待定位")

        assertTrue(
            "地址没解析出来时要写明等待定位，实际=${SubtitleContent.infoBar(notReady)}",
            SubtitleContent.infoBar(notReady).caption.contains("等待定位")
        )
        assertTrue(
            "SRT 同样要写明，实际=${SubtitleContent.lines(notReady.copy(addressPendingText = "位置解析中"))}",
            SubtitleContent.lines(notReady.copy(addressPendingText = "位置解析中")).contains("位置解析中")
        )
    }

    @Test
    fun missing_fix_still_shows_a_gps_placeholder_on_both_paths() {
        // 没拿到定位时两条路径都必须留占位，不能让经纬度这一行凭空消失。
        val fields = ShareSubtitleFields()
        val noFix = fullFacts(fields).copy(latitude = null, longitude = null)

        assertTrue("白栏缺定位要有占位，实际=${SubtitleContent.infoBar(noFix)}",
            SubtitleContent.infoBar(noFix).caption.contains("GPS --"))
        assertTrue("SRT 缺定位要有占位，实际=${SubtitleContent.lines(noFix)}",
            SubtitleContent.lines(noFix).contains("GPS --"))
    }

    @Test
    fun stationary_wait_is_shown_even_at_zero() {
        // 停留为 0 时不显示会让同一段录像前后几秒忽隐忽现，看起来像丢了一项。
        val zero = fullFacts(ShareSubtitleFields()).copy(stationaryWaitMs = 0L)
        assertTrue(SubtitleContent.infoBar(zero).parameters.contains("停留 "))
        assertTrue(SubtitleContent.lines(zero).any { it == "静止等待：00:00:00" })
    }

    @Test
    fun road_layer_follows_the_address_and_never_appears_twice() {
        // 图层挂在地址后面时不重复；地址关掉时自己单独占一行。
        val withAddress = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields()))
        assertEquals(
            "图层挂在地址后面时只应出现一次，实际=${withAddress.caption}",
            1,
            Regex(Regex.escape(layer)).findAll(withAddress.caption).count()
        )

        val withoutAddress = SubtitleContent.infoBar(
            fullFacts(ShareSubtitleFields(showAddress = false))
        )
        assertTrue("地址关掉时图层要自己显示，实际=${withoutAddress.caption}",
            withoutAddress.caption.contains(layer))

        val layerOff = SubtitleContent.infoBar(
            fullFacts(ShareSubtitleFields(showRoadLayer = false))
        )
        assertFalse("图层开关关掉时任何位置都不该有图层名，实际=${layerOff.caption}",
            layerOff.caption.contains(layer))
    }

    @Test
    fun blank_address_is_treated_as_no_address() {
        val blank = fullFacts(ShareSubtitleFields()).copy(address = "   ")
        val info = SubtitleContent.infoBar(blank)
        assertFalse("空白地址不该当成有效地址，实际=${info.caption}", info.caption.contains(address))
        assertTrue("空白地址时经纬度还在，实际=${info.caption}", info.caption.contains(coordinates))
    }

    @Test
    fun missing_radar_limit_does_not_leave_a_dangling_separator() {
        val noRadar = fullFacts(ShareSubtitleFields()).copy(radarLimit = null)
        val parameters = SubtitleContent.infoBar(noRadar).parameters
        assertFalse("雷达没数据时不该留下多余分隔符，实际=$parameters", parameters.contains("    "))
        assertTrue(parameters.isNotBlank())
    }

    // ---- 文本格式本身 ----

    @Test
    fun both_paths_use_the_same_coordinate_precision() {
        // 以前白栏 5 位、SRT 6 位，同一趟录像两处对不上。现在统一 5 位。
        val info = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields()))
        val lines = SubtitleContent.lines(fullFacts(ShareSubtitleFields()))
        assertTrue("白栏应为 5 位，实际=$info", info.caption.contains(coordinates))
        assertTrue("SRT 也应为 5 位，实际=$lines", lines.contains(coordinates))
    }

    @Test
    fun coordinates_carry_hemisphere_letters() {
        // 光看两个数字分不清哪一半是纬度、正负又在哪个半球，所以要有 N/E。
        val text = SubtitleContent.coordinatesText(30.2084, 120.2123)
        assertTrue("北半球要标 N，实际=$text", text.contains("°N"))
        assertTrue("东半球要标 E，实际=$text", text.endsWith("°E"))
        // 「北纬」和 N 是一个意思，都写上既啰嗦又白占宽度。
        assertFalse("不该再写「北纬」前缀，实际=$text", text.contains("北纬"))
        assertFalse("不该再写「东经」前缀，实际=$text", text.contains("东经"))
    }

    @Test
    fun southern_and_western_hemispheres_flip_the_letter_and_drop_the_sign() {
        // 南纬不能写成「-33.8°N」——符号和字母会互相矛盾。
        val text = SubtitleContent.coordinatesText(-33.8688, -151.2093)
        assertTrue("南半球要标 S，实际=$text", text.contains("°S"))
        assertTrue("西半球要标 W，实际=$text", text.contains("°W"))
        assertFalse("半球取绝对值后不该再带负号，实际=$text", text.contains("-"))
        assertFalse("不该再出现 N/E，实际=$text", text.contains("°N") || text.contains("°E"))
    }

    @Test
    fun coordinates_never_split_across_two_lines() {
        // 用户报的问题：经纬度一部分在第二行、另一部分在第三行。
        // 它现在是一个硬断行块，必须整体落在同一行。
        val info = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields()))
        val lines = SubtitleLineWrap.wrap(info.caption, 400f) { it.length * 10f }
        val line = lines.firstOrNull { it.contains("°N") }
        assertTrue("整段经纬度必须在同一行里，实际=$lines", line != null && line.contains("°E"))
    }

    @Test
    fun coordinates_own_their_own_line_when_the_caption_is_long() {
        // 时间+地址+经纬度放不进一行时，经纬度整块换到下一行，而不是被拆开接着写。
        val info = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields()))
        val lines = SubtitleLineWrap.wrap(info.caption, 260f) { it.length * 10f }

        val coordinateLine = lines.firstOrNull { it.contains("°N") }
        assertTrue("经纬度要单独成行，实际=$lines", coordinateLine != null)
        assertTrue("经纬度的两个半段必须在同一行，实际=$lines",
            coordinateLine!!.contains("°E"))
        assertFalse("经纬度那一行不该混进地址，实际=$lines", coordinateLine.contains(address))
    }

    @Test
    fun coordinates_come_last_so_an_ellipsis_never_eats_the_address() {
        // 三行封顶时最后一行被省略号收掉。经纬度放最后，被收的就是它而不是地址。
        val info = SubtitleContent.infoBar(fullFacts(ShareSubtitleFields()))
        val lines = SubtitleLineWrap.wrap(info.caption, 120f) { it.length * 10f }
        assertTrue("行数封顶时也不能超过三行，实际=$lines", lines.size <= SubtitleLineWrap.MAX_LINES)
        val joined = lines.joinToString("\n")
        assertTrue("地址不该被省略号吃掉，实际=$joined", joined.contains(address))
    }

    @Test
    fun stationary_wait_formats_as_a_clock() {
        assertEquals("静止等待：00:00:00", SubtitleContent.stationaryWaitText(0L))
        assertEquals("静止等待：00:00:00", SubtitleContent.stationaryWaitText(-1_500L))
        assertEquals("静止等待：01:01:01", SubtitleContent.stationaryWaitText(3_661_000L))
    }

    @Test
    fun focus_text_covers_manual_and_autofocus() {
        val manual = fullFacts(ShareSubtitleFields(showFocus = true)).copy(manualFocus = true)
        assertTrue(SubtitleContent.focusText(manual).endsWith("· MF"))

        val unknownFocus = fullFacts(ShareSubtitleFields(showFocus = true))
            .copy(focusDistanceDiopters = null, autofocusState = null)
        assertEquals("对焦 -- · AF--", SubtitleContent.focusText(unknownFocus))
    }

    @Test
    fun satellites_placeholder_only_appears_where_there_is_room() {
        // 白栏空间窄，没卫星数就不占位；SRT 一行一项，要写明 GPS卫星：--。
        val noCount = fullFacts(ShareSubtitleFields()).copy(satelliteCount = null)
        assertFalse(
            "白栏没卫星数时不该塞占位符，实际=${SubtitleContent.infoBar(noCount).parameters}",
            SubtitleContent.infoBar(noCount).parameters.contains("卫星")
        )
        assertTrue(
            "SRT 没卫星数时要写明占位，实际=${SubtitleContent.lines(noCount)}",
            SubtitleContent.lines(noCount).any { it == "GPS卫星：--" }
        )
    }
}

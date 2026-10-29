package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLineWrapTest {
    /** 每个字符按固定宽度算，方便精确断言。中文按 2 个单位、其余按 1 个单位，接近实际字宽。 */
    private fun measure(text: String): Float =
        text.fold(0) { acc, ch -> acc + if (ch.code > 0x2E80) 2 else 1 }.toFloat() * 10f

    @Test
    fun short_caption_stays_on_one_line() {
        val lines = SubtitleLineWrap.wrap("2026.10.23 18:01:00  杭州市滨江区", maxWidth = 1000f, measure = ::measure)
        assertEquals(listOf("2026.10.23 18:01:00  杭州市滨江区"), lines)
    }

    @Test
    fun time_address_and_coordinates_wrap_between_items() {
        // 这正是显示不全的那一行：时间 + 地址 + 经纬度，一行放不下。
        val text = "2026.10.23 18:01:00  杭州市滨江区\n30.20840°N 120.21230°E"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 350f, measure = ::measure)

        assertEquals(2, lines.size)
        // 在项之间断开，地址和经纬度各自完整，没有被从中间撕开。
        assertEquals("2026.10.23 18:01:00  杭州市滨江区", lines[0])
        assertEquals("30.20840°N 120.21230°E", lines[1])
        assertTrue(lines.none { it.endsWith("…") })
    }

    @Test
    fun coordinates_are_never_cut_when_it_wraps() {
        val text = "2026.10.23 18:01:00  杭州市滨江区\n30.20840°N 120.21230°E"
        val wrapped = SubtitleLineWrap.wrap(text, maxWidth = 350f, measure = ::measure).joinToString("")
        // 换行只是插入断行，两行放得下时一个字符都不该丢。
        assertEquals(text.replace(Regex("\\s"), ""), wrapped.replace(Regex("\\s"), ""))
    }

    @Test
    fun an_oversized_item_is_split_by_characters_and_never_loses_content_up_to_two_lines() {
        // 地址自己就超过一整行：按字符硬拆，两行内放得下的部分要完整保留。
        val text = "浙江省杭州市滨江区长河街道江南大道"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 200f, measure = ::measure)

        assertEquals(2, lines.size)
        lines.forEach { assertTrue("每行都应放得下: $it", measure(it) <= 200f) }
        assertEquals(text, lines.joinToString(""))
    }

    @Test
    fun more_than_two_lines_is_capped_and_marked_with_ellipsis() {
        val text = "2026.10.23 18:01:00  浙江省杭州市滨江区\n30.20840°N 120.21230°E  高架  地面道路"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 200f, measure = ::measure)

        assertEquals(SubtitleLineWrap.MAX_LINES, lines.size)
        // 两行都放不下时明确标出省略号，不能让人以为内容就这么多。
        assertTrue("最后一行应带省略号: ${lines.last()}", lines.last().endsWith("…"))
    }

    @Test
    fun wraps_into_three_lines_before_ellipsizing() {
        // 这正是加了经纬度之后的最坏情况：时间 + 长地址 + 经纬度，需要三行。
        val text = "2026.10.23 18:01:00  浙江省杭州市滨江区长河街道\n30.20840°N 120.21230°E"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 300f, measure = ::measure)

        assertEquals(3, lines.size)
        // 三行放得下就不该出现省略号。
        assertTrue("不该截断: $lines", lines.none { it.endsWith("…") })
        lines.forEach { assertTrue("每行都应放得下: $it", measure(it) <= 300f) }
        // 经纬度作为一个整体落在某一行里，没有被拆开。
        assertTrue("经纬度应完整出现在某一行: $lines",
            lines.any { it.contains("30.20840°N 120.21230°E") })
    }

    @Test
    fun wrap_limit_matches_the_bar_reservation() {
        // 白栏按最多三行预留高度，换行上限必须一致，否则第三行会被裁掉。
        assertEquals(3, SubtitleLineWrap.MAX_LINES)
        assertEquals(3, SubtitleLineWrap.wrap(
            "a  b  c  d  e  f",
            maxWidth = 30f,
            measure = ::measure
        ).size.coerceAtMost(SubtitleLineWrap.MAX_LINES))
    }

    @Test
    fun a_hard_break_always_starts_a_new_line() {
        // 经纬度用硬换行保护：无论前面那行还剩多少空间，它都必须从新的一行开始。
        val text = "2026.10.23 18:01:00\n30.20840°N 120.21230°E"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 1000f, measure = ::measure)

        assertEquals(2, lines.size)
        assertEquals("2026.10.23 18:01:00", lines[0])
        assertEquals("30.20840°N 120.21230°E", lines[1])
    }

    @Test
    fun a_hard_break_never_gets_filled_in_from_the_previous_group() {
        // 硬换行两侧不互相填充：即使第一行明明还空着，经纬度也不会被塞上去。
        val text = "18:01\n30.20840°N 120.21230°E"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 800f, measure = ::measure)

        assertEquals(2, lines.size)
        assertTrue("经纬度必须在自己那一行里完整出现: $lines",
            lines[1].contains("°N") && lines[1].contains("°E"))
        assertFalse("上一行的内容不能混进经纬度那一行: $lines", lines[1].contains("18:01"))
    }

    @Test
    fun a_long_item_after_a_hard_break_is_still_never_lost() {
        // 硬换行之后那一段自己还是可能超宽，这时按字符拆，但内容不能整段消失。
        val text = "18:01\n30.20840°N 120.21230°E"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 150f, measure = ::measure)

        assertTrue(lines.size >= 2)
        assertTrue("两行封顶内不能丢掉纬度: $lines", lines.joinToString("").contains("30.20840°N"))
        lines.forEach { assertTrue("每行都应放得下: $it", measure(it) <= 150f) }
    }

    @Test
    fun coordinates_with_hemisphere_letters_are_never_split() {
        // 用户报的问题：经纬度一部分在第二行、另一部分在第三行。
        val text = "2026.10.23 18:01:00  杭州市滨江区\n30.20840°N 120.21230°E"
        listOf(260f, 340f, 500f).forEach { width ->
            val lines = SubtitleLineWrap.wrap(text, maxWidth = width, measure = ::measure)
            val line = lines.firstOrNull { it.contains("°N") || it.contains("°E") }
            assertTrue("宽度 $width 下经纬度必须完整落在同一行: $lines",
                line != null && line.contains("°N") && line.contains("°E"))
        }
    }

    @Test
    fun a_too_wide_coordinate_is_ellipsized_as_a_whole_instead_of_torn_in_half() {
        // 一行都放不下整段坐标时，宁可整体收成省略号也不要拆开——
        // 「30.20840°N 120.21」这种半截坐标看着像完整坐标，比没有更容易看错。
        val text = "18:01\n30.20840°N 120.21230°E"
        val lines = SubtitleLineWrap.wrap(text, maxWidth = 150f, measure = ::measure)

        val containing = lines.count { it.contains("°N") || it.contains("°E") }
        assertEquals("经纬度只能出现在一行里，实际=$lines", 1, containing)
    }

    @Test
    fun blank_and_zero_width_inputs_are_safe() {
        assertTrue(SubtitleLineWrap.wrap("", maxWidth = 400f, measure = ::measure).isEmpty())
        assertTrue(SubtitleLineWrap.wrap("   ", maxWidth = 400f, measure = ::measure).isEmpty())
        // 宽度还没测量出来时原样返回，不做猜测。
        assertEquals(listOf("a  b"), SubtitleLineWrap.wrap("a  b", maxWidth = 0f, measure = ::measure))
    }

    @Test
    fun every_wrapped_line_fits_the_available_width() {
        val text = "2026.10.23 18:01:00  杭州市滨江区\n30.20840°N 120.21230°E"
        listOf(260f, 320f, 400f, 520f).forEach { width ->
            SubtitleLineWrap.wrap(text, maxWidth = width, measure = ::measure).forEach { line ->
                assertTrue("宽度 $width 下这一行放不下: $line", measure(line) <= width)
            }
        }
    }
}

package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test
    fun plain_values_stay_unquoted() {
        assertEquals("a,b,c", Csv.row(listOf("a", "b", "c")))
    }

    @Test
    fun a_value_with_a_half_width_comma_is_quoted_so_excel_does_not_shift_the_column() {
        assertEquals("\"a,b\",c", Csv.row(listOf("a,b", "c")))
    }

    @Test
    fun a_full_width_comma_is_left_alone_because_excel_does_not_split_on_it() {
        // 中文文案里的全角逗号不是 CSV 分隔符，加了引号反而让那一列变成文本。
        assertEquals("测速雷达，20秒,80", Csv.row(listOf("测速雷达，20秒", "80")))
    }

    @Test
    fun quotes_inside_a_value_are_doubled() {
        assertEquals("\"a\"\"b\"", Csv.row(listOf("a\"b")))
    }

    @Test
    fun a_newline_inside_a_value_is_quoted() {
        assertEquals("\"a\nb\"", Csv.row(listOf("a\nb")))
    }

    @Test
    fun empty_values_stay_empty() {
        assertEquals("a,,c", Csv.row(listOf("a", "", "c")))
    }
}

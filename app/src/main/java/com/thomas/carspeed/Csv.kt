package com.thomas.carspeed

/**
 * 极简 CSV 转义。留档里的地址、文案都可能有逗号和引号，
 * 不转义的话 Excel 打开就是错列，比没有留档还误导人。
 */
internal object Csv {
    fun row(values: List<String>): String {
        return values.joinToString(",") { cell(it) }
    }

    private fun cell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (NEEDS_QUOTING.any { escaped.contains(it) }) "\"$escaped\"" else escaped
    }

    private val NEEDS_QUOTING = charArrayOf(',', '"', '\n', '\r')
}

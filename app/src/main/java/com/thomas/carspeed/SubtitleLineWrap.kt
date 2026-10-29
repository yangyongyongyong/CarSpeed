package com.thomas.carspeed

/**
 * 明细白栏里的自动换行。
 *
 * 两条规则：
 * 1. `\n` 是**硬断行**——写在这里的项永远从新的一行开始，绝不和前面的内容挤在同一行。
 *    经纬度用它独占最后一行：它是一对坐标，跟时间地址挤在一行只会被拆得七零八落。
 * 2. 段内用「  」分隔的每一项都当成**不可拆的整体**，优先在项之间断开；
 *    只有某一项自己就宽过整行时，才退化成按字符硬拆。
 */
internal object SubtitleLineWrap {
    const val MAX_LINES = 3
    private const val SEPARATOR = "  "
    private const val HARD_BREAK = '\n'
    private const val ELLIPSIS = "…"

    fun wrap(
        text: String,
        maxWidth: Float,
        maxLines: Int = MAX_LINES,
        measure: (String) -> Float
    ): List<String> {
        if (text.isBlank()) return emptyList()
        if (maxWidth <= 0f || maxLines < 1) return listOf(text)

        val lines = mutableListOf<String>()
        // 先按硬断行切成若干组，每组内部再按宽度软换行。硬断行之间不互相填充。
        for (group in text.split(HARD_BREAK)) {
            if (group.isBlank()) continue
            wrapGroup(group, maxWidth, measure, lines)
        }
        if (lines.isEmpty()) return emptyList()

        if (lines.size <= maxLines) return lines
        // 超过上限时把最后一行收成省略号，明确告诉用户还有没显示完的内容。
        return lines.take(maxLines - 1) + ellipsizeAlways(lines[maxLines - 1], maxWidth, measure)
    }

    /** 把一组（不含硬断行）按宽度顺序塞进 [into]。 */
    private fun wrapGroup(
        group: String,
        maxWidth: Float,
        measure: (String) -> Float,
        into: MutableList<String>
    ) {
        val segments = group.split(SEPARATOR).filter { it.isNotBlank() }
        if (segments.isEmpty()) return
        var current = ""
        for (segment in segments) {
            val candidate = if (current.isEmpty()) segment else current + SEPARATOR + segment
            if (measure(candidate) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) {
                into += current
                current = ""
            }
            if (segment.count { it == '°' } >= 2) {
                // 经纬度是一对必须成对读的坐标：整段放不下就整体收成省略号，
                // 绝不按字符拆——「30.20840°N 120.21」看着像完整坐标，比没有更容易看错。
                into += ellipsizeAlways(segment, maxWidth, measure)
                current = ""
                continue
            }
            // 地址这类内容按字符硬拆，宁可难看也不能整段丢掉。
            var rest = segment
            while (rest.isNotEmpty() && measure(rest) > maxWidth) {
                var cut = rest.length
                while (cut > 1 && measure(rest.substring(0, cut)) > maxWidth) cut -= 1
                into += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            current = rest
        }
        if (current.isNotEmpty()) into += current
    }

    private fun ellipsizeAlways(line: String, maxWidth: Float, measure: (String) -> Float): String {
        var cut = line.length
        while (cut > 0 && measure(line.substring(0, cut) + ELLIPSIS) > maxWidth) cut -= 1
        return line.substring(0, cut) + ELLIPSIS
    }
}

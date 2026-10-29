package com.thomas.carspeed

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class DashcamInfoBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val title = textView(ExportSubtitleStyle.TITLE_SP, Color.BLACK, "sans-serif-medium")
    private val label = textView(ExportSubtitleStyle.LABEL_SP, Color.DKGRAY, "sans-serif")
    private val parameters = textView(ExportSubtitleStyle.PARAMETERS_SP, Color.BLACK, "sans-serif-medium")
    private val caption = textView(ExportSubtitleStyle.CAPTION_SP, Color.DKGRAY, "sans-serif")
    private val identity: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(ExportSubtitleStyle.INSET_HORIZONTAL_DP), dp(ExportSubtitleStyle.INSET_VERTICAL_DP),
            dp(ExportSubtitleStyle.INSET_HORIZONTAL_DP), dp(ExportSubtitleStyle.INSET_VERTICAL_DP))
        identity = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ExportSubtitleStyle.LABEL_GAP_DP)
            })
        }
        addView(identity, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(ExportSubtitleStyle.IDENTITY_GAP_DP)
        })
        val details = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(parameters, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(caption, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ExportSubtitleStyle.DETAILS_GAP_DP)
            })
        }
        parameters.gravity = Gravity.END
        caption.gravity = Gravity.END
        addView(details, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        identity.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        details.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /**
     * [showBar] 由字幕开关决定：十项全关时整条白栏收起，画面占满。
     * 开关开着但这一秒没有字幕数据时仍然保留白栏，避免播放中忽隐忽现。
     */
    fun setInfo(info: DashcamInfoBarText, showBar: Boolean = true) {
        visibility = if (showBar) VISIBLE else GONE
        title.text = info.title
        title.textSize = ExportSubtitleStyle.TITLE_SP
        label.text = info.label
        parameters.text = info.parameters
        caption.text = info.caption
        title.visibility = if (info.title.isBlank()) GONE else VISIBLE
        label.visibility = if (info.label.isBlank()) GONE else VISIBLE
        parameters.visibility = if (info.parameters.isBlank()) GONE else VISIBLE
        caption.visibility = if (info.caption.isBlank()) GONE else VISIBLE
        identity.visibility = if (info.title.isBlank() && info.label.isBlank()) GONE else VISIBLE
        val fullText = listOf("${info.title} ${info.label}", info.parameters, info.caption)
            .filter { it.isNotBlank() }.joinToString("\n")
        contentDescription = fullText
        tooltipText = fullText
    }

    private fun textView(size: Float, color: Int, family: String) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(family, Typeface.NORMAL)
        includeFontPadding = false
        // 第二行把时间、地址、经纬度拼在一起，一行经常放不下：允许换行但封顶两行，
        // 超过再省略，既不会把内容截掉，也不会把白栏撑得老高。
        maxLines = SubtitleLineWrap.MAX_LINES
        ellipsize = TextUtils.TruncateAt.END
        fontFeatureSettings = "tnum"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}

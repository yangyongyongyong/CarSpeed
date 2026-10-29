package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale


/**
 * 跳到高德地图的路线规划页。
 *
 * 用高德的 `amapuri://route/plan` 调起方式：只给终点，起点留空让高德用自己当前的定位。
 * 这样用户在我们这边选好终点、跳过去就能直接导航，我们这边也记住了终点和几条规划路线，
 * 雷达预警可以按整条路线来做，而不是只盯着前方那一个巡航回调点。
 */
internal object AmapNavigate {
    private const val AMAP_PACKAGE = "com.autonavi.minimap"

    fun isInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(AMAP_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * 拼路线规划页的 URI。
     *
     * sourceApplication 是高德要求的来源标识，出问题时要靠它在高德侧定位是哪个 App 调的。
     * t=0 是驾车。dev 和 style 用不到，但高德文档里是标准参数，留着不会出错。
     */
    fun planUri(destination: PlaceTip): String {
        return buildString {
            append("amapuri://route/plan/?")
            append("sourceApplication=carspeed")
            append("&dlat=").append(format(destination.latitude))
            append("&dlon=").append(format(destination.longitude))
            append("&dname=").append(encodeQueryValue(destination.name))
            // t=0 驾车。起点不传，让高德用它自己的当前定位。
            append("&t=0")
            append("&dev=0")
        }
    }

    /**
     * URL 里参数值的编码。不用 android.net.Uri 是为了能在普通单元测试里直接跑——
     * 这里错一个字符，终点名就会被截断或整条 intent 失效。
     */
    fun encodeQueryValue(value: String): String {
        val builder = StringBuilder()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val unreserved = (code < 128) && (char.isLetterOrDigit() || char in UNRESERVED)
            if (unreserved) {
                builder.append(char)
            } else {
                builder.append('%').append(String.format(Locale.US, "%02X", code))
            }
        }
        return builder.toString()
    }

    /** RFC 3986 里不用转义的那几个符号。 */
    private const val UNRESERVED = "-._~"

    /**
     * 打开高德路线规划页。返回是否成功调起。
     * 没装高德时返回 false，由调用方决定是提示还是退回应用商店。
     */
    fun openRoutePlan(context: Context, destination: PlaceTip): Boolean {
        if (!isInstalled(context)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(planUri(destination))).apply {
            setPackage(AMAP_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)
}

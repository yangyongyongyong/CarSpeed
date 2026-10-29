package com.thomas.carspeed

import android.content.Context
import com.amap.api.maps.model.LatLng

/**
 * 当前正在使用的规划路线。
 *
 * 用户在查路线页算完路线、跳去高德导航之后，这个终点和这几条路线要一起活下来：
 * 雷达预警要靠它判断「前方还有几个雷达、还多远」，而不是只盯着高德巡航给的那一个。
 *
 * 存在 SharedPreferences 里而不是内存里，是因为查路线页和雷达服务是两个组件，
 * 而且进程可能被杀——重新开起来时路线还得在。
 *
 * 用逐行的纯文本而不是 JSON：这份数据是「几个数字排成一张表」，没有嵌套结构，
 * 行格式配上转义就够，而且可以在普通 JVM 单元测试里原样跑一遍，
 * 不用为了测一个存档格式去引入 org.json 的测试实现。
 */
internal object PlannedRouteStore {
    private const val KEY_STATE = "planned_route_state"

    data class State(
        val endName: String,
        val endLatitude: Double,
        val endLongitude: Double,
        val routes: List<PlannedRoute>,
        /** 用户跳转高德时选中的是第几条，作为初始判断的起点。 */
        val selectedRouteId: Int,
        val savedAtMs: Long
    ) {
        fun isEmpty(): Boolean = routes.isEmpty()
    }

    /**
     * 路线不是永久有效的：几个小时后用户早就开到别处了，
     * 继续拿旧路线算「前方 2 公里有雷达」只会是错的。
     */
    const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    fun save(context: Context, state: State) {
        prefs(context).edit().putString(KEY_STATE, encode(state)).apply()
    }

    fun load(context: Context, nowMs: Long = System.currentTimeMillis()): State? {
        val raw = prefs(context).getString(KEY_STATE, null) ?: return null
        val state = decode(raw) ?: return null
        if (nowMs - state.savedAtMs > STALE_AFTER_MS) return null
        return state
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_STATE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)

    // ---- 存档格式 ----
    // 一行一条记录，用竖线分隔字段。地名可能带竖线和换行，统一转义。
    //   state|终点名|终点纬度|终点经度|选中路线|保存时间
    //   route|id|名称|时长|距离|收费
    //   point|纬度|经度
    //   radar|纬度|经度|类型|限速|层级

    private const val FIELD = '|'

    fun encode(state: State): String {
        val lines = mutableListOf<String>()
        lines += listOf(
            "state",
            escape(state.endName),
            format(state.endLatitude),
            format(state.endLongitude),
            state.selectedRouteId.toString(),
            state.savedAtMs.toString()
        ).joinToString(FIELD.toString())
        state.routes.forEach { route ->
            lines += listOf(
                "route",
                route.id.toString(),
                escape(route.label),
                route.durationSeconds.toString(),
                route.distanceMeters.toString(),
                route.tollYuan.toString()
            ).joinToString(FIELD.toString())
            route.points.forEach { point ->
                lines += listOf("point", format(point.latitude), format(point.longitude))
                    .joinToString(FIELD.toString())
            }
            route.radars.forEach { radar ->
                lines += listOf(
                    "radar",
                    format(radar.latitude),
                    format(radar.longitude),
                    radar.cameraType.toString(),
                    radar.speedLimit?.toString().orEmpty(),
                    escape(radar.roadLayer)
                ).joinToString(FIELD.toString())
            }
        }
        return lines.joinToString("\n")
    }

    fun decode(raw: String): State? {
        if (raw.isBlank()) return null
        var endName = ""
        var endLatitude = 0.0
        var endLongitude = 0.0
        var selected = 0
        var savedAt = 0L
        var sawState = false
        val routes = mutableListOf<PlannedRoute>()
        // 正在拼的那条路线
        var routeId = 0
        var routeLabel = ""
        var routeDuration = 0
        var routeDistance = 0
        var routeToll = 0
        val points = mutableListOf<LatLng>()
        val radars = mutableListOf<VideoTrackRadar>()

        fun flushRoute() {
            if (points.size >= 2) {
                routes += PlannedRoute(
                    id = routeId,
                    label = routeLabel,
                    durationSeconds = routeDuration,
                    distanceMeters = routeDistance,
                    tollYuan = routeToll,
                    points = points.toList(),
                    radars = radars.toList()
                )
            }
            points.clear()
            radars.clear()
        }

        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split(FIELD)
            when (parts.firstOrNull()) {
                "state" -> {
                    if (parts.size < 6) return null
                    endName = unescape(parts[1])
                    endLatitude = parts[2].toDoubleOrNull() ?: return null
                    endLongitude = parts[3].toDoubleOrNull() ?: return null
                    selected = parts[4].toIntOrNull() ?: 0
                    savedAt = parts[5].toLongOrNull() ?: 0L
                    sawState = true
                }
                "route" -> {
                    flushRoute()
                    if (parts.size < 6) return null
                    routeId = parts[1].toIntOrNull() ?: return null
                    routeLabel = unescape(parts[2])
                    routeDuration = parts[3].toIntOrNull() ?: 0
                    routeDistance = parts[4].toIntOrNull() ?: 0
                    routeToll = parts[5].toIntOrNull() ?: 0
                }
                "point" -> {
                    if (parts.size < 3) continue
                    val latitude = parts[1].toDoubleOrNull() ?: continue
                    val longitude = parts[2].toDoubleOrNull() ?: continue
                    points += LatLng(latitude, longitude)
                }
                "radar" -> {
                    if (parts.size < 6) continue
                    val latitude = parts[1].toDoubleOrNull() ?: continue
                    val longitude = parts[2].toDoubleOrNull() ?: continue
                    radars += VideoTrackRadar(
                        elapsedMs = 0L,
                        timeMs = 0L,
                        latitude = latitude,
                        longitude = longitude,
                        cameraType = parts[3].toIntOrNull() ?: 4,
                        speedLimit = parts[4].toIntOrNull()?.takeIf { it > 0 },
                        roadLayer = unescape(parts[5])
                    )
                }
            }
        }
        flushRoute()
        if (!sawState || routes.isEmpty()) return null
        return State(
            endName = endName,
            endLatitude = endLatitude,
            endLongitude = endLongitude,
            routes = routes,
            selectedRouteId = selected.takeIf { id -> routes.any { it.id == id } } ?: routes.first().id,
            savedAtMs = savedAt
        )
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.6f", value)

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(FIELD.toString(), "\\p")
        .replace("\n", "\\n")

    private fun unescape(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (value[index + 1]) {
                    'p' -> result.append(FIELD)
                    'n' -> result.append('\n')
                    '\\' -> result.append('\\')
                    else -> result.append(value[index + 1])
                }
                index += 2
            } else {
                result.append(char)
                index++
            }
        }
        return result.toString()
    }
}

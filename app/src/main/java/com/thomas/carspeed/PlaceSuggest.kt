package com.thomas.carspeed

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.services.core.AMapException
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery

internal data class PlaceTip(
    val name: String,
    val detail: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String
)

/** 起点终点的输入提示。坐标已经是地图坐标。 */
internal class PlaceSuggest(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestId = 0

    fun suggest(keyword: String, onResult: (List<PlaceTip>) -> Unit) {
        val id = ++requestId
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            deliver(id, emptyList(), onResult)
            return
        }
        val query = InputtipsQuery(trimmed, "").apply { cityLimit = false }
        val search = Inputtips(appContext, query)
        search.setInputtipsListener { tips, code ->
            if (id != requestId) return@setInputtipsListener
            val places = if (code == AMapException.CODE_AMAP_SUCCESS) {
                tips.orEmpty().mapNotNull { tip ->
                    val point = tip.point ?: return@mapNotNull null
                    val name = tip.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    PlaceTip(
                        name = name,
                        detail = listOfNotNull(tip.district, tip.address)
                            .filter { it.isNotBlank() && it != name }
                            .distinct()
                            .joinToString(" "),
                        latitude = point.latitude,
                        longitude = point.longitude,
                        poiId = tip.poiID.orEmpty()
                    )
                }
            } else {
                emptyList()
            }
            deliver(id, places, onResult)
        }
        search.requestInputtipsAsyn()
    }

    fun cancel() {
        requestId++
    }

    private fun deliver(id: Int, places: List<PlaceTip>, onResult: (List<PlaceTip>) -> Unit) {
        if (id != requestId) return
        if (Looper.myLooper() == Looper.getMainLooper()) onResult(places)
        else mainHandler.post { if (id == requestId) onResult(places) }
    }
}

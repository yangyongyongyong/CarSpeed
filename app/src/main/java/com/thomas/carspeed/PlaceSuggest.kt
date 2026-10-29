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
    private var geocodeId = 0

    /**
     * 把一个坐标反查成地名。地图上长按选点后用它填出名字，
     * 不然输入框里只有一个「我的位置」——用户看不出自己选的是哪。
     */
    fun describe(
        latitude: Double,
        longitude: Double,
        onResult: (String) -> Unit
    ) {
        describePlace(latitude, longitude) { place ->
            onResult(place?.label().orEmpty())
        }
    }

    /** 地图上按坐标选的点：名字 + 详细地址，和搜索出来的地点长得一样。 */
    data class Place(val name: String, val detail: String) {
        /** 输入框里显示的样子。名字和地址相同时只显示一遍，不然会出现「杭州…杭州…」。 */
        fun label(): String = when {
            name.isBlank() -> detail
            detail.isBlank() || detail == name -> name
            else -> "$name $detail"
        }
    }

    /**
     * 把一个坐标反查成「名字 + 地址」。
     *
     * 之前这里只回一个 formatAddress 字符串，而它经常以 "#" 开头（高德对没门牌号的点
     * 就是这样返回的），输入框里就只剩一个 "#"，也认不出是哪栋楼。
     * 有 POI（小区、楼、店）时优先用它的名字，名字和街道地址都不为空才都有。
     */
    fun describePlace(
        latitude: Double,
        longitude: Double,
        onResult: (Place?) -> Unit
    ) {
        val id = ++geocodeId
        val query = com.amap.api.services.geocoder.RegeocodeQuery(
            com.amap.api.services.core.LatLonPoint(latitude, longitude),
            200f,
            // 第三个参数是坐标系。地图上拿到的点已经是 GCJ-02（高德自带坐标系），
            // 传 autonavi 表示「别再转换」；留空或传 GPS 会被当成 WGS-84 再转一次，位置偏几百米。
            LAT_LON_TYPE_AUTONAVI
        )
        val search = com.amap.api.services.geocoder.GeocodeSearch(appContext)
        search.setOnGeocodeSearchListener(
            object : com.amap.api.services.geocoder.GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: com.amap.api.services.geocoder.RegeocodeResult?, code: Int) {
                    if (id != geocodeId) return
                    val address = result?.regeocodeAddress ?: run {
                        mainHandler.post { onResult(null) }
                        return
                    }
                    val poiName = address.pois?.firstOrNull()?.title.orEmpty().clean()
                    val streetAddress = address.formatAddress.clean()
                    val place = when {
                        poiName.isBlank() && streetAddress.isBlank() -> null
                        poiName.isBlank() -> Place(name = streetAddress, detail = "")
                        else -> Place(name = poiName, detail = streetAddress)
                    }
                    mainHandler.post { onResult(place) }
                }

                override fun onGeocodeSearched(
                    result: com.amap.api.services.geocoder.GeocodeResult?,
                    code: Int
                ) = Unit
            }
        )
        runCatching { search.getFromLocationAsyn(query) }
    }

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
        geocodeId++
    }

    private fun deliver(id: Int, places: List<PlaceTip>, onResult: (List<PlaceTip>) -> Unit) {
        if (id != requestId) return
        if (Looper.myLooper() == Looper.getMainLooper()) onResult(places)
        else mainHandler.post { if (id == requestId) onResult(places) }
    }

    private companion object {
        /** 高德坐标系标识：入参已经是 GCJ-02，不用再转换。 */
        const val LAT_LON_TYPE_AUTONAVI = "autonavi"
    }

    /** 高德没门牌号时会返回以 "#" 开头的串，留着只会让输入框看起来像出错。 */
    private fun String.clean(): String = trim().trimStart('#').trim()
}

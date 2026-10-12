package com.thomas.carspeed

object AmapOfflineMapStore {
    val regions = listOf(
        AmapOfflineRegion("zhejiang", "浙江省", 118.0, 27.0, 123.2, 31.3),
        AmapOfflineRegion("shanghai", "上海市", 120.8, 30.6, 122.1, 31.9),
        AmapOfflineRegion("jiangsu", "江苏省", 116.3, 30.7, 121.9, 35.2),
        AmapOfflineRegion("anhui", "安徽省", 114.8, 29.4, 119.7, 34.7),
        AmapOfflineRegion("fujian", "福建省", 115.8, 23.5, 120.7, 28.4),
        AmapOfflineRegion("jiangxi", "江西省", 113.5, 24.4, 118.5, 30.1),
        AmapOfflineRegion("shandong", "山东省", 114.8, 34.3, 122.8, 38.5),
        AmapOfflineRegion("guangdong", "广东省", 109.6, 20.1, 117.3, 25.6),
        AmapOfflineRegion("guangxi", "广西壮族自治区", 104.4, 20.9, 112.1, 26.4),
        AmapOfflineRegion("hainan", "海南省", 108.5, 18.0, 111.2, 20.2),
        AmapOfflineRegion("henan", "河南省", 110.3, 31.4, 116.7, 36.4),
        AmapOfflineRegion("hubei", "湖北省", 108.3, 29.0, 116.1, 33.3),
        AmapOfflineRegion("hunan", "湖南省", 108.8, 24.6, 114.4, 30.2),
        AmapOfflineRegion("beijing", "北京市", 115.4, 39.4, 117.6, 41.1),
        AmapOfflineRegion("tianjin", "天津市", 116.7, 38.5, 118.1, 40.3),
        AmapOfflineRegion("hebei", "河北省", 113.5, 36.0, 119.9, 42.7),
        AmapOfflineRegion("shanxi", "山西省", 110.2, 34.6, 114.6, 40.8),
        AmapOfflineRegion("inner_mongolia", "内蒙古自治区", 97.1, 37.4, 126.1, 53.6),
        AmapOfflineRegion("liaoning", "辽宁省", 118.8, 38.7, 125.8, 43.5),
        AmapOfflineRegion("jilin", "吉林省", 121.6, 40.9, 131.3, 46.3),
        AmapOfflineRegion("heilongjiang", "黑龙江省", 121.1, 43.4, 135.1, 53.6),
        AmapOfflineRegion("chongqing", "重庆市", 105.2, 28.1, 110.2, 32.3),
        AmapOfflineRegion("sichuan", "四川省", 97.3, 26.0, 108.6, 34.3),
        AmapOfflineRegion("guizhou", "贵州省", 103.6, 24.6, 109.6, 29.3),
        AmapOfflineRegion("yunnan", "云南省", 97.5, 21.1, 106.2, 29.3),
        AmapOfflineRegion("tibet", "西藏自治区", 78.3, 26.8, 99.1, 36.6),
        AmapOfflineRegion("shaanxi", "陕西省", 105.5, 31.7, 111.3, 39.6),
        AmapOfflineRegion("gansu", "甘肃省", 92.3, 32.0, 108.7, 42.8),
        AmapOfflineRegion("qinghai", "青海省", 89.4, 31.4, 103.1, 39.2),
        AmapOfflineRegion("ningxia", "宁夏回族自治区", 104.2, 35.1, 107.7, 39.4),
        AmapOfflineRegion("xinjiang", "新疆维吾尔自治区", 73.4, 34.2, 96.4, 49.2),
        AmapOfflineRegion("taiwan", "台湾省", 119.3, 21.8, 122.1, 25.5),
        AmapOfflineRegion("hong_kong", "香港特别行政区", 113.8, 22.1, 114.5, 22.6),
        AmapOfflineRegion("macau", "澳门特别行政区", 113.5, 22.1, 113.7, 22.3)
    )

    fun regionForRoute(points: List<VideoTrackPoint>): AmapOfflineRegion? {
        if (points.isEmpty()) return null
        val centerLat = points.map { it.latitude }.average()
        val centerLon = points.map { it.longitude }.average()
        return regions.firstOrNull { it.contains(centerLat, centerLon) }
    }

    fun compactName(name: String): String {
        return name
            .removeSuffix("省")
            .removeSuffix("市")
            .removeSuffix("壮族自治区")
            .removeSuffix("回族自治区")
            .removeSuffix("维吾尔自治区")
            .removeSuffix("自治区")
            .removeSuffix("特别行政区")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "大小未知"
        val mb = bytes / 1024.0 / 1024.0
        return if (mb < 100) String.format(java.util.Locale.CHINA, "%.1f MB", mb) else String.format(java.util.Locale.CHINA, "%.0f MB", mb)
    }
}

data class AmapOfflineRegion(
    val id: String,
    val provinceName: String,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double
) {
    val compactName: String = AmapOfflineMapStore.compactName(provinceName)

    fun contains(latitude: Double, longitude: Double): Boolean {
        return longitude in minLon..maxLon && latitude in minLat..maxLat
    }
}

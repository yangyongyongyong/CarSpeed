package com.thomas.carspeed

internal object PlaceSubtitle {
    private val roadHint = Regex("路|街|道|巷|线|桥|高速|高架|公路")
    private val houseNumber = Regex("\\d+号?$")
    private val provinceHead = Regex("^[\\u4e00-\\u9fff]{2,8}省")
    private val districtHead = Regex("^([\\u4e00-\\u9fff]{1,8}(?:区|县|旗))(.+)$")

    fun format(
        adminArea: String? = null,
        subAdminArea: String? = null,
        locality: String? = null,
        subLocality: String? = null,
        thoroughfare: String? = null,
        featureName: String? = null,
        premises: String? = null,
        addressLine: String? = null
    ): String? {
        var city: String? = null
        var district: String? = null
        var town: String? = null
        var village: String? = null
        var road: String? = null
        var poi: String? = null
        var peeledDistrict: String? = null
        fun take(raw: String?, fromAdmin: Boolean = false, preferRoad: Boolean = false) {
            val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val parsed = kindOf(name)
            val kind = when {
                parsed == Kind.NAMED && fromAdmin -> Kind.CITY
                parsed == Kind.NAMED && preferRoad -> Kind.ROAD
                parsed == Kind.NAMED -> Kind.POI
                else -> parsed
            }
            when (kind) {
                Kind.PROVINCE, Kind.IGNORE, Kind.NAMED -> Unit
                Kind.CITY -> if (city == null) city = withCitySuffix(name)
                Kind.DISTRICT -> if (district == null) district = name
                Kind.TOWN -> if (town == null) town = name
                Kind.VILLAGE -> if (village == null) village = name
                Kind.ROAD -> if (road == null) road = stripHouseNumber(name)
                Kind.POI -> if (poi == null) poi = name
            }
        }
        take(adminArea, fromAdmin = true)
        take(subAdminArea, fromAdmin = true)
        take(locality, fromAdmin = true)
        take(subLocality)
        take(thoroughfare, preferRoad = true)
        take(featureName)
        take(premises)
        take(addressLine)
        road = road?.let { peelPlace(it, city) { peeledDistrict = it } }
        village = village?.let { peelPlace(it, city) { peeledDistrict = it } }
        poi = poi?.let { peelPlace(it, city) { peeledDistrict = it } }
        if (district == null) district = peeledDistrict
        return if (road != null) {
            joinDistinct(city, district ?: town, road)
        } else {
            joinDistinct(city, village ?: poi ?: town ?: district)
        }
    }

    fun withLayer(place: String?, layer: String?): String? {
        val placeText = place?.trim()?.takeIf { it.isNotEmpty() }
        val layerText = layer?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            placeText != null && layerText != null && !placeText.endsWith(layerText) -> "$placeText $layerText"
            placeText != null -> placeText
            else -> layerText
        }
    }

    private enum class Kind { PROVINCE, CITY, DISTRICT, TOWN, VILLAGE, ROAD, POI, NAMED, IGNORE }

    private fun kindOf(name: String): Kind {
        if (name.none { it.code in 0x4E00..0x9FFF }) return Kind.IGNORE
        if (houseNumber.matches(name)) return Kind.IGNORE
        if (name.endsWith("省")) return Kind.PROVINCE
        if (name.endsWith("村") || name.endsWith("社区") || name.endsWith("嘎查") || name.endsWith("屯")) {
            return Kind.VILLAGE
        }
        if (name.endsWith("街道") || name.endsWith("镇") || name.endsWith("乡") || name.endsWith("苏木")) {
            return Kind.TOWN
        }
        if (looksLikeRoad(name)) return Kind.ROAD
        if (name.endsWith("小区") || name.endsWith("大厦") || name.endsWith("广场") ||
            name.endsWith("工厂") || name.endsWith("厂") || name.endsWith("公司")
        ) {
            return Kind.POI
        }
        if (name.endsWith("区") || name.endsWith("县") || name.endsWith("旗")) return Kind.DISTRICT
        if (name.endsWith("市") || name.endsWith("自治州") || name.endsWith("盟") ||
            name.endsWith("地区") || name.endsWith("州")
        ) {
            return Kind.CITY
        }
        return Kind.NAMED
    }

    private fun looksLikeRoad(name: String): Boolean {
        return roadHint.containsMatchIn(name) && !name.endsWith("区") && !name.endsWith("村")
    }

    private fun withCitySuffix(name: String): String {
        if (name.endsWith("市") || name.endsWith("自治州") || name.endsWith("盟") ||
            name.endsWith("地区") || name.endsWith("省")
        ) {
            return name
        }
        return name + "市"
    }

    private fun stripHouseNumber(name: String): String {
        val stripped = name.replace(houseNumber, "").trim()
        return stripped.ifBlank { name }
    }

    private fun peelPlace(name: String, city: String?, onDistrict: (String) -> Unit): String {
        var text = stripHouseNumber(name).removePrefix("中国")
        text = text.replace(provinceHead, "")
        if (!city.isNullOrEmpty() && text.startsWith(city) && text.length > city.length) {
            text = text.removePrefix(city)
        }
        val head = districtHead.find(text)
        if (head != null) {
            val finer = head.groupValues[2]
            val finerKind = kindOf(finer)
            if (finerKind != Kind.IGNORE && finerKind != Kind.PROVINCE && finerKind != Kind.CITY) {
                onDistrict(head.groupValues[1])
                text = finer
            }
        }
        return text.trim().ifBlank { name }
    }

    private fun joinDistinct(vararg parts: String?): String? {
        val out = mutableListOf<String>()
        for (raw in parts) {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val combined = out.joinToString("")
            if (combined.endsWith(text)) continue
            if (out.isNotEmpty() && text.startsWith(out.last()) && text.length > out.last().length) {
                out[out.lastIndex] = text
                continue
            }
            out += text
        }
        return out.joinToString("").ifBlank { null }
    }
}

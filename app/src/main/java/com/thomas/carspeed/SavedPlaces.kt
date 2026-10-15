package com.thomas.carspeed

/** 查路线页记住的家、公司和收藏夹。纯编码，方便单测。 */
internal object SavedPlaces {
    const val KEY_HOME = "radar_query_place_home"
    const val KEY_COMPANY = "radar_query_place_company"
    const val KEY_FAVORITES = "radar_query_place_favorites"
    const val MAX_FAVORITES = 30

    fun encode(place: PlaceTip): String {
        return listOf(
            place.name,
            place.detail,
            place.latitude.toString(),
            place.longitude.toString(),
            place.poiId
        ).joinToString("|") { escape(it) }
    }

    fun decode(raw: String?): PlaceTip? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val fields = splitFields(text) ?: return null
        if (fields.size != 5) return null
        val name = fields[0]
        if (name.isBlank()) return null
        val latitude = fields[2].toDoubleOrNull() ?: return null
        val longitude = fields[3].toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return PlaceTip(
            name = name,
            detail = fields[1],
            latitude = latitude,
            longitude = longitude,
            poiId = fields[4]
        )
    }

    fun encodeFavorites(places: List<PlaceTip>): String = places.joinToString("\n") { encode(it) }

    fun decodeFavorites(raw: String?): List<PlaceTip> {
        return raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { decode(it) }
            .toList()
    }

    /** 新地址放在最前面。已经收藏过就原样返回，added 为 false。超过上限丢掉最早的一条。 */
    fun addFavorite(favorites: List<PlaceTip>, place: PlaceTip): FavoriteAdd {
        if (favorites.any { samePlace(it, place) }) {
            return FavoriteAdd(favorites, added = false)
        }
        return FavoriteAdd(
            favorites = (listOf(place) + favorites).take(MAX_FAVORITES),
            added = true
        )
    }

    fun samePlace(left: PlaceTip, right: PlaceTip): Boolean {
        if (left.poiId.isNotBlank() && left.poiId == right.poiId) return true
        return left.name == right.name &&
            nearlySame(left.latitude, right.latitude) &&
            nearlySame(left.longitude, right.longitude)
    }

    private fun nearlySame(left: Double, right: Double): Boolean = kotlin.math.abs(left - right) < 0.00001

    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '|' -> append("\\|")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun splitFields(raw: String): List<String>? {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in raw) {
            if (escaped) {
                when (ch) {
                    '\\' -> current.append('\\')
                    '|' -> current.append('|')
                    'n' -> current.append('\n')
                    'r' -> current.append('\r')
                    else -> return null
                }
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '|') {
                fields += current.toString()
                current.clear()
            } else {
                current.append(ch)
            }
        }
        if (escaped) return null
        fields += current.toString()
        return fields
    }
}

internal data class FavoriteAdd(
    val favorites: List<PlaceTip>,
    val added: Boolean
)

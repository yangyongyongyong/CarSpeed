package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPlacesTest {
    @Test
    fun round_trips_a_place_including_pipes_and_newlines() {
        val place = PlaceTip(
            name = "家|公司",
            detail = "杭州市\n西湖区",
            latitude = 30.25,
            longitude = 120.15,
            poiId = "B0FF\\1"
        )

        val decoded = SavedPlaces.decode(SavedPlaces.encode(place))

        assertEquals(place, decoded)
    }

    @Test
    fun rejects_blank_and_corrupt_records() {
        assertNull(SavedPlaces.decode(null))
        assertNull(SavedPlaces.decode("   "))
        assertNull(SavedPlaces.decode("只有名字"))
        assertNull(SavedPlaces.decode("名字||不是数字|120|"))
    }

    @Test
    fun skips_a_bad_favorite_and_keeps_the_rest() {
        val good = PlaceTip("西湖", "杭州", 30.2, 120.1, "poi")
        val raw = "坏的一行\n${SavedPlaces.encode(good)}"

        assertEquals(listOf(good), SavedPlaces.decodeFavorites(raw))
    }

    @Test
    fun does_not_add_the_same_place_twice() {
        val first = PlaceTip("西湖", "杭州", 30.2, 120.1, "poi-1")
        val again = first.copy(detail = "换了一行说明")

        val added = SavedPlaces.addFavorite(listOf(first), again)

        assertFalse(added.added)
        assertEquals(listOf(first), added.favorites)
    }

    @Test
    fun newest_favorite_goes_first_and_the_oldest_drops_off() {
        val existing = (1..SavedPlaces.MAX_FAVORITES).map { index ->
            PlaceTip("地点$index", "", 30.0 + index, 120.0, "poi-$index")
        }
        val newest = PlaceTip("新地点", "", 31.0, 121.0, "poi-new")

        val added = SavedPlaces.addFavorite(existing, newest)

        assertTrue(added.added)
        assertEquals(SavedPlaces.MAX_FAVORITES, added.favorites.size)
        assertEquals(newest, added.favorites.first())
        assertFalse(added.favorites.any { it.poiId == "poi-${SavedPlaces.MAX_FAVORITES}" })
    }
}

package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSubtitleTest {
    @Test
    fun on_a_road_shows_city_district_and_road() {
        assertEquals(
            "杭州市余杭区天目山路",
            PlaceSubtitle.format(
                adminArea = "浙江省",
                subAdminArea = "杭州市",
                locality = "余杭区",
                thoroughfare = "天目山路128号",
                featureName = "肯德基"
            )
        )
    }

    @Test
    fun off_road_shows_city_and_village() {
        assertEquals(
            "临汾市安李村",
            PlaceSubtitle.format(
                locality = "临汾市",
                subAdminArea = "洪洞县",
                subLocality = "安李村"
            )
        )
    }

    @Test
    fun off_road_can_use_a_nearby_factory() {
        assertEquals(
            "临汾市华北水泥厂",
            PlaceSubtitle.format(
                locality = "临汾",
                featureName = "华北水泥厂"
            )
        )
        assertEquals(
            "临汾市华北建材",
            PlaceSubtitle.format(locality = "临汾市", premises = "华北建材")
        )
    }

    @Test
    fun district_alone_is_kept_when_nothing_finer_exists() {
        assertEquals(
            "杭州市余杭区",
            PlaceSubtitle.format(subAdminArea = "杭州市", locality = "余杭区")
        )
    }

    @Test
    fun full_address_line_is_shortened_to_city_district_and_road() {
        assertEquals(
            "杭州市余杭区天目山路",
            PlaceSubtitle.format(
                locality = "杭州市",
                addressLine = "中国浙江省杭州市余杭区天目山路128号"
            )
        )
    }

    @Test
    fun road_named_like_a_market_is_kept() {
        assertEquals(
            "杭州市市心路",
            PlaceSubtitle.format(locality = "杭州市", thoroughfare = "市心路")
        )
    }

    @Test
    fun plain_city_name_gets_a_city_suffix() {
        assertEquals("杭州市", PlaceSubtitle.format(locality = "杭州", adminArea = "浙江省"))
    }

    @Test
    fun layer_is_appended_to_the_place() {
        assertEquals("杭州市余杭区天目山路 隧道", PlaceSubtitle.withLayer("杭州市余杭区天目山路", "隧道"))
        assertEquals("杭州市余杭区天目山路", PlaceSubtitle.withLayer("杭州市余杭区天目山路", null))
        assertEquals("桥下", PlaceSubtitle.withLayer(null, "桥下"))
        assertNull(PlaceSubtitle.withLayer(null, null))
    }
}

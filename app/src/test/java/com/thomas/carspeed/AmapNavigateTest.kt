package com.thomas.carspeed

import org.junit.Assert.assertTrue
import org.junit.Test

class AmapNavigateTest {
    private val destination = PlaceTip(
        name = "杭州东站",
        detail = "",
        latitude = 30.290000,
        longitude = 120.210000,
        poiId = ""
    )

    @Test
    fun the_uri_targets_the_amap_route_plan_page() {
        val uri = AmapNavigate.planUri(destination)
        assertTrue(uri.startsWith("amapuri://route/plan/?"))
    }

    @Test
    fun the_destination_is_carried_with_full_precision() {
        val uri = AmapNavigate.planUri(destination)
        assertTrue(uri.contains("dlat=30.290000"))
        assertTrue(uri.contains("dlon=120.210000"))
    }

    @Test
    fun no_start_point_is_given_so_amap_uses_its_own_location() {
        val uri = AmapNavigate.planUri(destination)
        // 用户的要求是「起点用高德地图的当前位置」，所以不能带 slat/slon。
        assertTrue("不该带起点纬度", !uri.contains("slat="))
        assertTrue("不该带起点经度", !uri.contains("slon="))
    }

    @Test
    fun driving_mode_is_requested() {
        assertTrue(AmapNavigate.planUri(destination).contains("t=0"))
    }

    @Test
    fun a_place_name_with_special_characters_is_escaped() {
        val tricky = destination.copy(name = "A&B 大厦 #3")
        val uri = AmapNavigate.planUri(tricky)
        // 不转义的话 & 会被当成参数分隔，终点名只剩前半截。
        assertTrue(uri.contains("dname=A%26B"))
        assertTrue(!uri.contains("dname=A&B"))
    }

    @Test
    fun a_chinese_name_is_url_encoded() {
        val uri = AmapNavigate.planUri(destination)
        assertTrue("中文必须编码，否则部分机型调起失败", !uri.contains("杭州东站"))
    }
}

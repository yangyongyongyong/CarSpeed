package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRulesTest {

    @Test
    fun collapse_only_when_right_top_corner_is_near_right_edge_and_in_upper_half() {
        val screenW = 1080
        val screenH = 2400
        val edgeThreshold = 24
        val panelW = 340
        val panelH = 360

        // 命中：贴右边 + 右上角(topY)在上1/2区域
        assertTrue(
            OverlayRules.shouldCollapseToBubble(
                overlayLeftX = screenW - panelW - 10,
                overlayWidth = panelW,
                overlayTopY = 200,
                overlayHeight = panelH,
                screenWidth = screenW,
                screenHeight = screenH,
                edgeThresholdPx = edgeThreshold
            )
        )

        // 反例1：贴右边，但右上角超过上1/2区域
        assertFalse(
            OverlayRules.shouldCollapseToBubble(
                overlayLeftX = screenW - panelW - 10,
                overlayWidth = panelW,
                overlayTopY = 1300,
                overlayHeight = panelH,
                screenWidth = screenW,
                screenHeight = screenH,
                edgeThresholdPx = edgeThreshold
            )
        )

        // 反例2：在上1/2区域，但不贴右边
        assertFalse(
            OverlayRules.shouldCollapseToBubble(
                overlayLeftX = 300,
                overlayWidth = panelW,
                overlayTopY = 200,
                overlayHeight = panelH,
                screenWidth = screenW,
                screenHeight = screenH,
                edgeThresholdPx = edgeThreshold
            )
        )
    }
}

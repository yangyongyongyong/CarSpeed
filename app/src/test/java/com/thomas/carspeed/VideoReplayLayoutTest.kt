package com.thomas.carspeed

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class VideoReplayLayoutTest {
    @Test
    fun video_frame_fills_its_card_without_unsupported_layout_weight() {
        val layout = listOf(
            File("src/main/res/layout/activity_video_replay.xml"),
            File("app/src/main/res/layout/activity_video_replay.xml")
        ).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(layout)
        val nodes = document.getElementsByTagName("FrameLayout")
        val frame = (0 until nodes.length).map { nodes.item(it) as Element }
            .single { it.getAttributeNS(ANDROID, "id") == "@+id/videoFrame" }
        assertEquals("com.google.android.material.card.MaterialCardView", frame.parentNode.nodeName)
        assertEquals("match_parent", frame.getAttributeNS(ANDROID, "layout_height"))
        assertEquals("", frame.getAttributeNS(ANDROID, "layout_weight"))
    }

    @Test
    fun map_replay_transport_and_track_overlay_stack_instead_of_overlapping() {
        val layout = listOf(
            File("src/main/res/layout/activity_video_replay.xml"),
            File("app/src/main/res/layout/activity_video_replay.xml")
        ).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(layout)
        val byId = document.getElementsByTagName("LinearLayout").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
                .associateBy { it.getAttributeNS(ANDROID, "id") }
        }
        val transport = byId.getValue("@+id/layoutMapReplayControls")
        val overlay = byId.getValue("@+id/layoutMapBottomOverlay")
        val stack = transport.parentNode as Element
        assertEquals(stack, overlay.parentNode)
        assertEquals("vertical", stack.getAttributeNS(ANDROID, "orientation"))
        assertEquals("bottom", stack.getAttributeNS(ANDROID, "layout_gravity"))
        assertEquals(
            "both bars must stay direct children of the bottom stack",
            "@+id/layoutMapBottomStack",
            stack.getAttributeNS(ANDROID, "id")
        )
        assertEquals("", transport.getAttributeNS(ANDROID, "layout_gravity"))
        assertEquals("", overlay.getAttributeNS(ANDROID, "layout_gravity"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}

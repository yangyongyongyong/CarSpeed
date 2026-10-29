package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayRememberedTest {
    @Test
    fun preview_memory_keeps_rotation_and_map_choice_inside_bounds() {
        val memory = ReplayRemembered.sanitizePreview(
            rotationDegrees = -90,
            satellite = true,
            wholeRoute = true,
            scaleIndex = 40
        )
        assertEquals(270, memory.rotationDegrees)
        assertTrue(memory.satellite)
        assertTrue(memory.wholeRoute)
        assertEquals(ReplayRemembered.SCALE_INDEX_MAX, memory.scaleIndex)
    }

    @Test
    fun export_settings_keep_last_speed_fps_and_subtitle_fields() {
        val fields = ShareSubtitleFields(showAddress = false, showGps = false, showTime = true)
        val settings = ReplayRemembered.sanitizeExport(
            speedIndex = 99,
            fps = 48,
            fpsCustomized = true,
            includeMap = false,
            fields = fields
        )
        assertEquals(ExportFps.SPEEDS.lastIndex, settings.speedIndex)
        assertEquals(48, settings.fps)
        assertTrue(settings.fpsCustomized)
        assertFalse(settings.includeMap)
        assertEquals(fields, settings.fields)
    }

    @Test
    fun remembered_export_settings_keep_speed_and_fps_but_read_fields_from_settings() {
        val sanitized = ReplayRemembered.sanitizeExport(
            speedIndex = 2,
            fps = 30,
            fpsCustomized = false,
            includeMap = true,
            fields = ShareSubtitleFields(showSpeed = false, showStationaryWait = false)
        )
        assertEquals(2, sanitized.speedIndex)
        assertEquals(30, sanitized.fps)
        // 字幕开关只有设置页一份，回放记住的只是速度、帧率和是否带地图。
        assertTrue(sanitized.fields.showSpeed.not())
        assertTrue(sanitized.fields.showStationaryWait.not())
        assertTrue(sanitized.fields.showTime)
    }
}

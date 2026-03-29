package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionFormatterTest {

    @Test
    fun exact_cardinal_and_intercardinal() {
        assertEquals("北", DirectionFormatter.label(0f))
        assertEquals("东北", DirectionFormatter.label(45f))
        assertEquals("东", DirectionFormatter.label(90f))
        assertEquals("西北", DirectionFormatter.label(315f))
    }

    @Test
    fun near_cardinal_with_offset() {
        assertEquals("北偏东 22°", DirectionFormatter.label(23f))
        assertEquals("南偏西 20°", DirectionFormatter.label(200f))
    }
}

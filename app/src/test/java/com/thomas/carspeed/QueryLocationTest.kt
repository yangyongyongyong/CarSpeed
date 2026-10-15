package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryLocationTest {
    @Test
    fun empty_start_is_filled_with_the_current_location() {
        assertEquals(
            StartFill.PREFILL,
            QueryLocation.fillAction(
                startBlank = true,
                hasStartPlace = false,
                autoFilled = false,
                userEditedStart = false
            )
        )
    }

    @Test
    fun a_later_fix_only_updates_the_auto_filled_start() {
        assertEquals(
            StartFill.UPDATE,
            QueryLocation.fillAction(
                startBlank = false,
                hasStartPlace = true,
                autoFilled = true,
                userEditedStart = false
            )
        )
    }

    @Test
    fun user_edited_start_is_left_alone() {
        assertEquals(
            StartFill.LEAVE,
            QueryLocation.fillAction(
                startBlank = true,
                hasStartPlace = false,
                autoFilled = false,
                userEditedStart = true
            )
        )
        assertEquals(
            StartFill.LEAVE,
            QueryLocation.fillAction(
                startBlank = false,
                hasStartPlace = true,
                autoFilled = false,
                userEditedStart = true
            )
        )
    }
}

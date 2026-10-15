package com.thomas.carspeed

/** 查路线页收到定位后，起点要不要自动填上。 */
internal enum class StartFill {
    PREFILL,
    UPDATE,
    LEAVE
}

internal object QueryLocation {
    fun fillAction(
        startBlank: Boolean,
        hasStartPlace: Boolean,
        autoFilled: Boolean,
        userEditedStart: Boolean
    ): StartFill = when {
        autoFilled && hasStartPlace -> StartFill.UPDATE
        !userEditedStart && startBlank && !hasStartPlace -> StartFill.PREFILL
        else -> StartFill.LEAVE
    }
}

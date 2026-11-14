package com.nuvio.app.features.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A resting analogue stick that sits just past the configured dead zone used to read as a
 * permanently held D-pad, which spammed synthetic arrow keys (fighting the keyboard/remote) and
 * repeated seeks during playback. The gate needs extra travel to engage and the dead zone to release.
 */
class GamepadStickHysteresisTest {

    private val range = 32_767f
    private fun raw(fraction: Float) = (fraction * range).toInt()

    @Test
    fun aRestingStickJustPastTheDeadZoneDoesNotEngage() {
        val gate = GamepadInput.StickHysteresis()
        // 0.35 is above the 0.30 dead zone but inside the 0.10 enter margin.
        assertNull(gate.direction(0, raw(0.35f), 0, deadZone = 0.30f))
        assertNull(gate.direction(0, 0, raw(-0.38f), deadZone = 0.30f))
    }

    @Test
    fun aRealPushEngagesAndHoldsUntilTheDeadZone() {
        val gate = GamepadInput.StickHysteresis()
        assertEquals(StickDirection.Right, gate.direction(0, raw(0.55f), 0, deadZone = 0.30f))
        // Still held above the dead zone (but below the enter threshold).
        assertEquals(StickDirection.Right, gate.direction(0, raw(0.35f), 0, deadZone = 0.30f))
        // Released once back inside the dead zone.
        assertNull(gate.direction(0, raw(0.20f), 0, deadZone = 0.30f))
    }

    @Test
    fun resetClearsAConnectedSlotsDirection() {
        val gate = GamepadInput.StickHysteresis()
        assertEquals(StickDirection.Up, gate.direction(2, 0, raw(0.6f), deadZone = 0.30f))
        gate.reset(2)
        assertNull(gate.direction(2, raw(0.35f), 0, deadZone = 0.30f))
    }
}

package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A JVM method signature holds at most 255 slots (`this` included; Long and Double take two).
 * Kotlin's defaults constructor for [PlayerControlsState] adds an Int mask per 32 parameters and a
 * marker, so every field upstream or the Linux port adds counts toward that limit, and going over
 * makes the class fail to load (ClassFormatError) only at run time. The Umbra snapshot 148524e33
 * merge reached 256, and Umbra's b216ef64e..948c92bdd sync brought upstream itself to 255, so the Linux
 * port now adds nothing here: its MPRIS details are in [PlayerMediaSession].
 */
class PlayerControlsStateSignatureLimitTest {
    @Test
    fun `the defaults constructor stays within the JVM's 255 signature slots`() {
        val defaults = PlayerControlsState::class.java.declaredConstructors.maxBy { it.parameterCount }
        val slots = 1 + defaults.parameterTypes.sumOf { type ->
            if (type == java.lang.Long.TYPE || type == java.lang.Double.TYPE) 2L else 1L
        }
        println("PlayerControlsState defaults constructor: $slots/255 slots")
        assertTrue(slots <= 255, "PlayerControlsState needs $slots signature slots; group new fields into a holder")
    }
}

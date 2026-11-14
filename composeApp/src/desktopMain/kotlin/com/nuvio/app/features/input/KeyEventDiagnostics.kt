package com.nuvio.app.features.input

import java.awt.event.KeyEvent

/**
 * Opt-in keyboard diagnostics. Enable with `-Dnuvio.keyDiagnostics=true` or
 * `NUVIO_KEY_DIAGNOSTICS=1`; every line is prefixed `[key]` so it is easy to grep.
 *
 * It logs every [java.awt.KeyEvent] that reaches an AWT [java.awt.KeyEventDispatcher], before any
 * other dispatcher can consume it. A line that never appears for a pressed key means the key never
 * reached AWT at all — the desktop environment grabbed it first.
 */
internal object KeyEventDiagnostics {
    val enabled: Boolean by lazy {
        System.getProperty("nuvio.keyDiagnostics").toBoolean() ||
            System.getenv("NUVIO_KEY_DIAGNOSTICS") == "1"
    }

    private fun idName(id: Int): String = when (id) {
        KeyEvent.KEY_PRESSED -> "PRESSED"
        KeyEvent.KEY_RELEASED -> "RELEASED"
        KeyEvent.KEY_TYPED -> "TYPED"
        else -> "id=$id"
    }

    @Suppress("DEPRECATION")
    fun log(event: KeyEvent) {
        if (!enabled) return
        val keyChar = event.keyChar
        val keyCharText = if (keyChar == KeyEvent.CHAR_UNDEFINED) "UNDEFINED" else "'$keyChar'(0x${keyChar.code.toString(16)})"
        println(
            "[key] id=${idName(event.id)} keyCode=${event.keyCode} " +
                "extendedKeyCode=${event.extendedKeyCode} keyChar=$keyCharText " +
                "modifiers=${event.modifiers} modifiersEx=${event.modifiersEx}"
        )
    }
}

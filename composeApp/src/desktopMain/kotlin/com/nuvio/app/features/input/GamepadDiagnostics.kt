package com.nuvio.app.features.input

/**
 * Opt-in controller diagnostics. Enable with `-Dnuvio.gamepadDiagnostics=true` or
 * `NUVIO_GAMEPAD_DIAGNOSTICS=1`; every line is prefixed `[gamepad]` so it is easy to grep.
 *
 * It reports, in order: whether the app's own listener is enabled, whether the native bridge that
 * carries the Linux JNI entry points loaded (and if not, exactly why), which slots are connected,
 * the raw button set on each change, and the key each button translates to. That distinguishes a
 * missing bridge from a disabled setting from a controller Plasma itself consumed.
 */
internal object GamepadDiagnostics {
    val enabled: Boolean by lazy {
        System.getProperty("nuvio.gamepadDiagnostics").toBoolean() ||
            System.getenv("NUVIO_GAMEPAD_DIAGNOSTICS") == "1"
    }

    fun log(message: () -> String) {
        if (enabled) println("[gamepad] ${message()}")
    }
}

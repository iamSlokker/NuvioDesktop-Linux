package com.nuvio.app.features.locallibrary

/**
 * Settings that take a typed path (shaders, the pre-show folder) cannot reach anything from inside a
 * sandbox such as the Linux Flatpak, so there they also offer the system chooser. [required] is false
 * everywhere else, and those pages stay as they are.
 */
internal expect object SandboxPathPicker {
    val required: Boolean
    suspend fun pickFolder(): String?
    suspend fun pickShaderFile(): String?
}

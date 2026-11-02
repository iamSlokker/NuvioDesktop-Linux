package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopVideoSourceSize

/** One decoded video-params observation; the shader chain only needs its size. */
internal data class LinuxShaderMedia(val size: DesktopVideoSourceSize?) {
    companion object {
        const val EVENT_PREFIX = "linuxShaderMedia/"

        fun fromEvent(packedSize: Double): LinuxShaderMedia =
            LinuxShaderMedia(DesktopVideoSourceSize.unpack(packedSize))
    }
}

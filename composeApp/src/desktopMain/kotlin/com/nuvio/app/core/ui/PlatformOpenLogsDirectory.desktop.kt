package com.nuvio.app.core.ui

import com.nuvio.app.core.build.LinuxHostProcess
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.awt.Desktop
import java.nio.file.Files

actual fun platformOpenLogsDirectory() {
    runCatching {
        val logsDir = DesktopStorage.rootDir.resolve("logs")
        // Fresh installs may not have logged anything yet; Explorer errors on missing paths.
        Files.createDirectories(logsDir)
        if (DesktopHostOs.current == DesktopHostOs.LINUX) {
            val folder = logsDir.toFile()
            LinuxHostProcess.open(folder.absolutePath, fallback = { runCatching { Desktop.getDesktop().open(folder) } })
            return@runCatching
        }
        val opened = runCatching {
            ProcessBuilder("explorer.exe", logsDir.toAbsolutePath().toString()).start()
        }.isSuccess
        if (!opened) {
            Desktop.getDesktop().open(logsDir.toFile())
        }
    }
}

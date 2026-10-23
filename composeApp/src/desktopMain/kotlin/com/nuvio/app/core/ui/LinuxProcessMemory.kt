package com.nuvio.app.core.ui

import java.io.File

/**
 * Linux stand-in for Windows private bytes in the `(MemoryTrim)` log. On Linux the JVM's
 * committed virtual memory is VmSize, the whole reserved address space (around 113 GB once the
 * heap, Skia and thread stacks have reserved theirs), so a trim read from it shows nothing.
 * Private memory is the anonymous resident pages plus whatever of them is swapped out.
 */
internal object LinuxProcessMemory {
    fun privateBytes(status: File = File("/proc/self/status")): Long? =
        runCatching { parsePrivateBytes(status.readText()) }.getOrNull()

    internal fun parsePrivateBytes(status: String): Long? {
        val kilobytes = status.lineSequence()
            .mapNotNull { line -> STATUS_LINE.matchEntire(line.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }
        // RssAnon needs Linux 4.5; VmRSS (which also counts shared file pages) is the fallback.
        val anonymous = kilobytes["RssAnon"] ?: return kilobytes["VmRSS"]?.times(1024)
        return (anonymous + (kilobytes["VmSwap"] ?: 0L)) * 1024
    }

    private val STATUS_LINE = Regex("""(\w+):\s+(\d+)\s+kB""")
}

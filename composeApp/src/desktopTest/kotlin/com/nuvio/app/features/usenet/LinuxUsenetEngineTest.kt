package com.nuvio.app.features.usenet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxUsenetEngineTest {
    private val linux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    @Test
    fun theLinuxPackagesKeepTheEngineInLibNative() {
        if (linux) {
            assertEquals("nuvio-usenet", linuxUsenetEngineName)
            assertEquals(File("/opt/nuvio-desktop/lib/native"), linuxUsenetEngineDir(File("/opt/nuvio-desktop/lib")))
        } else {
            assertNull(linuxUsenetEngineName)
            assertNull(linuxUsenetEngineDir(File("C:/Nuvio")))
        }
    }

    /** Runs only where an engine is on PATH (tools/linux/packaging/usenet/build.py's output, for one). */
    @Test
    fun anEngineOnPathStartsAndStopsWithItsStdin() {
        if (!linux || usenetEngineExecutable() == null) return
        assertTrue(linuxUsenetEngineSelfTest().startsWith("protocol 1 on 127.0.0.1:"))
    }
}

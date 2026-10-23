package com.nuvio.app.core.ui

import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxProcessMemoryTest {
    private val status = """
        Name:	java
        VmPeak:	118734520 kB
        VmSize:	118603448 kB
        VmRSS:	 1843200 kB
        RssAnon:	 1536000 kB
        RssFile:	  301056 kB
        RssShmem:	    6144 kB
        VmSwap:	   20480 kB
        Threads:	97
    """.trimIndent()

    @Test
    fun privateMemoryIsAnonymousResidentPlusSwapNotTheAddressSpace() {
        assertEquals((1_536_000L + 20_480L) * 1024, LinuxProcessMemory.parsePrivateBytes(status))
    }

    @Test
    fun olderKernelsWithoutRssAnonFallBackToVmRss() {
        val old = status.lineSequence().filterNot { it.startsWith("RssAnon") }.joinToString("\n")
        assertEquals(1_843_200L * 1024, LinuxProcessMemory.parsePrivateBytes(old))
    }

    @Test
    fun unreadableStatusGivesNoReading() {
        assertNull(LinuxProcessMemory.parsePrivateBytes("Name:\tjava\n"))
        assertNull(LinuxProcessMemory.privateBytes(File("/nonexistent/nuvio/status")))
    }

    @Test
    fun readsThisProcessOnLinux() {
        assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))
        val bytes = assertNotNull(LinuxProcessMemory.privateBytes())
        // A test JVM uses well under 64 GB of private memory; VmSize is routinely above that.
        assertTrue(bytes in 1L..(64L shl 30), "private bytes $bytes")
    }
}

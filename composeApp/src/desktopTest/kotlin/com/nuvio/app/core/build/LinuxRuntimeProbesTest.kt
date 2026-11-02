package com.nuvio.app.core.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The probes' own logic, on a JVM where every registration holds: a probe that fails here would
 * block a release for nothing. What only an image can lose is the smoke gate's to find.
 */
class LinuxRuntimeProbesTest {
    @Test
    fun everyProbePassesOnAJvm() {
        val results = linkedMapOf<String, Result<String>>()
        LinuxRuntimeProbes.run { name, check -> results[name] = runCatching(check) }
        assertEquals(
            listOf(
                "linux tls", "linux unix socket", "linux xml", "linux normalizer",
                "linux jca", "linux audio output", "linux library serializers",
            ),
            results.keys.toList(),
        )
        results.forEach { (name, result) -> assertTrue(result.isSuccess, "$name: $result") }
    }
}

package com.nuvio.app.features.music.potoken

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxWebKitBotGuardTest {
    @Test
    fun pageRefusesEveryRequestAndKeepsTheBotGuardPage() {
        val html = LinuxWebKitBotGuard.pageHtml()
        val policy = Regex("""<meta http-equiv="Content-Security-Policy" content="([^"]*)">""").findAll(html).toList()
        assertEquals(1, policy.size, "exactly one policy")
        assertEquals("default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'", policy.single().groupValues[1])
        // Before the page's own script, so it applies to everything the script does.
        assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf("<script>"))
        assertEquals(BotGuardPage.HTML, html.replace(policy.single().value, ""))
    }
}

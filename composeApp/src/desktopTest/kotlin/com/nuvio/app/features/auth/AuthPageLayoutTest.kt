package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.qr.QrCodeEncoder
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The real sign-in screen, measured at the window sizes it actually runs in.
 *
 * These are the constraints the layout has to satisfy together, and each one is a defect that was
 * visible in a real window: the page fits without scrolling, the brand keeps the left half and the
 * cards sit in the rail on the right, the two cards are the same height, and each card is wide
 * enough that its text stays on a line instead of wrapping into a sliver. Rendering [AuthScreen]
 * itself (not a stand-in) is what makes the numbers meaningful — a replica drifted from the real
 * card while this was being built.
 *
 * Skia only: no AWT peer, window, GTK or DISPLAY connection.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class AuthPageLayoutTest {

    /** Window sizes the wide layout is designed for: a laptop, a desktop, and a large TV panel. */
    private val wideSizes = listOf(1280 to 820, 1440 to 900, 1920 to 1080)

    /** Below [WideLayoutMinWidth] the page becomes one column; both cards still have to fit. */
    private val narrowSizes = listOf(1024 to 768, 900 to 700)

    /** A control's bounds in pixels. Pixels, not dp, because the cards sit under a raised density. */
    private fun ComposeUiTest.px(tag: String): androidx.compose.ui.geometry.Rect =
        onAllNodesWithTag(tag, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot

    private fun authPage(width: Int, height: Int, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        AuthScreen(modifier = Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()
            block()
        }

    @Test
    fun `everything fits on one screen in the wide layout`() {
        for ((w, h) in wideSizes) {
            authPage(w, h) {
                val window = onRoot().fetchSemanticsNode().boundsInRoot
                val action = px(AuthContinueWithoutAccountTag)
                assertTrue(
                    action.bottom <= window.bottom,
                    "${w}x$h: the action row reaches ${action.bottom}px, past the window's ${window.bottom}",
                )
                assertTrue(action.top >= window.top, "${w}x$h: the action row starts above the window")
                onNodeWithTag(AuthFormCardTag).assertIsDisplayed()
                onAllNodesWithTag(AuthCodeCardTag).onFirst().assertIsDisplayed()
            }
        }
    }

    @Test
    fun `the narrow layout scrolls rather than clipping the action row`() {
        for ((w, h) in narrowSizes) {
            authPage(w, h) {
                // The documented fallback below the breakpoint: one column that scrolls. What must
                // not happen is a control being dropped from the page.
                onNodeWithTag(AuthFormCardTag).assertIsDisplayed()
                onAllNodesWithTag(AuthCodeCardTag).onFirst().assertExists()
                onNodeWithTag(AuthContinueWithoutAccountTag).assertExists()
            }
        }
    }

    @Test
    fun `the brand keeps the left and the cards sit in the rail on the right`() {
        for ((w, h) in wideSizes) {
            authPage(w, h) {
                val window = onRoot().fetchSemanticsNode().boundsInRoot
                val brand = px(AuthBrandTag)
                val form = px(AuthFormCardTag)
                val code = px(AuthCodeCardTag)

                assertTrue(
                    brand.right < form.left,
                    "${w}x$h: the brand runs into the form card: ${brand.right} vs ${form.left}",
                )
                assertTrue(
                    form.left > window.width * 0.3f,
                    "${w}x$h: the cards are not in a rail: form starts at ${form.left} of ${window.width}",
                )
                assertTrue(
                    code.right <= window.right,
                    "${w}x$h: the code card runs past the window: ${code.right}",
                )
            }
        }
    }

    @Test
    fun `below the wide breakpoint the cards stack instead of shrinking`() {
        for ((w, h) in narrowSizes) {
            authPage(w, h) {
                val form = onNodeWithTag(AuthFormCardTag).getBoundsInRoot()
                val code = onAllNodesWithTag(AuthCodeCardTag).onFirst().getBoundsInRoot()
                assertTrue(
                    code.top.value >= form.bottom.value - 0.5f,
                    "${w}x$h: the code card is not below the form: ${code.top} vs ${form.bottom}",
                )
            }
        }
    }

    @Test
    fun `neither card collapses around its content`() {
        for ((w, h) in wideSizes) {
            authPage(w, h) {
                val form = px(AuthFormCardTag)
                val code = px(AuthCodeCardTag)
                for (field in listOf(AuthEmailFieldTag, AuthPasswordFieldTag)) {
                    val bounds = px(field)
                    assertTrue(
                        bounds.left >= form.left && bounds.right <= form.right,
                        "${w}x$h: '$field' spills out of the form card: $bounds vs $form",
                    )
                }
                val panel = px(QrPanelTag)
                assertTrue(
                    panel.left >= code.left && panel.right <= code.right,
                    "${w}x$h: the pairing panel spills out of its card: $panel vs $code",
                )
            }
        }
    }

    @Test
    fun `the two cards are the same height`() = runDesktopComposeUiTest(width = 1400, height = 900) {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AuthScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }

        // Layout bounds, not pixels: a card's glass is nearly the same colour as the page behind it,
        // so no pixel heuristic can tell its edges apart reliably.
        val form = onNodeWithTag(AuthFormCardTag).getBoundsInRoot()
        val code = onAllNodesWithTag(AuthCodeCardTag).onFirst().getBoundsInRoot()

        assertTrue(
            kotlin.math.abs(form.top.value - code.top.value) < 2f,
            "cards start at different rows: ${form.top} vs ${code.top}",
        )
        assertTrue(
            kotlin.math.abs(form.bottom.value - code.bottom.value) < 2f,
            "cards end at different rows: ${form.bottom} vs ${code.bottom}",
        )
    }

    @Test
    fun `a waiting code card is never shorter than its own content`() = runDesktopComposeUiTest(width = 900, height = 820) {
        // The regression this exists for: the code card was laid out at the *form's* height, so the
        // bottom of the panel — the countdown and the refresh button — was cut off. The card is
        // driven here in the real Waiting state, next to real form content, through the same
        // equal-height container the page uses.
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(860.dp, 800.dp).background(Color.Black)) {
                    EqualHeightCards(
                        modifier = Modifier.fillMaxWidth(),
                        gap = 14.dp,
                        firstWeight = 1.2f,
                        first = { cardModifier ->
                            NuvioSurfaceCard(cardModifier.testTag(AuthFormCardTag)) {
                                androidx.compose.material3.Text("Welcome Back")
                            }
                        },
                        second = { cardModifier ->
                            NuvioSurfaceCard(cardModifier.testTag(AuthCodeCardTag)) {
                                QrLoginPanel(state = waitingState(), onRefresh = {})
                            }
                        },
                    )
                }
            }
        }

        onAllNodesWithTag(QrRefreshTag).onFirst().assertIsDisplayed()
        onAllNodesWithTag(QrCountdownTag).onFirst().assertIsDisplayed()

        val card = onNodeWithTag(AuthCodeCardTag).getBoundsInRoot()
        val refresh = onAllNodesWithTag(QrRefreshTag).onFirst().getBoundsInRoot()
        val countdown = onAllNodesWithTag(QrCountdownTag).onFirst().getBoundsInRoot()
        assertTrue(
            refresh.bottom.value <= card.bottom.value + 0.5f,
            "refresh button is clipped by the code card: ${refresh.bottom} > ${card.bottom}",
        )
        assertTrue(
            countdown.bottom.value <= card.bottom.value + 0.5f,
            "countdown is clipped by the code card: ${countdown.bottom} > ${card.bottom}",
        )
    }
}

/**
 * A pairing code in the state the page shows while waiting for a phone: symbol present, deadline
 * set, countdown running. Built here rather than through the controller because this test is about
 * layout, not pairing.
 */
private fun waitingState(): QrLoginUiState = QrLoginUiState(
    phase = QrLoginPhase.Waiting,
    matrix = QrCodeEncoder.encode("https://nuvio.tv/link?code=NHGSVP").getOrThrow(),
    userCode = "NHGSVP",
    expiresAtMillis = System.currentTimeMillis() + 120_000,
)

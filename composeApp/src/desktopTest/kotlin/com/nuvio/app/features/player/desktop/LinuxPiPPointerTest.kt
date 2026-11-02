package com.nuvio.app.features.player.desktop

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.*

class LinuxPiPPointerTest {
    @Test fun eightHandlesAroundThePicture() {
        val w = 480; val h = 270
        assertEquals(8, linuxPiPResizeEdge(0, 0, w, h))
        assertEquals(1, linuxPiPResizeEdge(240, 0, w, h))
        assertEquals(2, linuxPiPResizeEdge(479, 0, w, h))
        assertEquals(3, linuxPiPResizeEdge(479, 135, w, h))
        assertEquals(4, linuxPiPResizeEdge(479, 269, w, h))
        assertEquals(5, linuxPiPResizeEdge(240, 269, w, h))
        assertEquals(6, linuxPiPResizeEdge(0, 269, w, h))
        assertEquals(7, linuxPiPResizeEdge(0, 135, w, h))
        assertEquals(0, linuxPiPResizeEdge(240, 135, w, h))
        assertEquals(0, linuxPiPResizeEdge(LinuxPiPResizeZone, LinuxPiPResizeZone, w, h))
    }
    @Test fun resizingKeepsTheOppositeSideStill() {
        val start = Rectangle(100, 100, 480, 270)
        assertEquals(Rectangle(100, 100, 500, 270), linuxPiPResizedBounds(start, 3, 20, 99))
        assertEquals(Rectangle(100, 100, 480, 290), linuxPiPResizedBounds(start, 5, 99, 20))
        assertEquals(Rectangle(80, 100, 500, 270), linuxPiPResizedBounds(start, 7, -20, 99))
        assertEquals(Rectangle(100, 80, 480, 290), linuxPiPResizedBounds(start, 1, 99, -20))
        assertEquals(Rectangle(100, 100, 490, 280), linuxPiPResizedBounds(start, 4, 10, 10))
        assertEquals(Rectangle(90, 90, 490, 280), linuxPiPResizedBounds(start, 8, -10, -10))
        assertEquals(Rectangle(100, 90, 490, 280), linuxPiPResizedBounds(start, 2, 10, -10))
        assertEquals(Rectangle(90, 100, 490, 280), linuxPiPResizedBounds(start, 6, -10, 10))
    }
    @Test fun aWindowDoesNotShrinkPastTheSmallestPictureOrWalkAway() {
        val start = Rectangle(100, 100, 480, 270)
        assertEquals(Rectangle(100, 100, 240, 135), linuxPiPResizedBounds(start, 4, -900, -900))
        assertEquals(Rectangle(340, 235, 240, 135), linuxPiPResizedBounds(start, 8, 900, 900))
    }
    @Test fun everyHandleHasItsCursor() {
        assertEquals(8, (1..8).map(::linuxPiPResizeCursor).distinct().size)
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, linuxPiPResizeCursor(0))
    }
    @Test fun dragOnAHandleResizesAndIsNeverAClick() {
        val sizes = mutableListOf<Rectangle>()
        var toggles = 0
        val policy = LinuxPiPPointer({ toggles++ }, {}, { error("not a move") }, { 0L }, { _, action -> action(); {} }, { sizes += it })
        policy.setActive(true)
        policy.pressResize(Point(10, 10), Rectangle(0, 0, 480, 270), 4)
        policy.motion(Point(30, 30))
        policy.release(Point(40, 40))
        assertEquals(listOf(Rectangle(0, 0, 500, 290), Rectangle(0, 0, 510, 300)), sizes)
        assertEquals(0, toggles)
    }
    @Test fun aHandlePressWithoutMovementDoesNotToggle() {
        var toggles = 0
        val policy = LinuxPiPPointer({ toggles++ }, {}, {}, { 0L }, { _, action -> action(); {} }, {})
        policy.setActive(true)
        policy.pressResize(Point(10, 10), Rectangle(0, 0, 480, 270), 3)
        policy.release(Point(10, 10))
        assertEquals(0, toggles)
    }
    @Test fun cancellingAResizeStopsIt() {
        val sizes = mutableListOf<Rectangle>()
        val policy = LinuxPiPPointer({}, {}, {}, { 0L }, { _, action -> action(); {} }, { sizes += it })
        policy.setActive(true)
        policy.pressResize(Point(10, 10), Rectangle(0, 0, 480, 270), 3)
        policy.reset()
        policy.motion(Point(30, 30))
        assertTrue(sizes.isEmpty())
    }
    private class Task(val action: () -> Unit, var cancelled: Boolean = false)
    private class Fixture {
        var time = 0L
        var toggles = 0
        var exits = 0
        val moves = mutableListOf<Point>()
        val tasks = mutableListOf<Task>()
        val policy = LinuxPiPPointer({ toggles++ }, { exits++ }, { moves.add(it) }, { time }, { delay, action ->
            assertEquals(220, delay)
            val task = Task(action); tasks.add(task)
            val cancel: () -> Unit = { task.cancelled = true }; cancel
        })
        init { policy.setActive(true) }
        fun click() { policy.press(Point(10, 10), Point(100, 100)); policy.release(Point(10, 10)) }
        fun fire(stale: Boolean = false) { tasks.toList().forEach { if (stale || !it.cancelled) it.action() }; tasks.clear() }
    }
    @Test fun firstClickWaitsThenTogglesExactlyOnce() {
        val f = Fixture(); f.click(); assertEquals(0, f.toggles)
        f.fire(); assertEquals(1, f.toggles); f.fire(); assertEquals(1, f.toggles)
    }
    @Test fun secondPressBeforeDeadlineCancelsSingleEvenIfReleaseIsLater() {
        val f = Fixture(); f.click(); f.time = 219
        f.policy.press(Point(10, 10), Point(100, 100)); f.fire(stale = true)
        f.time = 300; f.policy.release(Point(10, 10))
        assertEquals(0, f.toggles); assertEquals(1, f.exits)
    }
    @Test fun doubleClickAndRapidThirdClickOnlyExit() {
        val f = Fixture(); f.click(); f.time = 100; f.click(); f.click(); f.fire(stale = true)
        assertEquals(0, f.toggles); assertEquals(1, f.exits)
    }
    @Test fun secondClickOutsideIntervalIsAnotherSingle() {
        val f = Fixture(); f.click(); f.time = 221; f.fire(); f.click(); f.fire()
        assertEquals(2, f.toggles); assertEquals(0, f.exits)
    }
    @Test fun belowThresholdRemainsClick() {
        val f = Fixture(); f.policy.press(Point(10, 10), Point(100, 100))
        f.policy.motion(Point(15, 10)); f.policy.release(Point(15, 10)); f.fire()
        assertTrue(f.moves.isEmpty()); assertEquals(1, f.toggles)
    }
    @Test fun dragCancelsPendingClickAndReleaseCannotToggle() {
        val f = Fixture(); f.click(); f.time = 100
        f.policy.press(Point(10, 10), Point(100, 100))
        f.policy.motion(Point(16, 10)); f.policy.motion(Point(20, 15)); f.policy.release(Point(20, 15))
        f.fire(stale = true)
        assertEquals(Point(110, 105), f.moves.last()); assertEquals(0, f.toggles); assertEquals(0, f.exits)
    }
    @Test fun physicalThresholdAccountsForScaleAndReleaseMovement() {
        val f = Fixture(); f.policy.press(Point(10, 10), Point(100, 100), 2.0, 2.0)
        f.policy.release(Point(13, 10)); f.fire()
        assertEquals(listOf(Point(103, 100)), f.moves); assertEquals(0, f.toggles)
    }
    @Test fun exitCancelsPendingAndStaleCallback() {
        val f = Fixture(); f.click(); f.policy.setActive(false); f.fire(stale = true)
        assertEquals(0, f.toggles)
    }
    @Test fun disposalCancelsPendingAndCannotReactivate() {
        val f = Fixture(); f.click(); f.policy.close(); f.policy.setActive(true); f.click(); f.fire(stale = true)
        assertEquals(0, f.toggles); assertEquals(0, f.exits)
    }
    @Test fun focusLossCancelsGestureWithoutDeactivatingSession() {
        val f = Fixture(); f.click(); f.policy.reset(); f.fire(stale = true)
        assertEquals(0, f.toggles); f.click(); f.fire(); assertEquals(1, f.toggles)
    }
    @Test fun newSessionRejectsOldCallbacksAndResetsState() {
        val f = Fixture(); f.click(); val old = f.tasks.single()
        f.policy.setActive(false); f.policy.setActive(true); f.click()
        old.action(); assertEquals(0, f.toggles); f.fire(); assertEquals(1, f.toggles)
    }
    @Test fun repeatedActivationDoesNotCancelValidSingle() {
        val f = Fixture(); f.click(); repeat(5) { f.policy.setActive(true) }; f.fire()
        assertEquals(1, f.toggles)
    }
    @Test fun oneListenerPerControllerAndCleanReplacementWithoutAPeer() {
        val host = NativePlayerHost() // A Canvas object only: no addNotify, peer, window or display.
        val first = LinuxPiPSurfaceInput.install(DesktopHostOs.LINUX, host) {}!!
        repeat(5) { host.linuxPiPInteractive = true }
        assertEquals(1, host.mouseListeners.size); assertEquals(1, host.mouseMotionListeners.size)
        first.close(); assertEquals(0, host.mouseListeners.size)
        val second = LinuxPiPSurfaceInput.install(DesktopHostOs.LINUX, host) {}!!
        assertEquals(1, host.mouseListeners.size); second.close(); assertEquals(0, host.mouseMotionListeners.size)
    }
    @Test fun nonLinuxInstallsNothingRegardlessOfFlags() {
        val host = NativePlayerHost()
        DesktopHostOs.entries.filter { it != DesktopHostOs.LINUX }.forEach {
            assertNull(LinuxPiPSurfaceInput.install(it, host) {})
        }
        assertTrue(host.mouseListeners.isEmpty()); assertTrue(host.mouseMotionListeners.isEmpty())
    }
}

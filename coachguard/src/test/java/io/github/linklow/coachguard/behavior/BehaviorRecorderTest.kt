package io.github.linklow.coachguard.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BehaviorRecorderTest {

    @Test
    fun `focused session with two taps`() {
        val recorder = BehaviorRecorder(startedAt = 0, initiallyFocused = true)
        recorder.onTouchDown(1_000)
        recorder.onTouchUp(1_100, downTime = 1_000)
        recorder.onTouchDown(3_000)
        recorder.onTouchUp(3_150, downTime = 3_000)

        val features = recorder.snapshot(now = 3_200)

        assertEquals(3_200L, features.timeToConfirmMillis)
        assertEquals(0, features.focusLosses)
        assertEquals(0L, features.timeAwayMillis)
        assertEquals(2, features.touches)
        assertEquals(2_000L, features.longestPauseMillis) // between the taps
        assertEquals(125.0, features.meanTouchDurationMillis!!, 1e-9)
    }

    @Test
    fun `leaving the app counts as a focus loss and time away, not as a pause`() {
        // Session starts in onCreate, before the window has focus.
        val recorder = BehaviorRecorder(startedAt = 0, initiallyFocused = false)
        recorder.onFocusChanged(hasFocus = true, now = 300)
        recorder.onTouchDown(1_300)
        recorder.onFocusChanged(hasFocus = false, now = 2_000) // switches to a messenger
        recorder.onFocusChanged(hasFocus = true, now = 32_000) // comes back
        recorder.onTouchDown(33_000)
        recorder.onTouchUp(33_080, downTime = 33_000)

        val features = recorder.snapshot(now = 33_100)

        assertEquals(1, features.focusLosses)
        assertEquals(30_000L, features.timeAwayMillis)
        assertEquals(2, features.touches)
        // Pauses are measured only while focused: 300→1300, 1300→2000, 32000→33000, 33000→33100.
        assertEquals(1_000L, features.longestPauseMillis)
        assertEquals(80.0, features.meanTouchDurationMillis!!, 1e-9)
    }

    @Test
    fun `time away still running at snapshot is included`() {
        val recorder = BehaviorRecorder(startedAt = 0, initiallyFocused = true)
        recorder.onFocusChanged(hasFocus = false, now = 5_000)

        val features = recorder.snapshot(now = 9_000)

        assertEquals(1, features.focusLosses)
        assertEquals(4_000L, features.timeAwayMillis)
        assertEquals(5_000L, features.longestPauseMillis)
    }

    @Test
    fun `initial unfocused period is neither time away nor a focus loss`() {
        val recorder = BehaviorRecorder(startedAt = 0, initiallyFocused = false)
        recorder.onFocusChanged(hasFocus = true, now = 400)

        val features = recorder.snapshot(now = 1_000)

        assertEquals(0, features.focusLosses)
        assertEquals(0L, features.timeAwayMillis)
        assertEquals(600L, features.longestPauseMillis)
    }

    @Test
    fun `repeated focus event does not restart the current pause`() {
        val recorder = BehaviorRecorder(startedAt = 0, initiallyFocused = true)
        recorder.onTouchDown(1_000)
        recorder.onFocusChanged(hasFocus = true, now = 5_000)

        val features = recorder.snapshot(now = 6_000)

        assertEquals(5_000L, features.longestPauseMillis)
        assertEquals(0, features.focusLosses)
    }

    @Test
    fun `long presses and broken timestamps are left out of touch duration`() {
        val recorder = BehaviorRecorder(startedAt = 0, initiallyFocused = true)
        recorder.onTouchDown(100)
        recorder.onTouchUp(5_100, downTime = 100) // 5 s long press
        recorder.onTouchUp(50, downTime = 100) // up before down

        val features = recorder.snapshot(now = 6_000)

        assertEquals(1, features.touches)
        assertNull(features.meanTouchDurationMillis)
    }

    @Test
    fun `no touches gives no touch duration`() {
        val features = BehaviorRecorder(startedAt = 0, initiallyFocused = true).snapshot(now = 2_000)

        assertEquals(0, features.touches)
        assertNull(features.meanTouchDurationMillis)
        assertEquals(2_000L, features.longestPauseMillis)
    }
}

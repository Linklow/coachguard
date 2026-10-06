package io.github.linklow.coachguard.behavior

import kotlin.math.max

/**
 * Accumulates [BehaviorFeatures] from window events. Times are in milliseconds on one monotonic
 * clock (on Android, `SystemClock.uptimeMillis`, the clock `MotionEvent` timestamps use).
 *
 * Pure Kotlin and not thread-safe; the Android layer feeds it from the main thread.
 */
internal class BehaviorRecorder(private val startedAt: Long, initiallyFocused: Boolean) {

    private var focused = initiallyFocused
    private var focusLosses = 0
    private var timeAway = 0L
    private var awaySince = NOT_SET

    // Start of the current stretch without interaction while focused: the last touch or the
    // moment focus returned. NOT_SET while the screen has not had focus yet.
    private var lastInteraction = if (initiallyFocused) startedAt else NOT_SET
    private var longestPause = 0L

    private var touches = 0
    private var completedTouches = 0
    private var touchDurationSum = 0L

    fun onFocusChanged(hasFocus: Boolean, now: Long) {
        if (hasFocus == focused) return
        focused = hasFocus
        if (hasFocus) {
            if (awaySince != NOT_SET) {
                timeAway += now - awaySince
                awaySince = NOT_SET
            }
            lastInteraction = now
        } else {
            focusLosses++
            awaySince = now
            closePause(now)
            lastInteraction = NOT_SET
        }
    }

    fun onTouchDown(eventTime: Long) {
        touches++
        if (focused) {
            closePause(eventTime)
            lastInteraction = eventTime
        }
    }

    fun onTouchUp(eventTime: Long, downTime: Long) {
        val duration = eventTime - downTime
        if (duration in 0..MAX_TOUCH_DURATION_MILLIS) {
            completedTouches++
            touchDurationSum += duration
        }
    }

    fun snapshot(now: Long): BehaviorFeatures {
        val ongoingAway = if (!focused && awaySince != NOT_SET) now - awaySince else 0L
        val ongoingPause = if (focused && lastInteraction != NOT_SET) now - lastInteraction else 0L
        return BehaviorFeatures(
            timeToConfirmMillis = (now - startedAt).coerceAtLeast(0L),
            focusLosses = focusLosses,
            timeAwayMillis = (timeAway + ongoingAway).coerceAtLeast(0L),
            touches = touches,
            longestPauseMillis = max(longestPause, ongoingPause).coerceAtLeast(0L),
            meanTouchDurationMillis = if (completedTouches > 0) {
                touchDurationSum.toDouble() / completedTouches
            } else {
                null
            },
        )
    }

    private fun closePause(now: Long) {
        if (lastInteraction != NOT_SET) longestPause = max(longestPause, now - lastInteraction)
    }

    private companion object {
        const val NOT_SET = Long.MIN_VALUE

        // Longer contacts are long presses or slow drags rather than taps; they would dominate
        // the average without saying much about the user's touch rhythm.
        const val MAX_TOUCH_DURATION_MILLIS = 2_000L
    }
}

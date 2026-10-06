package io.github.linklow.coachguard.signals

/** Whether the host app's screen is being recorded or shared. */
public enum class ScreenCaptureState {
    /** The app's windows are not visible in any screen recording or screen share. */
    NOT_CAPTURED,

    /** The app's windows are visible in a screen recording or screen share. */
    CAPTURED,

    /**
     * Not observable: Android below 15, no active [io.github.linklow.coachguard.GuardSession],
     * or the host app removed the `DETECT_SCREEN_RECORDING` permission.
     */
    UNKNOWN,
}

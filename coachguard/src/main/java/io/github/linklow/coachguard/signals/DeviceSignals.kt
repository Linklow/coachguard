package io.github.linklow.coachguard.signals

import io.github.linklow.coachguard.behavior.BehaviorAnalysis

/**
 * Raw observations at one moment. All of them are collected on the device and none of them leave
 * it unless the host app sends them somewhere.
 */
public data class DeviceSignals(
    public val callState: CallState,
    public val screenCapture: ScreenCaptureState,
    /** Installed apps that let another person see or control this device, by package name. */
    public val remoteAccessApps: List<String>,
    /** Accessibility services the user has enabled. */
    public val accessibilityServices: List<AccessibilityServiceSignal>,
    /**
     * Touches that reached the app while another app's window covered the touched point
     * (a tapjacking pattern). Counted only during a [io.github.linklow.coachguard.GuardSession].
     */
    public val obscuredTouchCount: Int,
    /**
     * Touches that reached the app while another app's window covered some other part of the
     * screen. Common with harmless overlays such as chat heads, so it is reported but not scored.
     */
    public val partiallyObscuredTouchCount: Int,
    /** USB or wireless debugging is enabled. `null` if the setting could not be read. */
    public val adbEnabled: Boolean?,
    /** Wall-clock time of collection, in milliseconds since the epoch. */
    public val collectedAtMillis: Long,
    /**
     * How the user's interaction compares with their baseline. `null` outside a
     * [io.github.linklow.coachguard.GuardSession].
     */
    public val behavior: BehaviorAnalysis? = null,
)

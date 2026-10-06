package io.github.linklow.coachguard.signals

/** An accessibility service that the user has enabled on the device. */
public data class AccessibilityServiceSignal(
    public val packageName: String,
    public val serviceName: String,
    /** The service ships with the system image (or is an update to a system app). */
    public val isSystemApp: Boolean,
    /** The service can read the content of other apps' windows. */
    public val canRetrieveWindowContent: Boolean,
    /** The service can tap, swipe and type on the user's behalf. */
    public val canPerformGestures: Boolean,
    /**
     * The service declares `android:isAccessibilityTool="true"`, which Google Play allows only for
     * apps whose primary purpose is helping people with disabilities.
     * `null` below Android 12, where this attribute cannot be read.
     */
    public val isAccessibilityTool: Boolean?,
)

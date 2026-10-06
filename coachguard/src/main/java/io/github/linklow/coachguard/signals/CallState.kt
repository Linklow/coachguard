package io.github.linklow.coachguard.signals

/**
 * Whether the device is on a call, derived from the system audio mode.
 *
 * Reading the audio mode needs no permission and also covers VoIP calls (WhatsApp, Telegram,
 * Viber, ...), which scammers increasingly use instead of regular phone calls.
 */
public enum class CallState {
    /** No call in progress. */
    NONE,

    /** The phone is ringing or an incoming call is being screened. */
    RINGING,

    /** A cellular call is in progress. */
    CELLULAR_CALL,

    /**
     * A VoIP call is in progress. Note that the host app's own voice or video features also
     * put the device in this mode.
     */
    VOIP_CALL,

    /** The call state could not be read. */
    UNKNOWN;

    /** `true` for an active cellular or VoIP call. */
    public val isInCall: Boolean
        get() = this == CELLULAR_CALL || this == VOIP_CALL
}

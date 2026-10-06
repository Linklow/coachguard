package io.github.linklow.coachguard.risk

/** Stable identifiers of the rules in [RiskEngine]. Safe to use for analytics and UI copy. */
public enum class ReasonCode {
    /** A cellular or VoIP call is in progress. */
    ACTIVE_CALL,

    /** The app's screen is being recorded or shared. */
    SCREEN_CAPTURED,

    /** A remote-access app is installed, but nothing shows it can control the screen right now. */
    REMOTE_ACCESS_APP_INSTALLED,

    /** A remote-access app has an enabled accessibility service, so it can control the screen. */
    REMOTE_CONTROL_ENABLED,

    /**
     * A third-party accessibility service that is not a declared accessibility tool can read or
     * control the screen. Banking trojans commonly use this.
     */
    UNTRUSTED_ACCESSIBILITY_SERVICE,

    /** The user tapped while another app's window covered the touched point. */
    OBSCURED_TOUCH,

    /** USB or wireless debugging is enabled. */
    ADB_ENABLED,

    /** The user is interacting with the screen differently from their learned baseline. */
    BEHAVIOR_ANOMALY,

    /**
     * Unusual interaction during a call: long pauses, leaving the app and coming back, as when
     * someone on the line is dictating what to do.
     */
    COACHED_BEHAVIOR_DURING_CALL,

    /** The transfer goes to a new payee. */
    NEW_PAYEE,

    /** The transfer amount is unusual for this user. */
    UNUSUAL_AMOUNT,

    /** Transfer to a new payee during a call: the core pattern of impersonation scams. */
    CALL_DURING_NEW_PAYEE_TRANSFER,

    /** Transfer while the screen is shared, remotely controlled or covered by an overlay. */
    SCREEN_COMPROMISED_DURING_TRANSFER,
}

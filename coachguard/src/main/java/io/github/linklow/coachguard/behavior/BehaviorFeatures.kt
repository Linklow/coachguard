package io.github.linklow.coachguard.behavior

/**
 * How the user interacted with a guarded screen, measured from when the session started until
 * the assessment. Only these aggregates are kept; individual touches are never stored.
 */
public data class BehaviorFeatures(
    public val timeToConfirmMillis: Long,
    public val focusLosses: Int,
    public val timeAwayMillis: Long,
    public val touches: Int,
    public val longestPauseMillis: Long,
    /** `null` when no touch was completed during the session. */
    public val meanTouchDurationMillis: Double?,
) {
    /** The value of [feature], or `null` if it could not be measured in this session. */
    public fun valueOf(feature: BehaviorFeature): Double? = when (feature) {
        BehaviorFeature.TIME_TO_CONFIRM -> timeToConfirmMillis.toDouble()
        BehaviorFeature.FOCUS_LOSSES -> focusLosses.toDouble()
        BehaviorFeature.TIME_AWAY -> timeAwayMillis.toDouble()
        BehaviorFeature.TOUCHES -> touches.toDouble()
        BehaviorFeature.LONGEST_PAUSE -> longestPauseMillis.toDouble()
        BehaviorFeature.TOUCH_DURATION -> meanTouchDurationMillis
    }
}

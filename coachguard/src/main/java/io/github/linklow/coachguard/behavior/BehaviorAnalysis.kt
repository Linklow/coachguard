package io.github.linklow.coachguard.behavior

/** How far one feature of the current session is from the user's usual behavior. */
public data class FeatureDeviation(
    public val feature: BehaviorFeature,
    /** The value in this session, in the feature's raw unit (milliseconds or a count). */
    public val observed: Double,
    /** The user's typical value, in the same unit. */
    public val typical: Double,
    /** Signed distance from typical in standard deviations, on the feature's log scale. */
    public val zScore: Double,
)

/** Comparison of the current session with the user's learned baseline. */
public data class BehaviorAnalysis(
    public val features: BehaviorFeatures,
    /** Number of past sessions the baseline has learned from. */
    public val baselineSessions: Int,
    /**
     * Whether the baseline has seen enough sessions to judge this one. Until then
     * [anomalyScore] is 0 and [deviations] is empty.
     */
    public val isBaselineReady: Boolean,
    /**
     * Root mean square of the deviations' z-scores. Around 1 is ordinary variation; values of 2 and
     * above mean the session as a whole looks unlike the user's usual behavior.
     */
    public val anomalyScore: Double,
    /** Per-feature deviations, largest first. */
    public val deviations: List<FeatureDeviation>,
)

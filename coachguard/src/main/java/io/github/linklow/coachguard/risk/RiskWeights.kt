package io.github.linklow.coachguard.risk

/**
 * Weight of each rule in [RiskEngine], in `[0, 1]`. A weight of `0` disables the rule.
 *
 * The defaults are heuristic starting points based on public fraud typologies, not values fitted
 * to labelled data. Tune them against your own fraud outcomes.
 */
public data class RiskWeights(
    public val activeCall: Double = 0.35,
    public val screenCaptured: Double = 0.45,
    public val remoteAccessAppInstalled: Double = 0.15,
    public val remoteControlEnabled: Double = 0.50,
    public val untrustedAccessibilityService: Double = 0.25,
    public val obscuredTouch: Double = 0.40,
    public val adbEnabled: Double = 0.05,
    public val behaviorAnomaly: Double = 0.25,
    public val newPayee: Double = 0.10,
    public val unusualAmount: Double = 0.10,
    public val callDuringNewPayeeTransfer: Double = 0.40,
    public val screenCompromisedDuringTransfer: Double = 0.30,
    public val coachedBehaviorDuringCall: Double = 0.35,
) {
    init {
        requireWeight("activeCall", activeCall)
        requireWeight("screenCaptured", screenCaptured)
        requireWeight("remoteAccessAppInstalled", remoteAccessAppInstalled)
        requireWeight("remoteControlEnabled", remoteControlEnabled)
        requireWeight("untrustedAccessibilityService", untrustedAccessibilityService)
        requireWeight("obscuredTouch", obscuredTouch)
        requireWeight("adbEnabled", adbEnabled)
        requireWeight("behaviorAnomaly", behaviorAnomaly)
        requireWeight("newPayee", newPayee)
        requireWeight("unusualAmount", unusualAmount)
        requireWeight("callDuringNewPayeeTransfer", callDuringNewPayeeTransfer)
        requireWeight("screenCompromisedDuringTransfer", screenCompromisedDuringTransfer)
        requireWeight("coachedBehaviorDuringCall", coachedBehaviorDuringCall)
    }

    private fun requireWeight(name: String, value: Double) {
        require(value in 0.0..1.0) { "$name must be in [0, 1], was $value" }
    }
}

/**
 * Score boundaries between [RiskLevel]s. A score equal to a boundary falls into the higher level.
 */
public data class RiskThresholds(
    public val medium: Double = 0.30,
    public val high: Double = 0.60,
    /**
     * Behavioral anomaly score (see [io.github.linklow.coachguard.behavior.BehaviorAnalysis.anomalyScore])
     * at which the session counts as unusual for the user.
     */
    public val behaviorAnomaly: Double = 2.0,
) {
    init {
        require(medium > 0.0 && medium < high && high <= 1.0) {
            "Expected 0 < medium < high <= 1, was medium=$medium, high=$high"
        }
        require(behaviorAnomaly > 0.0 && behaviorAnomaly.isFinite()) {
            "behaviorAnomaly must be positive, was $behaviorAnomaly"
        }
    }
}

package io.github.linklow.coachguard.risk

import io.github.linklow.coachguard.TransactionContext
import io.github.linklow.coachguard.signals.DeviceSignals

/** Result of [RiskEngine.evaluate]: a score, a level and the reasons that explain them. */
public data class RiskAssessment(
    /** Combined risk in `[0, 1]`; see [RiskEngine] for how rules are combined. */
    public val score: Double,
    public val level: RiskLevel,
    /** Rules that fired, strongest first. Empty when nothing suspicious was observed. */
    public val reasons: List<RiskReason>,
    /** The observations the assessment was based on. */
    public val signals: DeviceSignals,
    /** The transaction the assessment was made for, if any. */
    public val transaction: TransactionContext?,
) {
    /** Whether a rule with this [code] fired. */
    public fun hasReason(code: ReasonCode): Boolean = reasons.any { it.code == code }
}

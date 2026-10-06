package io.github.linklow.coachguard.risk

/** One rule that fired during an assessment, with the evidence behind it. */
public data class RiskReason(
    public val code: ReasonCode,
    /** The rule's weight from [RiskWeights], in `[0, 1]`. */
    public val weight: Double,
    /** Short English explanation meant for logs and developer tooling, not for end users. */
    public val message: String,
    /** Concrete observations behind the rule, such as package names or the call type. */
    public val evidence: List<String> = emptyList(),
)

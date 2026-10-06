package io.github.linklow.coachguard.links

/** How likely a link is to lead to a phishing site. */
public enum class LinkRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
}

/** One piece of evidence behind a [LinkAssessment]. */
public data class LinkReason(
    /** What the model noticed, for example `contains "paypa"` or `hyphen_count = 4`. */
    public val description: String,
    /** How much this pushed the score toward phishing, in log-odds. */
    public val contribution: Double,
)

/** The result of [PhishingLinkClassifier.assess]. */
public data class LinkAssessment(
    public val url: String,
    /**
     * The host that was judged, lower-cased and without a leading `www.`; `null` if the URL has
     * no host, in which case the link is not judged and [level] is [LinkRiskLevel.LOW].
     */
    public val host: String?,
    /** The model's estimated probability that [host] is a phishing host, in `[0, 1]`. */
    public val probability: Double,
    public val level: LinkRiskLevel,
    /**
     * The host belongs to one of the most popular registered domains. Such links are reported as
     * [LinkRiskLevel.LOW] whatever the model score, because a host name cannot reveal a phishing
     * page on a reputable site, while names like `mail.example.com` would otherwise cause false
     * alarms. [probability] still holds the model's score.
     */
    public val isPopularDomain: Boolean,
    /**
     * The strongest evidence toward phishing, largest first. Empty when nothing points that way
     * and for popular domains.
     */
    public val reasons: List<LinkReason>,
)

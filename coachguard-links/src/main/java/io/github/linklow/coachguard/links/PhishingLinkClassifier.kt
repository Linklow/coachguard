package io.github.linklow.coachguard.links

import java.io.IOException
import java.util.Locale
import kotlin.math.exp
import kotlin.math.floor

/**
 * Judges whether a link, for example one decoded from a payment QR code or pasted by the user,
 * points to a phishing host. Runs on the device with a bundled model; nothing is sent over the
 * network.
 *
 * The model looks at the host only (`login-paypal.example.com`), not at the scheme, path or query.
 * It is a logistic regression over character n-grams and a few structural features, so every
 * decision can be broken down into the [LinkAssessment.reasons] that drove it. How it was trained
 * and how well it performs is documented in `ml/reports/phishing-model-report.md`.
 *
 * Hosts under the most popular registered domains are reported as low risk; see
 * [LinkAssessment.isPopularDomain].
 *
 * Instances are immutable and thread-safe. Loading reads the model once, so keep one instance.
 */
public class PhishingLinkClassifier private constructor(
    private val model: HostModel,
    private val popularDomains: PopularDomains,
) {

    /** Assesses one link. Fast enough to call on the main thread. */
    public fun assess(url: String): LinkAssessment {
        val parsed = HostFeatures.parse(url) ?: return LinkAssessment(
            url = url,
            host = null,
            probability = 0.0,
            level = LinkRiskLevel.LOW,
            isPopularDomain = false,
            reasons = emptyList(),
        )

        val contributions = contributions(parsed)
        val logit = contributions.fold(model.bias) { sum, reason -> sum + reason.contribution }
        val probability = 1.0 / (1.0 + exp(-logit))
        val popular = popularDomains.contains(parsed)
        val level = when {
            popular -> LinkRiskLevel.LOW
            probability >= model.thresholdHigh -> LinkRiskLevel.HIGH
            probability >= model.thresholdMedium -> LinkRiskLevel.MEDIUM
            else -> LinkRiskLevel.LOW
        }
        val reasons = if (popular) {
            emptyList()
        } else {
            contributions
                .filter { it.contribution > 0.0 }
                .sortedByDescending { it.contribution }
                .take(MAX_REASONS)
        }
        return LinkAssessment(url, parsed.host, probability, level, popular, reasons)
    }

    /**
     * Every term of the logit except the bias, in the same order as the reference scorer in
     * `ml/train_phishing_model.py`: hashed buckets in ascending order, then numeric features.
     * When several keys share a bucket, the bucket's weight is attributed to the first key only,
     * so the contributions always add up to the score.
     */
    private fun contributions(parsed: ParsedHost): List<LinkReason> {
        val keyByBucket = sortedMapOf<Int, String>()
        for (key in HostFeatures.tokenKeys(parsed)) {
            keyByBucket.putIfAbsent(HostFeatures.bucket(key), key)
        }
        val result = ArrayList<LinkReason>(keyByBucket.size + HostFeatures.NUMERIC_FEATURES.size)
        for ((bucket, key) in keyByBucket) {
            result += LinkReason(describeKey(key), model.hashedWeight(bucket))
        }
        HostFeatures.numericFeatures(parsed).forEachIndexed { index, value ->
            val name = HostFeatures.NUMERIC_FEATURES[index]
            result += LinkReason("$name = ${formatNumber(value)}", model.numericContribution(index, value))
        }
        return result
    }

    private fun describeKey(key: String): String {
        val value = key.substring(2)
        return when (key[0]) {
            'c' -> when {
                value.startsWith("^") && value.endsWith("$") -> "is \"${value.drop(1).dropLast(1)}\""
                value.startsWith("^") -> "starts with \"${value.drop(1)}\""
                value.endsWith("$") -> "ends with \"${value.dropLast(1)}\""
                else -> "contains \"$value\""
            }
            't' -> "top-level domain .$value"
            's' -> "domain $value"
            else -> "label \"$value\""
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == floor(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.2f", value)

    public companion object {
        // Absolute paths: R8 may rename or move this class in a release app, which would break a
        // lookup relative to its package. Resources keep their original paths.
        private const val MODEL_RESOURCE = "/io/github/linklow/coachguard/links/host-model.bin"
        private const val POPULAR_RESOURCE = "/io/github/linklow/coachguard/links/popular-domains.txt"
        private const val MAX_REASONS = 5

        /**
         * Loads the bundled model and popular-domain list (about 450 KB together). It reads and
         * parses those resources, so on a startup-critical path call it off the main thread.
         *
         * @throws IOException if a bundled resource is missing or corrupt, which indicates a
         * packaging problem such as resources stripped from the app.
         */
        @JvmStatic
        @Throws(IOException::class)
        public fun create(): PhishingLinkClassifier {
            val model = openResource(MODEL_RESOURCE).use(HostModel::read)
            val popular = openResource(POPULAR_RESOURCE).use(PopularDomains::read)
            return PhishingLinkClassifier(model, popular)
        }

        private fun openResource(name: String) =
            PhishingLinkClassifier::class.java.getResourceAsStream(name)
                ?: throw IOException("Bundled resource $name not found")
    }
}

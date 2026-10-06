package io.github.linklow.coachguard.behavior

import kotlin.math.max

/** Running statistics of one feature on its log scale. */
internal data class FeatureStats(val count: Int, val mean: Double, val variance: Double)

/**
 * A user's behavioral baseline: for each [BehaviorFeature], the mean and variance of its
 * log-scaled value over past sessions. Immutable; [updatedWith] returns a new profile.
 *
 * Updates use an adaptive exponentially weighted estimate. For the first `adaptationWindow`
 * sessions the step is `1/n`, which gives the exact mean and population variance of the sessions
 * seen so far. After that the step stays at `1/adaptationWindow`, so the baseline keeps following
 * the user as their habits change, while older sessions fade out.
 */
internal class BehaviorProfile(
    val sessions: Int,
    val stats: Map<BehaviorFeature, FeatureStats>,
) {

    fun updatedWith(features: BehaviorFeatures, adaptationWindow: Int): BehaviorProfile {
        val updated = stats.toMutableMap()
        for (feature in BehaviorFeature.entries) {
            val raw = features.valueOf(feature) ?: continue
            val value = feature.transform(raw)
            val previous = stats[feature] ?: FeatureStats(count = 0, mean = 0.0, variance = 0.0)
            val count = previous.count + 1
            val step = max(1.0 / count, 1.0 / adaptationWindow)
            val delta = value - previous.mean
            updated[feature] = FeatureStats(
                count = count,
                mean = previous.mean + step * delta,
                variance = (1.0 - step) * (previous.variance + step * delta * delta),
            )
        }
        return BehaviorProfile(sessions + 1, updated)
    }

    fun toProperties(): Map<String, String> = buildMap {
        put(KEY_VERSION, FORMAT_VERSION.toString())
        put(KEY_SESSIONS, sessions.toString())
        stats.forEach { (feature, featureStats) ->
            put("${feature.name}.count", featureStats.count.toString())
            put("${feature.name}.mean", featureStats.mean.toString())
            put("${feature.name}.variance", featureStats.variance.toString())
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_SESSIONS = "sessions"

        val EMPTY = BehaviorProfile(sessions = 0, stats = emptyMap())

        /** Parses [toProperties] output. Returns `null` for an unknown version or corrupt data. */
        fun fromProperties(properties: Map<String, String>): BehaviorProfile? {
            if (properties[KEY_VERSION]?.toIntOrNull() != FORMAT_VERSION) return null
            val sessions = properties[KEY_SESSIONS]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val stats = mutableMapOf<BehaviorFeature, FeatureStats>()
            for (feature in BehaviorFeature.entries) {
                // A feature without a count has simply never been observed; anything else missing is corruption.
                val countText = properties["${feature.name}.count"] ?: continue
                val count = countText.toIntOrNull() ?: return null
                val mean = properties["${feature.name}.mean"]?.toDoubleOrNull() ?: return null
                val variance = properties["${feature.name}.variance"]?.toDoubleOrNull() ?: return null
                if (count !in 1..sessions || !mean.isFinite() || !variance.isFinite() || variance < 0.0) return null
                stats[feature] = FeatureStats(count, mean, variance)
            }
            return BehaviorProfile(sessions, stats)
        }
    }
}

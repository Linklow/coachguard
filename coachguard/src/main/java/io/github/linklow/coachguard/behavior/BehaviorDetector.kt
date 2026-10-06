package io.github.linklow.coachguard.behavior

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Compares a session's [BehaviorFeatures] with a [BehaviorProfile].
 *
 * For each feature with at least [minBaselineSessions] observations, the deviation is the z-score
 * of the session's log-scaled value against the profile, using the larger of the learned standard
 * deviation and the feature's minimum spread. The anomaly score is the root mean square of those
 * z-scores: one strongly unusual feature or several moderately unusual ones both raise it.
 */
internal class BehaviorDetector(private val minBaselineSessions: Int) {

    fun analyze(features: BehaviorFeatures, profile: BehaviorProfile?): BehaviorAnalysis {
        val sessions = profile?.sessions ?: 0
        val deviations = if (profile == null || sessions < minBaselineSessions) {
            emptyList()
        } else {
            BehaviorFeature.entries.mapNotNull { feature -> deviation(feature, features, profile) }
        }
        val anomalyScore = if (deviations.isEmpty()) {
            0.0
        } else {
            sqrt(deviations.sumOf { it.zScore * it.zScore } / deviations.size)
        }
        return BehaviorAnalysis(
            features = features,
            baselineSessions = sessions,
            isBaselineReady = deviations.isNotEmpty(),
            anomalyScore = anomalyScore,
            deviations = deviations.sortedByDescending { abs(it.zScore) },
        )
    }

    private fun deviation(
        feature: BehaviorFeature,
        features: BehaviorFeatures,
        profile: BehaviorProfile,
    ): FeatureDeviation? {
        val observed = features.valueOf(feature) ?: return null
        val stats = profile.stats[feature]?.takeIf { it.count >= minBaselineSessions } ?: return null
        val spread = max(sqrt(stats.variance), feature.minSpread)
        return FeatureDeviation(
            feature = feature,
            observed = observed,
            typical = feature.inverse(stats.mean),
            zScore = (feature.transform(observed) - stats.mean) / spread,
        )
    }
}

package io.github.linklow.coachguard.risk

import io.github.linklow.coachguard.BehaviorConfig
import org.junit.Test

class RiskConfigTest {

    @Test(expected = IllegalArgumentException::class)
    fun `weight above one is rejected`() {
        RiskWeights(activeCall = 1.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative weight is rejected`() {
        RiskWeights(newPayee = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NaN weight is rejected`() {
        RiskWeights(obscuredTouch = Double.NaN)
    }

    @Test
    fun `boundary weights are accepted`() {
        RiskWeights(activeCall = 0.0, screenCaptured = 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `medium threshold must be below high`() {
        RiskThresholds(medium = 0.7, high = 0.6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `high threshold must not exceed one`() {
        RiskThresholds(medium = 0.3, high = 1.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `medium threshold must be positive`() {
        RiskThresholds(medium = 0.0, high = 0.6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `behavior anomaly threshold must be positive`() {
        RiskThresholds(behaviorAnomaly = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `behavior anomaly threshold must be finite`() {
        RiskThresholds(behaviorAnomaly = Double.POSITIVE_INFINITY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `baseline needs at least two sessions`() {
        BehaviorConfig(minBaselineSessions = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adaptation window cannot be shorter than the baseline`() {
        BehaviorConfig(minBaselineSessions = 10, adaptationWindow = 5)
    }
}

package io.github.linklow.coachguard.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class BehaviorDetectorTest {

    private val detector = BehaviorDetector(minBaselineSessions = 5)

    @Test
    fun `without a profile the baseline is not ready`() {
        val analysis = detector.analyze(features(), profile = null)

        assertFalse(analysis.isBaselineReady)
        assertEquals(0, analysis.baselineSessions)
        assertEquals(0.0, analysis.anomalyScore, 0.0)
        assertTrue(analysis.deviations.isEmpty())
    }

    @Test
    fun `baseline with too few sessions is not used`() {
        val analysis = detector.analyze(features(timeAwayMillis = 60_000), profileOf(typicalSessions().take(4)))

        assertFalse(analysis.isBaselineReady)
        assertEquals(4, analysis.baselineSessions)
        assertEquals(0.0, analysis.anomalyScore, 0.0)
    }

    @Test
    fun `typical session scores low`() {
        val analysis = detector.analyze(features(timeToConfirmMillis = 9_500, touches = 5), profileOf(typicalSessions()))

        assertTrue(analysis.isBaselineReady)
        assertEquals(10, analysis.baselineSessions)
        assertEquals(BehaviorFeature.entries.size, analysis.deviations.size)
        assertTrue("score ${analysis.anomalyScore}", analysis.anomalyScore < 1.0)
    }

    @Test
    fun `coached-looking session scores high and explains why`() {
        // Leaves the app twice for 40 s in total and hesitates for 25 s before confirming.
        val coached = features(
            timeToConfirmMillis = 75_000,
            focusLosses = 2,
            timeAwayMillis = 40_000,
            touches = 6,
            longestPauseMillis = 25_000,
        )

        val analysis = detector.analyze(coached, profileOf(typicalSessions()))

        assertTrue("score ${analysis.anomalyScore}", analysis.anomalyScore >= 2.0)
        val topThree = analysis.deviations.take(3)
        assertEquals(
            setOf(BehaviorFeature.TIME_TO_CONFIRM, BehaviorFeature.TIME_AWAY, BehaviorFeature.LONGEST_PAUSE),
            topThree.map { it.feature }.toSet(),
        )
        assertTrue(topThree.all { it.zScore > 3.0 })
        assertEquals(40_000.0, topThree.first { it.feature == BehaviorFeature.TIME_AWAY }.observed, 0.0)
        val magnitudes = analysis.deviations.map { abs(it.zScore) }
        assertEquals(magnitudes.sortedDescending(), magnitudes)
    }

    @Test
    fun `minimum spread keeps a never-varying feature from exploding`() {
        // Ten identical sessions: zero learned variance for every feature.
        val profile = profileOf(List(10) { features() })

        val sameAgain = detector.analyze(features(), profile)
        val oneFocusLoss = detector.analyze(features(focusLosses = 1), profile)

        assertEquals(0.0, sameAgain.anomalyScore, 1e-12)
        val deviation = oneFocusLoss.deviations.first { it.feature == BehaviorFeature.FOCUS_LOSSES }
        assertEquals(ln(2.0) / BehaviorFeature.FOCUS_LOSSES.minSpread, deviation.zScore, 1e-12)
        assertEquals(0.0, deviation.typical, 1e-9)
    }

    @Test
    fun `typical value is reported in raw units`() {
        val analysis = detector.analyze(features(), profileOf(List(10) { features(timeToConfirmMillis = 9_000) }))

        val deviation = analysis.deviations.first { it.feature == BehaviorFeature.TIME_TO_CONFIRM }
        assertEquals(9_000.0, deviation.typical, 1e-6)
    }

    @Test
    fun `feature missing from the session is skipped`() {
        val analysis = detector.analyze(features(meanTouchDurationMillis = null), profileOf(typicalSessions()))

        assertTrue(analysis.deviations.none { it.feature == BehaviorFeature.TOUCH_DURATION })
        assertEquals(BehaviorFeature.entries.size - 1, analysis.deviations.size)
    }

    /** Ten ordinary sessions with natural variation. */
    private fun typicalSessions(): List<BehaviorFeatures> = listOf(
        features(timeToConfirmMillis = 8_000, touches = 4, longestPauseMillis = 2_500, meanTouchDurationMillis = 100.0),
        features(timeToConfirmMillis = 11_000, touches = 6, longestPauseMillis = 3_500, meanTouchDurationMillis = 120.0),
        features(timeToConfirmMillis = 9_000, touches = 5, longestPauseMillis = 3_000, meanTouchDurationMillis = 105.0),
        features(timeToConfirmMillis = 12_000, touches = 5, longestPauseMillis = 4_000, meanTouchDurationMillis = 115.0),
        features(timeToConfirmMillis = 7_500, touches = 4, longestPauseMillis = 2_000, meanTouchDurationMillis = 95.0),
        features(timeToConfirmMillis = 10_000, touches = 5, longestPauseMillis = 3_000, meanTouchDurationMillis = 110.0),
        features(timeToConfirmMillis = 9_500, touches = 6, longestPauseMillis = 2_800, meanTouchDurationMillis = 108.0),
        features(timeToConfirmMillis = 13_000, focusLosses = 1, timeAwayMillis = 2_000, touches = 7, longestPauseMillis = 4_500),
        features(timeToConfirmMillis = 8_500, touches = 4, longestPauseMillis = 2_600, meanTouchDurationMillis = 102.0),
        features(timeToConfirmMillis = 10_500, touches = 5, longestPauseMillis = 3_200, meanTouchDurationMillis = 112.0),
    )

    private fun profileOf(sessions: List<BehaviorFeatures>): BehaviorProfile =
        sessions.fold(BehaviorProfile.EMPTY) { profile, session -> profile.updatedWith(session, adaptationWindow = 30) }
}

package io.github.linklow.coachguard.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class BehaviorProfileTest {

    @Test
    fun `early sessions give the exact mean and population variance`() {
        val profile = listOf(1_000L, 3_000L, 7_000L).fold(BehaviorProfile.EMPTY) { profile, millis ->
            profile.updatedWith(features(timeToConfirmMillis = millis), adaptationWindow = 30)
        }

        // On the log scale the values are ln 2, ln 4 and ln 8.
        val values = listOf(ln(2.0), ln(4.0), ln(8.0))
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val stats = profile.stats.getValue(BehaviorFeature.TIME_TO_CONFIRM)
        assertEquals(3, profile.sessions)
        assertEquals(3, stats.count)
        assertEquals(mean, stats.mean, 1e-12)
        assertEquals(variance, stats.variance, 1e-12)
    }

    @Test
    fun `after the adaptation window new sessions move the baseline by a fixed step`() {
        val window = 2
        val first = BehaviorProfile.EMPTY
            .updatedWith(features(touches = 3), window)
            .updatedWith(features(touches = 7), window)
        val meanAfterTwo = first.stats.getValue(BehaviorFeature.TOUCHES).mean

        val third = first.updatedWith(features(touches = 15), window)

        val expected = meanAfterTwo + 0.5 * (ln(16.0) - meanAfterTwo)
        assertEquals(expected, third.stats.getValue(BehaviorFeature.TOUCHES).mean, 1e-12)
    }

    @Test
    fun `a feature missing from a session is not updated`() {
        val profile = BehaviorProfile.EMPTY
            .updatedWith(features(meanTouchDurationMillis = 120.0), adaptationWindow = 30)
            .updatedWith(features(meanTouchDurationMillis = null), adaptationWindow = 30)

        assertEquals(2, profile.sessions)
        assertEquals(1, profile.stats.getValue(BehaviorFeature.TOUCH_DURATION).count)
        assertEquals(2, profile.stats.getValue(BehaviorFeature.TIME_TO_CONFIRM).count)
    }

    @Test
    fun `properties round trip`() {
        val profile = BehaviorProfile.EMPTY
            .updatedWith(features(timeToConfirmMillis = 8_000), adaptationWindow = 30)
            .updatedWith(features(timeToConfirmMillis = 11_000, meanTouchDurationMillis = null), adaptationWindow = 30)

        val restored = BehaviorProfile.fromProperties(profile.toProperties())

        assertNotNull(restored)
        assertEquals(profile.sessions, restored!!.sessions)
        assertEquals(profile.stats, restored.stats)
    }

    @Test
    fun `empty profile round trips`() {
        val restored = BehaviorProfile.fromProperties(BehaviorProfile.EMPTY.toProperties())

        assertEquals(0, restored!!.sessions)
        assertEquals(emptyMap<BehaviorFeature, FeatureStats>(), restored.stats)
    }

    @Test
    fun `corrupt or unknown data is rejected`() {
        val valid = BehaviorProfile.EMPTY.updatedWith(features(), adaptationWindow = 30).toProperties()

        assertNull(BehaviorProfile.fromProperties(valid + ("version" to "2")))
        assertNull(BehaviorProfile.fromProperties(valid - "version"))
        assertNull(BehaviorProfile.fromProperties(valid + ("sessions" to "-1")))
        assertNull(BehaviorProfile.fromProperties(valid + ("TOUCHES.count" to "5"))) // more than sessions
        assertNull(BehaviorProfile.fromProperties(valid + ("TOUCHES.variance" to "-0.1")))
        assertNull(BehaviorProfile.fromProperties(valid + ("TOUCHES.mean" to "NaN")))
        assertNull(BehaviorProfile.fromProperties(valid + ("TOUCHES.mean" to "abc")))
        assertNull(BehaviorProfile.fromProperties(valid - "TOUCHES.mean"))
    }
}

internal fun features(
    timeToConfirmMillis: Long = 9_000,
    focusLosses: Int = 0,
    timeAwayMillis: Long = 0,
    touches: Int = 4,
    longestPauseMillis: Long = 3_000,
    meanTouchDurationMillis: Double? = 110.0,
) = BehaviorFeatures(
    timeToConfirmMillis = timeToConfirmMillis,
    focusLosses = focusLosses,
    timeAwayMillis = timeAwayMillis,
    touches = touches,
    longestPauseMillis = longestPauseMillis,
    meanTouchDurationMillis = meanTouchDurationMillis,
)

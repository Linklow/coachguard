package io.github.linklow.coachguard.risk

import io.github.linklow.coachguard.TransactionContext
import io.github.linklow.coachguard.behavior.BehaviorAnalysis
import io.github.linklow.coachguard.behavior.BehaviorFeature
import io.github.linklow.coachguard.behavior.BehaviorFeatures
import io.github.linklow.coachguard.behavior.FeatureDeviation
import io.github.linklow.coachguard.signals.AccessibilityServiceSignal
import io.github.linklow.coachguard.signals.CallState
import io.github.linklow.coachguard.signals.DeviceSignals
import io.github.linklow.coachguard.signals.ScreenCaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {

    private val engine = RiskEngine()

    @Test
    fun `clean device is low risk with no reasons`() {
        val result = engine.evaluate(signals(), TransactionContext())

        assertEquals(0.0, result.score, EPSILON)
        assertEquals(RiskLevel.LOW, result.level)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `call alone is medium risk`() {
        val result = engine.evaluate(signals(callState = CallState.CELLULAR_CALL))

        assertEquals(0.35, result.score, EPSILON)
        assertEquals(RiskLevel.MEDIUM, result.level)
        assertEquals(listOf(ReasonCode.ACTIVE_CALL), result.reasons.map { it.code })
    }

    @Test
    fun `voip call counts as a call`() {
        val result = engine.evaluate(signals(callState = CallState.VOIP_CALL))

        assertTrue(result.hasReason(ReasonCode.ACTIVE_CALL))
        assertEquals(listOf("VOIP_CALL"), result.reasons.single().evidence)
    }

    @Test
    fun `ringing is not treated as a call`() {
        val result = engine.evaluate(signals(callState = CallState.RINGING))

        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `call during transfer to new payee is high risk`() {
        val result = engine.evaluate(
            signals(callState = CallState.CELLULAR_CALL),
            TransactionContext(isNewPayee = true),
        )

        // 1 - (1 - 0.35) * (1 - 0.10) * (1 - 0.40)
        assertEquals(0.649, result.score, EPSILON)
        assertEquals(RiskLevel.HIGH, result.level)
        assertEquals(
            listOf(ReasonCode.CALL_DURING_NEW_PAYEE_TRANSFER, ReasonCode.ACTIVE_CALL, ReasonCode.NEW_PAYEE),
            result.reasons.map { it.code },
        )
    }

    @Test
    fun `transaction rules do not fire without a transaction`() {
        val result = engine.evaluate(signals(callState = CallState.CELLULAR_CALL, screenCapture = ScreenCaptureState.CAPTURED))

        assertFalse(result.hasReason(ReasonCode.CALL_DURING_NEW_PAYEE_TRANSFER))
        assertFalse(result.hasReason(ReasonCode.SCREEN_COMPROMISED_DURING_TRANSFER))
    }

    @Test
    fun `screen capture during transfer is high risk`() {
        val result = engine.evaluate(
            signals(screenCapture = ScreenCaptureState.CAPTURED),
            TransactionContext(),
        )

        assertTrue(result.hasReason(ReasonCode.SCREEN_CAPTURED))
        assertTrue(result.hasReason(ReasonCode.SCREEN_COMPROMISED_DURING_TRANSFER))
        assertEquals(RiskLevel.HIGH, result.level)
    }

    @Test
    fun `installed remote access app alone is low risk`() {
        val result = engine.evaluate(signals(remoteAccessApps = listOf(ANYDESK)))

        assertEquals(listOf(ReasonCode.REMOTE_ACCESS_APP_INSTALLED), result.reasons.map { it.code })
        assertEquals(listOf(ANYDESK), result.reasons.single().evidence)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `remote access app with accessibility service is remote control, not just installed`() {
        val result = engine.evaluate(
            signals(
                remoteAccessApps = listOf(ANYDESK),
                accessibilityServices = listOf(service(ANYDESK, canPerformGestures = true)),
            ),
        )

        assertEquals(listOf(ReasonCode.REMOTE_CONTROL_ENABLED), result.reasons.map { it.code })
        assertEquals(listOf(ANYDESK), result.reasons.single().evidence)
    }

    @Test
    fun `remote control is detected even when the app is not in the installed list`() {
        // Package visibility can hide the app from the installed check while its service is enabled.
        val result = engine.evaluate(
            signals(accessibilityServices = listOf(service(ANYDESK, canPerformGestures = true))),
        )

        assertTrue(result.hasReason(ReasonCode.REMOTE_CONTROL_ENABLED))
    }

    @Test
    fun `third party service that can read the screen is untrusted`() {
        val result = engine.evaluate(
            signals(accessibilityServices = listOf(service("com.example.cleaner", canRetrieveWindowContent = true))),
        )

        assertEquals(listOf(ReasonCode.UNTRUSTED_ACCESSIBILITY_SERVICE), result.reasons.map { it.code })
        assertEquals(listOf("com.example.cleaner"), result.reasons.single().evidence)
    }

    @Test
    fun `service of unknown tool status on old android is untrusted`() {
        val result = engine.evaluate(
            signals(
                accessibilityServices = listOf(
                    service("com.example.cleaner", canPerformGestures = true, isAccessibilityTool = null),
                ),
            ),
        )

        assertTrue(result.hasReason(ReasonCode.UNTRUSTED_ACCESSIBILITY_SERVICE))
    }

    @Test
    fun `system services, declared accessibility tools and passive services are not flagged`() {
        val result = engine.evaluate(
            signals(
                accessibilityServices = listOf(
                    service("com.google.android.marvin.talkback", isSystemApp = true, canRetrieveWindowContent = true),
                    service("com.example.screenreader", canRetrieveWindowContent = true, isAccessibilityTool = true),
                    service("com.example.passive"),
                ),
            ),
        )

        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `obscured touch is reported with its count, partial obscuring is not scored`() {
        val result = engine.evaluate(signals(obscuredTouchCount = 2, partiallyObscuredTouchCount = 5))

        assertEquals(listOf(ReasonCode.OBSCURED_TOUCH), result.reasons.map { it.code })
        assertEquals(listOf("count=2"), result.reasons.single().evidence)

        assertTrue(engine.evaluate(signals(partiallyObscuredTouchCount = 5)).reasons.isEmpty())
    }

    @Test
    fun `adb enabled is a weak signal and unknown adb is ignored`() {
        val enabled = engine.evaluate(signals(adbEnabled = true))
        assertEquals(listOf(ReasonCode.ADB_ENABLED), enabled.reasons.map { it.code })
        assertEquals(RiskLevel.LOW, enabled.level)

        assertTrue(engine.evaluate(signals(adbEnabled = null)).reasons.isEmpty())
    }

    @Test
    fun `zero weight disables a rule`() {
        val quietEngine = RiskEngine(RiskWeights(activeCall = 0.0))

        val result = quietEngine.evaluate(signals(callState = CallState.CELLULAR_CALL))

        assertTrue(result.reasons.isEmpty())
        assertEquals(0.0, result.score, EPSILON)
    }

    @Test
    fun `score stays within bounds when every rule fires`() {
        val result = engine.evaluate(
            signals(
                callState = CallState.CELLULAR_CALL,
                screenCapture = ScreenCaptureState.CAPTURED,
                remoteAccessApps = listOf(ANYDESK),
                accessibilityServices = listOf(
                    service(ANYDESK, canPerformGestures = true),
                    service("com.example.cleaner", canRetrieveWindowContent = true),
                ),
                obscuredTouchCount = 1,
                adbEnabled = true,
                behavior = behavior(anomalyScore = 4.0),
            ),
            TransactionContext(isNewPayee = true, isAmountUnusual = true),
        )

        assertTrue(result.score in 0.0..1.0)
        assertEquals(RiskLevel.HIGH, result.level)
        val weights = result.reasons.map { it.weight }
        assertEquals(weights.sortedDescending(), weights)
    }

    @Test
    fun `adding a rule never lowers the score`() {
        val base = engine.evaluate(signals(callState = CallState.CELLULAR_CALL)).score
        val more = engine.evaluate(signals(callState = CallState.CELLULAR_CALL, adbEnabled = true)).score

        assertTrue(more > base)
    }

    @Test
    fun `score equal to a threshold falls into the higher level`() {
        val engineAtBoundary = RiskEngine(RiskWeights(activeCall = 0.5), RiskThresholds(medium = 0.2, high = 0.5))

        val result = engineAtBoundary.evaluate(signals(callState = CallState.CELLULAR_CALL))

        assertEquals(RiskLevel.HIGH, result.level)
    }

    @Test
    fun `unusual behavior alone is a low-risk nudge`() {
        val result = engine.evaluate(signals(behavior = behavior(anomalyScore = 2.5)))

        assertEquals(listOf(ReasonCode.BEHAVIOR_ANOMALY), result.reasons.map { it.code })
        assertEquals(0.25, result.score, EPSILON)
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `unusual behavior during a call is high risk`() {
        val result = engine.evaluate(
            signals(callState = CallState.VOIP_CALL, behavior = behavior(anomalyScore = 2.5)),
        )

        assertEquals(
            setOf(ReasonCode.ACTIVE_CALL, ReasonCode.BEHAVIOR_ANOMALY, ReasonCode.COACHED_BEHAVIOR_DURING_CALL),
            result.reasons.map { it.code }.toSet(),
        )
        // 1 - (1 - 0.35) * (1 - 0.25) * (1 - 0.35)
        assertEquals(0.683125, result.score, EPSILON)
        assertEquals(RiskLevel.HIGH, result.level)
    }

    @Test
    fun `behavior below the threshold or without a ready baseline does not fire`() {
        val belowThreshold = engine.evaluate(signals(callState = CallState.CELLULAR_CALL, behavior = behavior(anomalyScore = 1.9)))
        val notReady = engine.evaluate(signals(behavior = behavior(anomalyScore = 5.0, isBaselineReady = false)))

        assertEquals(listOf(ReasonCode.ACTIVE_CALL), belowThreshold.reasons.map { it.code })
        assertTrue(notReady.reasons.isEmpty())
    }

    @Test
    fun `behavior evidence lists the overall score and notable deviations`() {
        val analysis = behavior(
            anomalyScore = 2.5,
            deviations = listOf(
                FeatureDeviation(BehaviorFeature.TIME_AWAY, observed = 41_000.0, typical = 0.0, zScore = 6.94),
                FeatureDeviation(BehaviorFeature.FOCUS_LOSSES, observed = 2.0, typical = 0.1, zScore = 2.06),
                FeatureDeviation(BehaviorFeature.TOUCH_DURATION, observed = 95.0, typical = 110.0, zScore = -1.2),
                FeatureDeviation(BehaviorFeature.TOUCHES, observed = 5.0, typical = 5.0, zScore = 0.3),
            ),
        )

        val reason = engine.evaluate(signals(behavior = analysis)).reasons.single()

        assertEquals(
            listOf(
                "anomalyScore=2.50",
                "TIME_AWAY: 41.0 s vs usual 0.0 s (+6.9σ)",
                "FOCUS_LOSSES: 2.0 vs usual 0.1 (+2.1σ)",
                "TOUCH_DURATION: 95 ms vs usual 110 ms (-1.2σ)",
            ),
            reason.evidence,
        )
    }

    private fun signals(
        callState: CallState = CallState.NONE,
        screenCapture: ScreenCaptureState = ScreenCaptureState.NOT_CAPTURED,
        remoteAccessApps: List<String> = emptyList(),
        accessibilityServices: List<AccessibilityServiceSignal> = emptyList(),
        obscuredTouchCount: Int = 0,
        partiallyObscuredTouchCount: Int = 0,
        adbEnabled: Boolean? = false,
        behavior: BehaviorAnalysis? = null,
    ) = DeviceSignals(
        callState = callState,
        screenCapture = screenCapture,
        remoteAccessApps = remoteAccessApps,
        accessibilityServices = accessibilityServices,
        obscuredTouchCount = obscuredTouchCount,
        partiallyObscuredTouchCount = partiallyObscuredTouchCount,
        adbEnabled = adbEnabled,
        collectedAtMillis = 0L,
        behavior = behavior,
    )

    private fun behavior(
        anomalyScore: Double,
        isBaselineReady: Boolean = true,
        deviations: List<FeatureDeviation> = emptyList(),
    ) = BehaviorAnalysis(
        features = BehaviorFeatures(
            timeToConfirmMillis = 10_000,
            focusLosses = 0,
            timeAwayMillis = 0,
            touches = 5,
            longestPauseMillis = 3_000,
            meanTouchDurationMillis = 110.0,
        ),
        baselineSessions = if (isBaselineReady) 10 else 2,
        isBaselineReady = isBaselineReady,
        anomalyScore = anomalyScore,
        deviations = deviations,
    )

    private fun service(
        packageName: String,
        isSystemApp: Boolean = false,
        canRetrieveWindowContent: Boolean = false,
        canPerformGestures: Boolean = false,
        isAccessibilityTool: Boolean? = false,
    ) = AccessibilityServiceSignal(
        packageName = packageName,
        serviceName = "$packageName.Service",
        isSystemApp = isSystemApp,
        canRetrieveWindowContent = canRetrieveWindowContent,
        canPerformGestures = canPerformGestures,
        isAccessibilityTool = isAccessibilityTool,
    )

    private companion object {
        const val EPSILON = 1e-9
        const val ANYDESK = "com.anydesk.anydeskandroid"
    }
}

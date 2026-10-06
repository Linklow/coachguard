package io.github.linklow.coachguard.risk

import io.github.linklow.coachguard.TransactionContext
import io.github.linklow.coachguard.behavior.BehaviorAnalysis
import io.github.linklow.coachguard.signals.CallState
import io.github.linklow.coachguard.signals.DeviceSignals
import io.github.linklow.coachguard.signals.KnownRemoteAccessApps
import io.github.linklow.coachguard.signals.ScreenCaptureState
import java.util.Locale
import kotlin.math.abs

/**
 * Turns [DeviceSignals] and an optional [TransactionContext] into an explainable [RiskAssessment].
 *
 * Each rule that fires contributes its weight `w` from [RiskWeights]. Rules are combined as
 * independent pieces of evidence (noisy-OR): `score = 1 - Π(1 - w)`. The score therefore stays in
 * `[0, 1]`, never decreases when another rule fires, and every point of it is traceable to a
 * [RiskReason].
 *
 * The engine is pure Kotlin with no Android dependencies, so it can also run on a server against
 * signals reported by the app.
 */
public class RiskEngine @JvmOverloads constructor(
    private val weights: RiskWeights = RiskWeights(),
    private val thresholds: RiskThresholds = RiskThresholds(),
) {

    @JvmOverloads
    public fun evaluate(signals: DeviceSignals, transaction: TransactionContext? = null): RiskAssessment {
        val reasons = mutableListOf<RiskReason>()
        fun fire(code: ReasonCode, weight: Double, message: String, evidence: List<String> = emptyList()) {
            if (weight > 0.0) reasons += RiskReason(code, weight, message, evidence)
        }

        val inCall = signals.callState.isInCall
        if (inCall) {
            val message = if (signals.callState == CallState.VOIP_CALL) {
                "A VoIP call is in progress"
            } else {
                "A phone call is in progress"
            }
            fire(ReasonCode.ACTIVE_CALL, weights.activeCall, message, listOf(signals.callState.name))
        }

        val screenCaptured = signals.screenCapture == ScreenCaptureState.CAPTURED
        if (screenCaptured) {
            fire(ReasonCode.SCREEN_CAPTURED, weights.screenCaptured, "The app's screen is being recorded or shared")
        }

        val remoteControlPackages = signals.accessibilityServices
            .map { it.packageName }
            .filter { it in KnownRemoteAccessApps.packages }
            .distinct()
            .sorted()
        if (remoteControlPackages.isNotEmpty()) {
            fire(
                ReasonCode.REMOTE_CONTROL_ENABLED,
                weights.remoteControlEnabled,
                "A remote-access app can control the screen through an accessibility service",
                remoteControlPackages,
            )
        } else if (signals.remoteAccessApps.isNotEmpty()) {
            fire(
                ReasonCode.REMOTE_ACCESS_APP_INSTALLED,
                weights.remoteAccessAppInstalled,
                "A remote-access app is installed",
                signals.remoteAccessApps.sorted(),
            )
        }

        val untrustedPackages = signals.accessibilityServices
            .filter { service ->
                !service.isSystemApp &&
                    service.packageName !in KnownRemoteAccessApps.packages &&
                    service.isAccessibilityTool != true &&
                    (service.canRetrieveWindowContent || service.canPerformGestures)
            }
            .map { it.packageName }
            .distinct()
            .sorted()
        if (untrustedPackages.isNotEmpty()) {
            fire(
                ReasonCode.UNTRUSTED_ACCESSIBILITY_SERVICE,
                weights.untrustedAccessibilityService,
                "An accessibility service that is not a declared accessibility tool can read or control the screen",
                untrustedPackages,
            )
        }

        val obscuredTouch = signals.obscuredTouchCount > 0
        if (obscuredTouch) {
            fire(
                ReasonCode.OBSCURED_TOUCH,
                weights.obscuredTouch,
                "The user tapped while another app's window covered the touched point",
                listOf("count=${signals.obscuredTouchCount}"),
            )
        }

        if (signals.adbEnabled == true) {
            fire(ReasonCode.ADB_ENABLED, weights.adbEnabled, "USB or wireless debugging is enabled")
        }

        val behavior = signals.behavior
        if (behavior != null && behavior.isBaselineReady && behavior.anomalyScore >= thresholds.behaviorAnomaly) {
            fire(
                ReasonCode.BEHAVIOR_ANOMALY,
                weights.behaviorAnomaly,
                "The user is interacting with this screen differently from usual",
                describe(behavior),
            )
            if (inCall) {
                fire(
                    ReasonCode.COACHED_BEHAVIOR_DURING_CALL,
                    weights.coachedBehaviorDuringCall,
                    "Unusual interaction during a call, consistent with following someone's instructions",
                )
            }
        }

        if (transaction != null) {
            if (transaction.isNewPayee) {
                fire(ReasonCode.NEW_PAYEE, weights.newPayee, "The transfer goes to a new payee")
            }
            if (transaction.isAmountUnusual) {
                fire(ReasonCode.UNUSUAL_AMOUNT, weights.unusualAmount, "The amount is unusual for this user")
            }
            if (inCall && transaction.isNewPayee) {
                fire(
                    ReasonCode.CALL_DURING_NEW_PAYEE_TRANSFER,
                    weights.callDuringNewPayeeTransfer,
                    "Transfer to a new payee during a call, a common pattern in impersonation scams",
                )
            }
            if (screenCaptured || remoteControlPackages.isNotEmpty() || obscuredTouch) {
                fire(
                    ReasonCode.SCREEN_COMPROMISED_DURING_TRANSFER,
                    weights.screenCompromisedDuringTransfer,
                    "Transfer while the screen is shared, remotely controlled or covered by an overlay",
                )
            }
        }

        val score = 1.0 - reasons.fold(1.0) { remaining, reason -> remaining * (1.0 - reason.weight) }
        val level = when {
            score >= thresholds.high -> RiskLevel.HIGH
            score >= thresholds.medium -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return RiskAssessment(
            score = score,
            level = level,
            reasons = reasons.sortedByDescending { it.weight },
            signals = signals,
            transaction = transaction,
        )
    }

    /** Overall score plus the features that deviate most, e.g. `TIME_AWAY: 41.0 s vs usual 0.0 s (+6.9σ)`. */
    private fun describe(behavior: BehaviorAnalysis): List<String> {
        val overall = String.format(Locale.ROOT, "anomalyScore=%.2f", behavior.anomalyScore)
        val notable = behavior.deviations
            .filter { abs(it.zScore) >= NOTABLE_DEVIATION }
            .take(MAX_DEVIATIONS_IN_EVIDENCE)
            .map { deviation ->
                val feature = deviation.feature
                String.format(
                    Locale.ROOT,
                    "%s: %s vs usual %s (%+.1fσ)",
                    feature.name,
                    feature.format(deviation.observed),
                    feature.format(deviation.typical),
                    deviation.zScore,
                )
            }
        return listOf(overall) + notable
    }

    private companion object {
        const val NOTABLE_DEVIATION = 1.0
        const val MAX_DEVIATIONS_IN_EVIDENCE = 3
    }
}

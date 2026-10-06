package io.github.linklow.coachguard

import android.app.Activity
import android.content.Context
import io.github.linklow.coachguard.internal.BehaviorProfileStore
import io.github.linklow.coachguard.internal.DeviceSignalCollector
import io.github.linklow.coachguard.internal.ioExecutor
import io.github.linklow.coachguard.risk.RiskAssessment
import io.github.linklow.coachguard.risk.RiskEngine
import io.github.linklow.coachguard.signals.DeviceSignals
import io.github.linklow.coachguard.signals.ScreenCaptureState

/**
 * Entry point of the SDK: detects signs that a user is being coached by a scammer while making a
 * payment (an ongoing call, a shared or remotely controlled screen, an overlay over the app,
 * interaction unlike the user's own habits) and turns them into an explainable risk assessment.
 *
 * Everything runs on the device. The SDK makes no network requests and has no dependencies besides
 * the Kotlin standard library.
 *
 * ```
 * val guard = CoachGuard.create(context)
 *
 * // In the payment screen:
 * val session = guard.startSession(activity, SessionOptions(profileId = userId))
 * // When the user taps "Confirm":
 * val assessment = session.assess(TransactionContext(isNewPayee = true))
 * if (assessment.level == RiskLevel.HIGH) showScamWarning(assessment.reasons)
 * // When the screen goes away:
 * session.close()
 * ```
 */
public class CoachGuard private constructor(
    context: Context,
    /** The configuration this instance was created with. */
    public val config: CoachGuardConfig,
) {

    private val collector = DeviceSignalCollector(context)
    private val engine = RiskEngine(config.weights, config.thresholds)
    private val profileStore = BehaviorProfileStore(context)

    /**
     * Collects the signals that do not need a screen: call state, remote-access apps, accessibility
     * services and debugging. Screen capture is reported as [ScreenCaptureState.UNKNOWN], overlay
     * touches as zero and behavior as `null`; use [startSession] to observe those.
     */
    public fun signals(): DeviceSignals = collector.collect(
        screenCapture = ScreenCaptureState.UNKNOWN,
        obscuredTouchCount = 0,
        partiallyObscuredTouchCount = 0,
        behavior = null,
    )

    /** Assesses the risk from [signals] alone. Prefer [GuardSession.assess] on payment screens. */
    @JvmOverloads
    public fun assess(transaction: TransactionContext? = null): RiskAssessment =
        engine.evaluate(signals(), transaction)

    /**
     * Starts watching [activity] until the returned session is closed. Call on the main thread,
     * after the activity's window has been created (for example in `onCreate`).
     */
    @JvmOverloads
    public fun startSession(activity: Activity, options: SessionOptions = SessionOptions()): GuardSession =
        GuardSession(activity, collector, engine, profileStore, config.behavior, options)

    /**
     * Deletes the behavioral baseline learned for [profileId], for example when the user signs out
     * or asks for their data to be erased. Runs in the background. A session that is open at that
     * moment may still save itself afterwards, so close sessions first.
     */
    @JvmOverloads
    public fun resetBehaviorProfile(profileId: String = SessionOptions.DEFAULT_PROFILE_ID) {
        ioExecutor.execute { profileStore.delete(profileId) }
    }

    public companion object {
        /** Creates an instance. Cheap to call; keep one per application for convenience. */
        @JvmStatic
        @JvmOverloads
        public fun create(context: Context, config: CoachGuardConfig = CoachGuardConfig()): CoachGuard =
            CoachGuard(context, config)
    }
}

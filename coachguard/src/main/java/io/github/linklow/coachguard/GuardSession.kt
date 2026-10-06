package io.github.linklow.coachguard

import android.app.Activity
import android.view.Window
import io.github.linklow.coachguard.behavior.BehaviorAnalysis
import io.github.linklow.coachguard.behavior.BehaviorDetector
import io.github.linklow.coachguard.behavior.BehaviorProfile
import io.github.linklow.coachguard.internal.BehaviorProfileStore
import io.github.linklow.coachguard.internal.DeviceSignalCollector
import io.github.linklow.coachguard.internal.InteractionMonitor
import io.github.linklow.coachguard.internal.OverlayHider
import io.github.linklow.coachguard.internal.ScreenProtector
import io.github.linklow.coachguard.internal.ScreenRecordingMonitor
import io.github.linklow.coachguard.internal.checkMainThread
import io.github.linklow.coachguard.internal.ioExecutor
import io.github.linklow.coachguard.risk.ReasonCode
import io.github.linklow.coachguard.risk.RiskAssessment
import io.github.linklow.coachguard.risk.RiskEngine
import io.github.linklow.coachguard.risk.RiskLevel
import io.github.linklow.coachguard.signals.DeviceSignals
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Watches one screen, typically the payment confirmation screen, for as long as it is open.
 *
 * On top of the device-wide signals, a session observes screen recording and sharing
 * (Android 15+), taps made while an overlay covered the screen, and how the user interacts with
 * the screen compared with their own past sessions. Create it with [CoachGuard.startSession] when
 * the screen opens and [close] it when the screen goes away; an unclosed session keeps a
 * reference to the activity.
 *
 * All methods must be called on the main thread.
 */
public class GuardSession internal constructor(
    activity: Activity,
    private val collector: DeviceSignalCollector,
    private val engine: RiskEngine,
    private val profileStore: BehaviorProfileStore,
    behaviorConfig: BehaviorConfig,
    options: SessionOptions,
) : Closeable {

    private val window: Window = activity.window
    private val screenRecording = ScreenRecordingMonitor(activity)
    private val interactions = InteractionMonitor(window)
    private val detector = BehaviorDetector(behaviorConfig.minBaselineSessions)
    private val adaptationWindow = behaviorConfig.adaptationWindow
    private val profileId = options.profileId
    private val learnBehavior = options.learnBehavior

    // Loaded in the background so the main thread never touches the disk.
    private val pendingProfile: Future<BehaviorProfile?> = ioExecutor.submit(Callable { profileStore.load(profileId) })
    private var profile: BehaviorProfile? = null
    private var profileLoaded = false
    private var learned = false

    private val overlaysHidden: Boolean
    private val screenProtected: Boolean
    private var closed = false

    init {
        checkMainThread()
        screenRecording.start()
        interactions.start()
        overlaysHidden = options.hideOverlayWindows && OverlayHider.hide(activity)
        screenProtected = options.protectScreen && ScreenProtector.protect(window)
    }

    /** Whether [close] has been called. */
    public val isClosed: Boolean
        get() = closed

    /** Collects the current signals, including the ones only a session can observe. */
    public fun signals(): DeviceSignals {
        checkMainThread()
        check(!closed) { "GuardSession is closed" }
        return collector.collect(
            screenCapture = screenRecording.state,
            obscuredTouchCount = interactions.obscuredCount,
            partiallyObscuredTouchCount = interactions.partiallyObscuredCount,
            behavior = behaviorAnalysis(),
        )
    }

    /**
     * Assesses the risk right now, typically when the user taps "Confirm".
     * Pass a [transaction] to enable the rules that look at the payment itself.
     *
     * If [SessionOptions.learnBehavior] is on, the first low-risk assessment of the session with no
     * behavioral anomaly is added to the user's baseline.
     */
    @JvmOverloads
    public fun assess(transaction: TransactionContext? = null): RiskAssessment {
        val assessment = engine.evaluate(signals(), transaction)
        learnFrom(assessment)
        return assessment
    }

    /** Stops observing the screen. Safe to call more than once. */
    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        screenRecording.stop()
        interactions.stop()
        if (overlaysHidden) OverlayHider.restore(window)
        if (screenProtected) ScreenProtector.restore(window)
    }

    private fun behaviorAnalysis(): BehaviorAnalysis? {
        val features = interactions.behavior() ?: return null
        return detector.analyze(features, loadedProfile())
    }

    private fun loadedProfile(): BehaviorProfile? {
        if (profileLoaded) return profile
        try {
            profile = pendingProfile.get(PROFILE_LOAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            profileLoaded = true
        } catch (e: TimeoutException) {
            // Still loading: judge this assessment without a baseline and try again next time.
        } catch (e: ExecutionException) {
            profileLoaded = true // An unreadable profile is treated as absent and replaced.
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return profile
    }

    private fun learnFrom(assessment: RiskAssessment) {
        // Without the stored profile in memory, saving would overwrite it with this one session.
        if (!learnBehavior || learned || !profileLoaded) return
        if (assessment.level != RiskLevel.LOW || assessment.hasReason(ReasonCode.BEHAVIOR_ANOMALY)) return
        val features = assessment.signals.behavior?.features ?: return
        val updated = (profile ?: BehaviorProfile.EMPTY).updatedWith(features, adaptationWindow)
        profile = updated
        learned = true
        ioExecutor.execute { profileStore.save(profileId, updated) }
    }

    private companion object {
        // Reading a profile takes a few milliseconds; this only bounds the wait on a stalled disk.
        const val PROFILE_LOAD_TIMEOUT_MILLIS = 200L
    }
}

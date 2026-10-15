package io.github.linklow.coachguard.sample.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import io.github.linklow.coachguard.CoachGuard
import io.github.linklow.coachguard.SessionOptions
import io.github.linklow.coachguard.TransactionContext
import io.github.linklow.coachguard.behavior.BehaviorAnalysis
import io.github.linklow.coachguard.behavior.BehaviorFeature
import io.github.linklow.coachguard.behavior.BehaviorFeatures
import io.github.linklow.coachguard.behavior.FeatureDeviation
import io.github.linklow.coachguard.links.LinkRiskLevel
import io.github.linklow.coachguard.links.PhishingLinkClassifier
import io.github.linklow.coachguard.risk.RiskEngine
import io.github.linklow.coachguard.signals.CallState
import io.github.linklow.coachguard.signals.DeviceSignals
import io.github.linklow.coachguard.signals.ScreenCaptureState
import kotlin.concurrent.thread

/**
 * Measures the on-device overhead of the SDK in a minified, non-debuggable build. Run with
 *
 * ```
 * ./gradlew :sample:installBenchmark
 * adb shell am start -n io.github.linklow.coachguard.sample/.bench.BenchmarkActivity --ez run_benchmarks true
 * adb logcat -s CG_BENCH
 * ```
 *
 * and wait for the `DONE` line. Calls that do not need a window run on a background thread; sessions
 * attach to this activity's window, so they run on the main thread, as in an app. Do not touch the
 * screen while it runs: touches are part of what a session measures.
 */
class BenchmarkActivity : Activity() {

    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "CoachGuard benchmark" })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Sessions need an attached, focused window, so start only once it has focus.
        if (hasFocus && !started && intent.getBooleanExtra(EXTRA_RUN, false)) {
            started = true
            thread(name = "cg-bench") {
                runWithoutWindow()
                runOnUiThread {
                    runSessions()
                    Log.i(Timing.TAG, "DONE")
                    finish()
                }
            }
        }
    }

    private fun runWithoutWindow() {
        Timing.measure("CoachGuard.create", warmup = 20, runs = 200) { CoachGuard.create(applicationContext) }

        val guard = CoachGuard.create(applicationContext)
        Timing.measure("CoachGuard.signals (device-wide)", warmup = 20, runs = 300) { guard.signals() }

        val engine = RiskEngine()
        val transaction = TransactionContext(isNewPayee = true)
        Timing.measure("RiskEngine.evaluate", warmup = 1_000, runs = 5_000) {
            engine.evaluate(COACHED_SIGNALS, transaction)
        }

        // Each create() reads and parses the bundled model and popular-domain list again.
        Timing.measure("PhishingLinkClassifier.create", warmup = 2, runs = 20) { PhishingLinkClassifier.create() }

        val classifier = PhishingLinkClassifier.create()
        var index = 0
        Timing.measure("PhishingLinkClassifier.assess", warmup = 500, runs = 5_000) {
            classifier.assess(URLS[index++ % URLS.size])
        }
        // Sanity check that the measured work is real.
        check(classifier.assess("https://mail.google.com/mail/u/0/").level == LinkRiskLevel.LOW)
        check(classifier.assess("https://appleid.apple.com.verify-account-session.xyz/").level != LinkRiskLevel.LOW)
    }

    private fun runSessions() {
        val guard = CoachGuard.create(this)
        guard.startSession(this, SessionOptions(profileId = "bench", learnBehavior = false)).use { session ->
            Timing.measure("GuardSession.assess (open session)", warmup = 20, runs = 300) {
                session.assess(TransactionContext(isNewPayee = true))
            }
        }

        // Build a stored baseline first, so that every measured session loads it from disk.
        guard.resetBehaviorProfile(LIFECYCLE_PROFILE)
        repeat(6) {
            guard.startSession(this, SessionOptions(profileId = LIFECYCLE_PROFILE)).use { it.assess(TransactionContext()) }
        }
        val baseline = guard.startSession(this, SessionOptions(profileId = LIFECYCLE_PROFILE, learnBehavior = false))
            .use { it.assess(TransactionContext()).signals.behavior?.baselineSessions }
        Log.i(Timing.TAG, "stored baseline sessions before lifecycle benchmark: $baseline")
        Timing.measure("startSession + first assess + close (stored profile)", warmup = 5, runs = 100) {
            guard.startSession(this, SessionOptions(profileId = LIFECYCLE_PROFILE, learnBehavior = false))
                .use { it.assess(TransactionContext(isNewPayee = true)) }
        }
        guard.resetBehaviorProfile(LIFECYCLE_PROFILE)
    }

    private companion object {
        const val EXTRA_RUN = "run_benchmarks"
        const val LIFECYCLE_PROFILE = "bench-lifecycle"

        /** A coached transfer: VoIP call, a remote-access app, ADB on, and behavior unlike the baseline. */
        val COACHED_SIGNALS = DeviceSignals(
            callState = CallState.VOIP_CALL,
            screenCapture = ScreenCaptureState.UNKNOWN,
            remoteAccessApps = listOf("com.anydesk.anydeskandroid"),
            accessibilityServices = emptyList(),
            obscuredTouchCount = 0,
            partiallyObscuredTouchCount = 0,
            adbEnabled = true,
            collectedAtMillis = 0L,
            behavior = BehaviorAnalysis(
                features = BehaviorFeatures(26_900, 1, 19_500, 9, 1_900, 110.0),
                baselineSessions = 7,
                isBaselineReady = true,
                anomalyScore = 3.58,
                deviations = listOf(
                    FeatureDeviation(BehaviorFeature.TOUCHES, 9.0, 1.0, 6.4),
                    FeatureDeviation(BehaviorFeature.TIME_AWAY, 19_500.0, 200.0, 5.7),
                ),
            ),
        )

        val URLS = listOf(
            "https://www.example.com/login",
            "http://paypal.com@evil-login.example/verify",
            "http://192.168.10.5:8080/bank/login.php",
            "https://xn--pple-43d.com",
            "secure-update-account-verify.example-billing.top",
            "https://online.citi.com/US/login.do",
            "https://mail.google.com/mail/u/0/",
            "https://sites.google.com/view/account-recovery",
            "https://appleid.apple.com.verify-account-session.xyz/",
            "https://metamask-wallet-restore.web.app/",
        )
    }
}

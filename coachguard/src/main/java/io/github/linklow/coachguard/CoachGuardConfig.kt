package io.github.linklow.coachguard

import io.github.linklow.coachguard.risk.RiskThresholds
import io.github.linklow.coachguard.risk.RiskWeights

/** Configuration for a [CoachGuard] instance. */
public data class CoachGuardConfig @JvmOverloads constructor(
    public val weights: RiskWeights = RiskWeights(),
    public val thresholds: RiskThresholds = RiskThresholds(),
    public val behavior: BehaviorConfig = BehaviorConfig(),
)

/** How the behavioral model learns each user's baseline. */
public data class BehaviorConfig @JvmOverloads constructor(
    /** Sessions a baseline must learn from before it is used to judge new sessions. */
    public val minBaselineSessions: Int = 5,
    /**
     * Roughly how many recent sessions the baseline reflects. Older sessions fade out, so the
     * baseline follows the user as their habits change.
     */
    public val adaptationWindow: Int = 30,
) {
    init {
        require(minBaselineSessions >= 2) {
            "minBaselineSessions must be at least 2 to estimate a spread, was $minBaselineSessions"
        }
        require(adaptationWindow >= minBaselineSessions) {
            "adaptationWindow ($adaptationWindow) must be at least minBaselineSessions ($minBaselineSessions)"
        }
    }
}

/** Options for a [GuardSession]. */
public data class SessionOptions @JvmOverloads constructor(
    /**
     * Hide other apps' overlay windows while the session is open (Android 12+), so they cannot be
     * drawn over the payment screen. Off by default because it changes how the screen behaves.
     * Leave it off if your app already manages `Window.setHideOverlayWindows` itself: closing the
     * session turns the flag off again.
     */
    public val hideOverlayWindows: Boolean = false,
    /**
     * Whose behavior this session is compared with and learned into. Pass a stable identifier of
     * the signed-in user so that people sharing a device get separate baselines. It is hashed
     * before being used as a file name.
     */
    public val profileId: String = DEFAULT_PROFILE_ID,
    /**
     * Add this session to the user's baseline after an assessment that is [risk level][io.github.linklow.coachguard.risk.RiskLevel.LOW]
     * and shows no behavioral anomaly. Suspicious sessions are never learned, so a scam attempt
     * cannot teach the model that it is normal.
     */
    public val learnBehavior: Boolean = true,
    /**
     * Mark the screen as secure (`FLAG_SECURE`) while the session is open, so it shows up black in
     * screen recordings, screen sharing and remote-access viewers on every Android version. Use it
     * where screen capture cannot be detected (below Android 15). Off by default because it also
     * blocks the user's own screenshots. If the window is already secure, the session leaves the
     * flag alone.
     */
    public val protectScreen: Boolean = false,
) {
    public companion object {
        public const val DEFAULT_PROFILE_ID: String = "default"
    }
}

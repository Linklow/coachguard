package io.github.linklow.coachguard.behavior

import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * Interaction statistics that the behavioral model learns for each user.
 *
 * Values are compared on a log scale, `ln(1 + value / unit)`, so that a change from 5 s to 10 s
 * weighs about as much as one from 20 s to 40 s. [minSpread] is the smallest standard deviation
 * assumed on that scale. It keeps a feature that has never varied (for example, a user who never
 * leaves the app mid-payment) from turning a tiny change into an extreme deviation.
 *
 * The [unit] and [minSpread] values are heuristic defaults, not values fitted to data.
 */
public enum class BehaviorFeature(
    internal val unit: Double,
    internal val minSpread: Double,
    private val display: Display,
) {
    /** Time from opening the screen to confirming. */
    TIME_TO_CONFIRM(unit = 1000.0, minSpread = 0.25, display = Display.SECONDS),

    /** How many times the screen lost focus, for example when the user switched to a messenger. */
    FOCUS_LOSSES(unit = 1.0, minSpread = 0.5, display = Display.COUNT),

    /** Total time the screen was out of focus. */
    TIME_AWAY(unit = 1000.0, minSpread = 0.5, display = Display.SECONDS),

    /** Number of touches on the screen. */
    TOUCHES(unit = 1.0, minSpread = 0.25, display = Display.COUNT),

    /** Longest pause between interactions while the screen was in focus. */
    LONGEST_PAUSE(unit = 1000.0, minSpread = 0.25, display = Display.SECONDS),

    /** Average time a finger stayed on the screen per touch. */
    TOUCH_DURATION(unit = 100.0, minSpread = 0.15, display = Display.MILLIS),
    ;

    internal fun transform(value: Double): Double = ln(1.0 + value.coerceAtLeast(0.0) / unit)

    internal fun inverse(transformed: Double): Double = unit * (exp(transformed) - 1.0)

    /** Formats a raw value of this feature for logs, for example `41.0 s` or `3`. */
    internal fun format(value: Double): String = when (display) {
        Display.SECONDS -> String.format(Locale.ROOT, "%.1f s", value / 1000.0)
        Display.MILLIS -> String.format(Locale.ROOT, "%.0f ms", value)
        Display.COUNT -> String.format(Locale.ROOT, "%.1f", value)
    }

    private enum class Display { SECONDS, MILLIS, COUNT }
}

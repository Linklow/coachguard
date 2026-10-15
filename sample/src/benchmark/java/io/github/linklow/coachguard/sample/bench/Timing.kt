package io.github.linklow.coachguard.sample.bench

import android.os.Build
import android.util.Log
import java.util.Locale

/**
 * Minimal timing helper for on-device overhead measurements: warm up, run [runs] times, report the
 * median and the 95th percentile. Results go to logcat under the tag [TAG] in a stable format, so
 * they can be collected with `adb logcat -s CG_BENCH`.
 *
 * These are indicative numbers from one device, not a microbenchmark with locked clocks.
 */
internal object Timing {
    const val TAG = "CG_BENCH"

    fun measure(name: String, warmup: Int, runs: Int, block: () -> Unit): Result {
        repeat(warmup) { block() }
        val samples = LongArray(runs)
        for (i in 0 until runs) {
            val start = System.nanoTime()
            block()
            samples[i] = System.nanoTime() - start
        }
        samples.sort()
        val result = Result(
            name = name,
            runs = runs,
            medianMicros = samples[runs / 2] / 1_000.0,
            p95Micros = samples[(runs * 95 / 100).coerceAtMost(runs - 1)] / 1_000.0,
        )
        Log.i(
            TAG,
            String.format(
                Locale.ROOT,
                "%s | median_us=%.1f | p95_us=%.1f | runs=%d | device=%s %s | android=%d",
                result.name, result.medianMicros, result.p95Micros, result.runs,
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
            ),
        )
        return result
    }

    data class Result(val name: String, val runs: Int, val medianMicros: Double, val p95Micros: Double)
}

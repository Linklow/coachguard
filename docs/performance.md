# On-device performance

How much time CoachGuard adds to a payment flow, measured on a low-cost phone. The numbers come from
the benchmark build of the sample app, which is minified with R8 and not debuggable, like the
builds that apps ship. A debuggable build of the same code was up to four times slower, so those
numbers are not representative.

## Device

| | |
|---|---|
| Phone | Xiaomi Redmi 10 2022 (21121119SG) |
| System on chip | MediaTek Helio G88 (2× Cortex-A75, 6× Cortex-A55) |
| Memory | 6 GB |
| Android | 13 (API 33), MIUI |
| State | USB powered, battery 20%, 33.6 °C |

## Method

Each operation is warmed up, then timed with `System.nanoTime()` over many runs. The table shows the
median, and the 95th percentile because occasional garbage collection and scheduling dominate the
tail. The whole benchmark was run three times, each time in a freshly started process.

- **Without a window.** `CoachGuard.create`, `CoachGuard.signals`, `RiskEngine.evaluate` and the
  link classifier run on a background thread.
- **Sessions.** Sessions attach to an activity's window, so they run on the main thread, as in an
  app.
- **Risk engine input.** `RiskEngine.evaluate` scores a fully populated coached-transfer input:
  a VoIP call, a remote-access app, USB debugging and an anomalous behavioral session.
- **Links.** The link classifier is timed on a fixed rotation of 10 links, from ordinary
  addresses to look-alike and IP-based phishing hosts.
- **Stored profile.** Before the session lifecycle is timed, a behavioral profile with six learned
  sessions is stored on disk, so every timed session loads it.

## Results

Median and range of the three run medians; worst p95 of the three runs.

| Operation | Runs | Median | Range of medians | p95 (worst run) |
|---|---:|---:|---:|---:|
| `CoachGuard.create` | 200 | 0.016 ms | 0.014–0.017 ms | 0.04 ms |
| `CoachGuard.signals()` (device-wide) | 300 | 2.3 ms | 1.4–2.4 ms | 4.0 ms |
| `RiskEngine.evaluate` | 5,000 | 0.064 ms | 0.063–0.064 ms | 0.08 ms |
| `GuardSession.assess` in an open session | 300 | 1.9 ms | 1.8–2.0 ms | 3.3 ms |
| `startSession` + first `assess` + `close`, stored profile | 100 | 3.8 ms | 3.3–5.2 ms | 6.3 ms |
| `PhishingLinkClassifier.create` | 20 | 16 ms | 16–21 ms | 34 ms |
| `PhishingLinkClassifier.assess` | 5,000 | 0.54 ms | 0.54–0.56 ms | 2.3 ms |

What this means for an app:

- **Confirming a payment.** One `assess` when the user confirms costs about 2 ms, well under one
  60 Hz frame (16.7 ms). Almost all of it is reading device state. The risk engine itself takes
  0.06 ms.
- **Opening the payment screen.** Opening a session, loading the stored behavioral profile from
  disk on a background thread, assessing right away and closing costs about 4 ms in total.
- **Link checks.** Loading the classifier reads about 450 KB of bundled data, so create it once, off
  the main thread. After that, checking a link takes about half a millisecond.

## Reproducing

Connect a device with USB debugging enabled, then run:

```bash
./gradlew :sample:installBenchmark
adb shell am start -n io.github.linklow.coachguard.sample/.bench.BenchmarkActivity --ez run_benchmarks true
adb logcat -s CG_BENCH
```

Wait for the `DONE` line, and do not touch the screen while it runs. The code is in
[`sample/src/benchmark`](../sample/src/benchmark).

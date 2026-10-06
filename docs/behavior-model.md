# Behavioral model for coached payments

This document describes how CoachGuard decides that a payment session looks unlike the user's
usual behavior, and how the model is meant to be evaluated. It matches the implementation in
`io.github.linklow.coachguard.behavior`.

## 1. Motivation

In authorized push payment (APP) scams, the victim makes the payment themselves while a scammer
directs them, usually over a phone or VoIP call. Credentials, one-time codes and device binding all
pass, because the legitimate user on their own device is the one acting.

What changes is *how* the user acts. Someone following instructions tends to:

- wait on the payment screen for the next instruction;
- switch to a messenger to copy account details the scammer sent, then come back;
- take longer overall and hesitate before confirming.

Each of these also happens in ordinary payments, and people differ widely in how they use their
banking app. A fixed threshold ("more than 60 seconds is suspicious") would therefore flag slow
users constantly and miss fast ones. The model instead compares each user with their own history.

## 2. Features

A session starts when the host app opens a `GuardSession` on the payment screen and ends at the
assessment, normally when the user taps "Confirm". Timestamps come from `SystemClock.uptimeMillis`,
the clock used by `MotionEvent`.

| Feature | Definition |
|---|---|
| `TIME_TO_CONFIRM` | Time from session start to assessment. |
| `FOCUS_LOSSES` | Number of times the screen's window lost input focus after first gaining it. |
| `TIME_AWAY` | Total time without focus after first gaining it, including a period still running at assessment. |
| `TOUCHES` | Number of `ACTION_DOWN` events delivered to the window. |
| `LONGEST_PAUSE` | Longest interval while in focus with no new touch. An interval starts at a touch or when focus returns and ends at the next touch, a focus loss, or the assessment. |
| `TOUCH_DURATION` | Mean time between `ACTION_DOWN` and `ACTION_UP` over completed touches, ignoring contacts longer than 2 s (long presses and drags). Undefined when there are none. |

Touch and focus events are observed by wrapping the window's `Window.Callback`. No coordinates,
text, or view identifiers are recorded, and only the aggregates above leave the recorder.

## 3. Model

### Scaling

Each raw value `v` is mapped to `x = ln(1 + v / u)` with a per-feature unit `u` (1 s for times,
100 ms for touch duration, 1 for counts). On this scale, doubling a duration is a similar step
whether it goes from 5 to 10 s or from 20 to 40 s, and counts of zero remain finite.

### Per-user baseline

For each feature the profile keeps a count `n`, a mean `μ` and a variance `σ²`. A learned session
with value `x` updates them as

```
n  ← n + 1
α  = max(1/n, 1/W)
δ  = x − μ
μ  ← μ + α·δ
σ² ← (1 − α)·(σ² + α·δ²)
```

With `α = 1/n` this is the exact running mean and population variance. Once `n` exceeds the
adaptation window `W` (default 30), `α` stays at `1/W`. The baseline then becomes an
exponentially weighted estimate that follows gradual changes in the user's habits and forgets old
sessions.

### Deviation and anomaly score

For a new session, each feature with at least `N` learned observations (default 5) gets a z-score

```
z = (x − μ) / max(σ, s_min)
```

The floor `s_min` (0.15 to 0.5 depending on the feature) stops a feature that has never varied,
such as a user who has never left the app mid-payment, from turning any change into an infinite
deviation.

The session's anomaly score is the root mean square of the available z-scores:

```
A = sqrt( (1/k) · Σ z_i² )
```

`A ≈ 1` corresponds to ordinary variation. A single feature has to deviate by about `2·√k` (about
4.9σ with all six features) to reach `A = 2` on its own, while several moderately unusual features
reach it together. Under a Gaussian model with six independent features, `A ≥ 2` corresponds to
`χ²₆ ≥ 24`, which has a probability below 0.1%. Real behavior is heavier-tailed than that, which is
why the threshold is a configurable parameter rather than a derived constant.

## 4. Use in the risk score

When the baseline is ready and `A ≥ 2.0` (`RiskThresholds.behaviorAnomaly`):

- `BEHAVIOR_ANOMALY` fires with weight 0.25. Alone it keeps the session `LOW` (score 0.25), since
  unusual behavior by itself is common and harmless.
- `COACHED_BEHAVIOR_DURING_CALL` fires with weight 0.35 if a cellular or VoIP call is also in
  progress. Together with `ACTIVE_CALL` (0.35) the session scores
  `1 − 0.65 · 0.75 · 0.65 ≈ 0.68`, which is `HIGH`.

The evidence attached to `BEHAVIOR_ANOMALY` lists the anomaly score and up to three features with
|z| ≥ 1, in raw units.

## 5. Learning policy

A session is added to the baseline at most once, at its first assessment, and only if:

1. the assessment level is `LOW`;
2. `BEHAVIOR_ANOMALY` did not fire;
3. the stored profile was loaded, so saving cannot overwrite it with a fresh one;
4. the host has not disabled learning with `SessionOptions(learnBehavior = false)`.

Rule 1 and 2 keep suspicious sessions out of the baseline. Without them, a scammer who coached a
victim through several payments could gradually make that behavior look normal.

## 6. Privacy and storage

- Each profile is a small properties file in `Context.noBackupFilesDir`, which Android excludes from
  Auto Backup. Behavioral data therefore is not uploaded with the app's backups and is not
  restored onto a different device where it would not apply.
- The file name is the first 128 bits of SHA-256 of the host-supplied profile ID, so user
  identifiers do not appear in the file system.
- The file contains, per feature, three numbers: count, mean and variance. It holds no events,
  timestamps, coordinates or content.
- Reads and writes run on a single background thread; writes use `AtomicFile`.
- `CoachGuard.resetBehaviorProfile(profileId)` deletes a profile.

## 7. Parameters

| Parameter | Default | Where |
|---|---|---|
| Minimum baseline sessions `N` | 5 | `BehaviorConfig.minBaselineSessions` |
| Adaptation window `W` | 30 | `BehaviorConfig.adaptationWindow` |
| Anomaly threshold | 2.0 | `RiskThresholds.behaviorAnomaly` |
| `BEHAVIOR_ANOMALY` weight | 0.25 | `RiskWeights.behaviorAnomaly` |
| `COACHED_BEHAVIOR_DURING_CALL` weight | 0.35 | `RiskWeights.coachedBehaviorDuringCall` |
| Feature units and spread floors | see `BehaviorFeature` | not configurable |

All defaults are engineering judgements chosen before any evaluation. They should be revisited
once the study below has been run.

## 8. Limitations and threats to validity

- **Cold start.** A new user, or one who reinstalled the app, has no baseline for their first five
  sessions. Scammers often target exactly such moments, for example right after a "security
  reinstall" they asked for.
- **Shared flows.** In-app dialogs and the system biometric prompt take focus. If they appear in
  every payment they are absorbed into the baseline. If they appear only sometimes, they add noise.
- **Legitimate copying.** Users paying a friend often switch to a messenger to copy details, just as
  coached victims do. This is why behavior alone stays `LOW`, and the call signal is what raises
  the risk.
- **Independence assumption.** The features are correlated (for example, time away also lengthens
  time to confirm), so the χ² reading of the threshold is only approximate.
- **Not evaluated yet.** No claim about detection rates is made until the study below is done.

## 9. Evaluation plan

The study measures whether the anomaly score separates coached from self-directed payments, and at
what cost in false alarms.

**Design.** Within-subject. Each participant uses the sample app on their own phone.

1. *Baseline phase:* 8 to 10 ordinary transfers to known payees spread over several days, done
   alone.
2. *Test phase:* in random order, a few more ordinary transfers and two or three coached
   transfers, in which a researcher calls the participant and talks them through paying a new
   payee whose details arrive by messenger.

No real money moves, and participants give informed consent. Only the aggregate features already
stored by the SDK are collected.

**Metrics.**

- ROC AUC of the anomaly score for coached versus ordinary test sessions, overall and per
  participant.
- True positive rate at a 5% false positive rate.
- How the result changes when the call signal is combined with behavior, as the SDK does by
  default.
- Sensitivity to `N`, `W`, the spread floors and the threshold.
- Overhead on device: assessment latency, profile load time and file size.

**Baselines for comparison.**

- Fixed population-wide thresholds on the same features, without per-user baselines.
- Call detection alone.
- An Isolation Forest trained on the same per-user history, to check whether the simpler Gaussian
  model gives up accuracy.

## 10. Future work

- **Population prior for cold start.** A prior learned across many users and shipped with the app
  or improved through federated averaging, so that new users get a reasonable starting baseline
  without any raw data leaving devices.
- **Richer touch dynamics.** Typing rhythm in amount and payee fields, reported by the host app.
- **Mid-session signals.** Reacting when a call starts while the payment screen is open, rather than
  only at confirmation.

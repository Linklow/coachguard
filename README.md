# CoachGuard

[![CI](https://github.com/Linklow/coachguard/actions/workflows/ci.yml/badge.svg)](https://github.com/Linklow/coachguard/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.linklow/coachguard)](https://central.sonatype.com/artifact/io.github.linklow/coachguard)
[![Release](https://img.shields.io/github/v/release/Linklow/coachguard)](https://github.com/Linklow/coachguard/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Min SDK](https://img.shields.io/badge/minSdk-24-brightgreen.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg)

On-device detection of **coached payments** for Android banking and fintech apps.

In a coached payment, a scammer stays on the line with the victim (by phone or a WhatsApp call),
often asks them to install a remote-access app or share their screen, and talks them through
sending money. The victim authenticates the payment themselves, so passwords, OTPs and device
binding do not stop it.

CoachGuard looks for signs of this situation at the moment the user confirms a payment and returns
an explainable risk assessment the app can act on, for example by showing a warning or adding a
cooling-off delay.

- **Runs entirely on the device.** No network requests, no backend, no data leaves the phone.
- **Learns each user's habits on the device.** An unsupervised model notices when someone
  interacts with the payment screen unlike their usual self, as people do when following
  instructions over the phone. It needs no labelled fraud data.
- **Checks payment links on the device.** An optional module judges links from QR codes and
  messages with an explainable machine-learning model.
- **Zero dependencies** beyond the Kotlin standard library. The core AAR is under 100 KB.
- **No runtime permission prompts.** It uses only two normal, install-time permissions.
- **Explainable.** Every point of the score comes with a reason code and evidence.

> **Version 1.0.0.** The public API follows [semantic versioning](https://semver.org/). The default
> rule weights are heuristic starting points that have not been calibrated on real fraud data; they
> are configurable, and calibration and a user study are the next steps (see [Roadmap](#roadmap)).

## Installation

The libraries are on Maven Central:

```kotlin
dependencies {
    implementation("io.github.linklow:coachguard:1.0.0")
    implementation("io.github.linklow:coachguard-links:1.0.0") // optional: phishing link check
}
```

Both need Android 7.0 (API 24) or later and depend only on the Kotlin standard library, which
Kotlin apps already include. They are built with Kotlin 2.3, so Kotlin apps need Kotlin 2.2 or
later; Java apps work as is. Artifacts are signed with the key
`E018 4B53 8FA6 BBCF D2E6 13CF 56BA 598C 6809 FAF3`.

Each [release](https://github.com/Linklow/coachguard/releases/latest) also has the AARs for manual
installation and a demo APK.

| Module | What it is | Size |
|---|---|---|
| `coachguard` | Device signals, behavioral model, risk engine | ~95 KB |
| `coachguard-links` | Phishing link classifier with its model | ~245 KB |

## Usage

Open a session when the payment screen opens, assess when the user confirms, and close the session
when the screen goes away:

```kotlin
class ConfirmTransferActivity : ComponentActivity() {

    private lateinit var session: GuardSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Watch this screen while it is open: screen sharing, overlay taps, behavior and device signals.
        // profileId keeps baselines separate for different users of the same device.
        session = CoachGuard.create(this).startSession(this, SessionOptions(profileId = currentUserId))
        // ... set up the UI
    }

    private fun onConfirmClicked() {
        val assessment = session.assess(
            TransactionContext(isNewPayee = true, isAmountUnusual = false),
        )
        when (assessment.level) {
            RiskLevel.HIGH -> showScamWarning(assessment.reasons)
            RiskLevel.MEDIUM -> showGentleReminder()
            RiskLevel.LOW -> proceed()
        }
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }
}
```

The app never passes amounts, account numbers or payee details to the SDK, only the flags it has
already computed for its own users.

Call `assess()` when the user actually confirms. The behavioral model treats the moment of
assessment as the end of the session, and the first low-risk assessment of a session is learned
into the baseline. For a preliminary check earlier in the flow, open the session with
`SessionOptions(learnBehavior = false)`.

Without a screen, `CoachGuard.create(context).assess()` evaluates the device-wide signals only.
`RiskEngine` is pure Kotlin, so the same scoring can also run on a server against signals
reported by the app.

### Protecting the payment screen

```kotlin
session = CoachGuard.create(this).startSession(
    this,
    SessionOptions(
        protectScreen = true,      // FLAG_SECURE: black in recordings, screen sharing, remote viewers
        hideOverlayWindows = true, // Android 12+: hide other apps' overlays
    ),
)
```

Screen capture can only be *detected* from Android 15. `protectScreen` *prevents* it on every
version: the screen shows up black to a scammer watching through screen sharing or a remote-access
app. Both options are off by default because they change how the screen behaves; for example,
`protectScreen` also blocks the user's own screenshots.

### Checking a link

```kotlin
val classifier = PhishingLinkClassifier.create() // load once, off the main thread on startup paths
val result = classifier.assess(scannedUrl)
if (result.level == LinkRiskLevel.HIGH) warnUser(result.reasons)
```

## What it detects

| Signal | How | Android | Permission |
|---|---|---|---|
| Active call, cellular or VoIP | System audio mode | 7.0+ | none |
| Screen being recorded or shared, including by remote-access apps | `WindowManager.addScreenRecordingCallback` | 15+ (below: `UNKNOWN`, see `protectScreen`) | `DETECT_SCREEN_RECORDING` (normal) |
| Remote-access app installed (AnyDesk, TeamViewer QuickSupport, RustDesk, ...) | Package visibility for an explicit list of packages | 7.0+ | none (`<queries>`, no `QUERY_ALL_PACKAGES`) |
| Remote-access app able to control the screen | Its accessibility service is enabled | 7.0+ | none |
| Untrusted accessibility service that can read or control the screen | Enabled services that are not system apps and not declared accessibility tools | 7.0+ (tool flag 12+) | none |
| Tap while an overlay covers the touched point (tapjacking) | `MotionEvent.FLAG_WINDOW_IS_OBSCURED` | 7.0+ | none |
| USB / wireless debugging enabled | `Settings.Global.ADB_ENABLED` | 7.0+ | none |
| Interaction unlike the user's own baseline: long hesitation, leaving the app mid-payment, unusual touch rhythm | Per-user behavioral model, see below | 7.0+ | none |

Combined with what the app knows about the payment (new payee, unusual amount), these produce
rules such as *transfer to a new payee during a call*, the core pattern of impersonation scams.

## How the score works

Each rule that fires contributes a weight `w` in `[0, 1]`. Rules are combined as independent
pieces of evidence (noisy-OR):

```
score = 1 - Π (1 - w)
```

so the score stays in `[0, 1]`, never decreases when another rule fires, and is fully explained by
the list of reasons. The default levels are `MEDIUM` from 0.30 and `HIGH` from 0.60.

| Rule | Default weight |
|---|---|
| `REMOTE_CONTROL_ENABLED` | 0.50 |
| `SCREEN_CAPTURED` | 0.45 |
| `OBSCURED_TOUCH` | 0.40 |
| `CALL_DURING_NEW_PAYEE_TRANSFER` | 0.40 |
| `ACTIVE_CALL` | 0.35 |
| `COACHED_BEHAVIOR_DURING_CALL` | 0.35 |
| `SCREEN_COMPROMISED_DURING_TRANSFER` | 0.30 |
| `UNTRUSTED_ACCESSIBILITY_SERVICE` | 0.25 |
| `BEHAVIOR_ANOMALY` (anomaly score ≥ 2.0) | 0.25 |
| `REMOTE_ACCESS_APP_INSTALLED` | 0.15 |
| `NEW_PAYEE` | 0.10 |
| `UNUSUAL_AMOUNT` | 0.10 |
| `ADB_ENABLED` | 0.05 |

For example, a call alone scores 0.35 (`MEDIUM`). A transfer to a new payee during a call scores
0.65 (`HIGH`). Unusual behavior alone stays `LOW` at 0.25, but unusual behavior during a call
scores 0.68 (`HIGH`). Weights and thresholds are configurable through `CoachGuardConfig`, and a
weight of 0 disables a rule.

## Behavioral model

People who are being talked through a payment behave differently from how they normally pay:
they wait for the next instruction, switch to a messenger to copy details, come back, and confirm
after a long pause. CoachGuard learns what is normal for each user and flags sessions that depart
from it.

- **Features.** For each session on a guarded screen: time to confirm, number of times the screen
  lost focus, time spent away, number of touches, longest pause while in focus, and average touch
  duration.
- **Model.** Per-user running mean and variance of each feature on a log scale, updated with an
  adaptive exponentially weighted estimate (exact for the first 30 sessions, then following the
  user's drift). A session's anomaly score is the root mean square of its per-feature z-scores.
  It is judged only once the baseline has 5 sessions.
- **Explanations.** Every deviation is reported in plain units, for example
  `TIME_AWAY: 41.0 s vs usual 0.0 s (+6.9σ)`.
- **Safe learning.** Only sessions assessed as low risk and without a behavioral anomaly are
  learned, so a scam attempt cannot teach the model that it is normal.
- **Privacy.** Only six running means and variances per user are stored, in the app's no-backup
  directory. Individual touches are never stored. `CoachGuard.resetBehaviorProfile()` erases a
  profile.

The full method, including an evaluation protocol, is in [docs/behavior-model.md](docs/behavior-model.md).

## Phishing link check

`coachguard-links` judges whether a link, for example one decoded from a payment QR code, points to
a phishing host.

- **Model.** Logistic regression over character n-grams of the host and a few structural
  features (length, hyphens, digits, IP address, punycode, `user@host` tricks). Weights are
  quantized to 8 bits: about 260 KB.
- **Explainable.** Every decision breaks down into the n-grams and features that drove it, for
  example `top-level domain .xyz` or `has_userinfo = 1`.
- **Popular domains.** The model judges spelling, not reputation, so hosts under the 10,000 most
  popular registered domains of the [Majestic Million](https://majestic.com/reports/majestic-million)
  are reported as low risk. Link shorteners, cloud platforms where anyone can create subdomains, and
  hosts already seen hosting phishing are excluded.
- **Same results as training.** The Kotlin feature extractor is a port of the Python
  specification, and a test checks that it reproduces the training scores on 254 URLs (maximum
  difference 1e-16).

### Honest numbers

The model was trained on the PhiUSIIL dataset (UCI, 2024). In that dataset every legitimate URL has
the form `https://www.<domain>`, so a model that sees only *how a URL is written* (scheme, `www`,
trailing slash, path) reaches 99.75% accuracy without knowing anything about the site. CoachGuard's
model therefore looks at the host only, is trained with additional legitimate subdomains, and is
evaluated on registered domains it has never seen:

| On unseen registered domains | Value |
|---|---|
| ROC AUC | 0.897 |
| Phishing detected at `HIGH` / `MEDIUM` | 53.5% / 64.1% |
| False alarms at `HIGH` / `MEDIUM`, all legitimate hosts | 0.66% / 4.4% |
| Fresh phishing from OpenPhish (different source), detected at `HIGH` / `MEDIUM` | 76.4% / 86.0% |

These are lower than the 99%+ often reported on this dataset, because they measure recognizing
phishing hosts rather than recognizing the dataset's formatting. Details, ablations and limitations
are in [ml/reports/phishing-model-report.md](ml/reports/phishing-model-report.md). How to retrain:
[ml/README.md](ml/README.md).

## Recommended handling

Treat the result as a reason to add friction, not as proof of fraud. Most users who are on a call
while paying are not being scammed. Effective responses include a clear warning ("Banks and police
never ask you to move money while on a call"), a short delay before high-risk transfers, or an
extra confirmation step. Avoid hard blocks based on the score alone.

## Limitations

- **Heuristic weights.** The defaults are not yet fitted to labelled fraud data.
- **Behavioral model needs history.** It stays silent for a user's first 5 sessions, and it has not
  yet been evaluated in a user study.
- **Focus includes your own dialogs.** In-app dialogs and the system biometric prompt also take
  focus from the screen and count as time away. When they appear in every payment, they simply
  become part of the user's baseline.
- **VoIP calls in your own app.** Your app's own voice or video features also put the device in
  communication mode and register as a VoIP call.
- **Screen capture before Android 15** cannot be observed and is reported as `UNKNOWN`; use
  `protectScreen` to prevent it instead.
- **Remote-access detection** covers a fixed list of tools that can capture or control this
  device; renamed or unknown ones are only caught through their accessibility service.
- **Links are judged by host.** A phishing page on a reputable or popular host is out of reach.
- **Not a security boundary.** Malware with root access can hide from any in-app check. CoachGuard
  targets social engineering, where the victim's device is not compromised at the OS level.

## Roadmap

- Feed the link check into the payment risk score
- User study of the behavioral model (protocol in [docs/behavior-model.md](docs/behavior-model.md))
- Continuous monitoring (callbacks when a call starts or screen sharing begins mid-session)

## Project structure

| Path | Contents |
|---|---|
| [`coachguard/`](coachguard) | Core SDK: signals, behavioral model, risk engine |
| [`coachguard-links/`](coachguard-links) | Phishing link classifier and its bundled model |
| [`sample/`](sample) | Demo app with a payment confirmation screen and a link checker |
| [`ml/`](ml) | Training pipeline, feature specification and model report |
| [`docs/`](docs) | Method descriptions |

## Building

Requires JDK 17+ and the Android SDK (platform 36 for the libraries, 37 for the sample app).

```bash
./gradlew :coachguard:testDebugUnitTest :coachguard-links:testDebugUnitTest
./gradlew :coachguard:lintRelease :coachguard-links:lintRelease
./gradlew :sample:installDebug
```

## Security

To report a vulnerability, see [SECURITY.md](SECURITY.md). Please do not open a public issue.

## License

Apache License 2.0. See [LICENSE](LICENSE). Changes are listed in [CHANGELOG.md](CHANGELOG.md).

### Data used by the link check

The bundled `popular-domains.txt` contains data from:

- [Majestic Million](https://majestic.com/reports/majestic-million) by Majestic, licensed under
  [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/);
- the [Public Suffix List](https://publicsuffix.org), licensed under the
  [Mozilla Public License 2.0](https://mozilla.org/MPL/2.0/);
- the PhiUSIIL Phishing URL Dataset (A. Prasad and S. Chandra, *Computers & Security*, 2024),
  licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), which was also used to
  train the model.

Copyright 2026 Ivan Mishchenko

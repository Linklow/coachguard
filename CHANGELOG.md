# Changelog

All notable changes to this project are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## 1.0.0 - 2026-10-06

First public release, available on Maven Central as `io.github.linklow:coachguard` and
`io.github.linklow:coachguard-links`.

### `coachguard`

- Device signals collected on the device without runtime permissions: active cellular or VoIP
  call, screen recording or sharing (Android 15+), installed remote-access apps and their
  accessibility services, untrusted accessibility services, taps through overlays, USB debugging.
- Explainable risk engine combining signals and transaction flags (new payee, unusual amount)
  into a score, a level and reason codes with evidence.
- Per-user behavioral model learned on the device, detecting sessions that depart from the user's
  usual interaction with the payment screen. Only low-risk sessions are learned.
- Session options to hide overlay windows (Android 12+) and to protect the screen from capture
  with `FLAG_SECURE`.

### `coachguard-links`

- On-device phishing link classifier: logistic regression over host features, 8-bit weights,
  with per-decision explanations.
- Popular-domain list from the Majestic Million, excluding link shorteners and platforms where
  anyone can create subdomains.

### Training and documentation

- Reproducible training pipeline in `ml/`, with a report showing that the PhiUSIIL dataset can be
  classified at 99.75% accuracy from URL formatting alone, and how the model avoids it.
- Method description of the behavioral model with an evaluation protocol.

# Security policy

CoachGuard is a fraud-prevention library, so weaknesses in it can be used to get around the
protection it gives. Please report them privately.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/Linklow/coachguard/security/advisories/new)
for this repository. Do not open a public issue for a vulnerability.

Useful details: the affected module and version, the Android version and device, and steps or
code that reproduce the problem.

You can expect an acknowledgement within 7 days. Fixes are released as a new version, and the
advisory is published once a fix is available.

## Scope

In scope: ways to make the SDK miss a signal it claims to detect, crashes or data exposure caused
by the SDK, and flaws in how the behavioral profile is stored.

Out of scope: limitations already listed in the README, such as screen capture not being detectable
before Android 15, or phishing pages hosted on reputable domains.

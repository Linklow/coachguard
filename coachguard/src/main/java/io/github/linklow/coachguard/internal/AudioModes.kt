package io.github.linklow.coachguard.internal

import io.github.linklow.coachguard.signals.CallState

// Values of android.media.AudioManager.MODE_*. Declared locally because some of them were added
// after this library's minSdk, and the system may report them on any newer device.
private const val MODE_NORMAL = 0
private const val MODE_RINGTONE = 1
private const val MODE_IN_CALL = 2
private const val MODE_IN_COMMUNICATION = 3
private const val MODE_CALL_SCREENING = 4 // API 30
private const val MODE_CALL_REDIRECT = 5 // API 33
private const val MODE_COMMUNICATION_REDIRECT = 6 // API 33

/** Maps the value of `AudioManager.getMode()` to a [CallState]. */
internal fun callStateFromAudioMode(mode: Int): CallState = when (mode) {
    MODE_NORMAL -> CallState.NONE
    MODE_RINGTONE, MODE_CALL_SCREENING -> CallState.RINGING
    MODE_IN_CALL, MODE_CALL_REDIRECT -> CallState.CELLULAR_CALL
    MODE_IN_COMMUNICATION, MODE_COMMUNICATION_REDIRECT -> CallState.VOIP_CALL
    else -> CallState.UNKNOWN
}

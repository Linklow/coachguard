package io.github.linklow.coachguard.internal

import io.github.linklow.coachguard.signals.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModesTest {

    @Test
    fun `maps every documented audio mode`() {
        val expected = mapOf(
            0 to CallState.NONE, // MODE_NORMAL
            1 to CallState.RINGING, // MODE_RINGTONE
            2 to CallState.CELLULAR_CALL, // MODE_IN_CALL
            3 to CallState.VOIP_CALL, // MODE_IN_COMMUNICATION
            4 to CallState.RINGING, // MODE_CALL_SCREENING
            5 to CallState.CELLULAR_CALL, // MODE_CALL_REDIRECT
            6 to CallState.VOIP_CALL, // MODE_COMMUNICATION_REDIRECT
        )

        expected.forEach { (mode, state) -> assertEquals("mode $mode", state, callStateFromAudioMode(mode)) }
    }

    @Test
    fun `unknown and invalid modes map to unknown`() {
        assertEquals(CallState.UNKNOWN, callStateFromAudioMode(-2)) // MODE_INVALID
        assertEquals(CallState.UNKNOWN, callStateFromAudioMode(42))
    }
}

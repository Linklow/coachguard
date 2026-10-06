package io.github.linklow.coachguard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileFileNameTest {

    @Test
    fun `uses the first 128 bits of SHA-256`() {
        // NIST test vector: SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a3...
        assertEquals("behavior-ba7816bf8f01cfea414140de5dae2223.properties", profileFileName("abc"))
    }

    @Test
    fun `different users get different files and the id does not leak`() {
        val first = profileFileName("user-42@example.com")
        val second = profileFileName("user-43@example.com")

        assertNotEquals(first, second)
        assertFalse(first.contains("user"))
        assertEquals(first, profileFileName("user-42@example.com"))
    }
}

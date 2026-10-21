package com.rouddy.smstransmitter.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberResolverTest {
    @Test
    fun `normalizePhoneNumber matches server rules`() {
        // Keep in sync with normalize_phone_number_matches_android_rules on the server.
        val cases = listOf(
            "01012345678" to "01012345678",
            "010-1234-5678" to "01012345678",
            " 010 1234 5678 " to "01012345678",
            "+82 10-1234-5678" to "+821012345678",
            "\t01012345678\n" to "01012345678",
            "(010)1234.5678" to "(010)1234.5678",
            "" to null,
            "   " to null,
            " - " to null,
        )
        for ((raw, expected) in cases) {
            assertEquals("\"$raw\"", expected, PhoneNumberResolver.normalizePhoneNumber(raw))
        }
        assertEquals(null, PhoneNumberResolver.normalizePhoneNumber(null))
    }
}

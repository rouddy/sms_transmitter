package com.rouddy.smstransmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmPayloadParserTest {
    @Test
    fun `returns null when message is missing`() {
        val payload = FcmPayloadParser.parse(mapOf("callback" to "https://example.com"))
        assertNull(payload)
    }

    @Test
    fun `returns null when message is blank`() {
        val payload = FcmPayloadParser.parse(mapOf("message" to "   "))
        assertNull(payload)
    }

    @Test
    fun `returns parsed payload when message exists`() {
        val payload = FcmPayloadParser.parse(
            mapOf(
                "message" to "  hello sms  ",
                "callback" to " https://example.com/callback "
            )
        )

        requireNotNull(payload)
        assertEquals("hello sms", payload.message)
        assertEquals("https://example.com/callback", payload.callback)
    }
}

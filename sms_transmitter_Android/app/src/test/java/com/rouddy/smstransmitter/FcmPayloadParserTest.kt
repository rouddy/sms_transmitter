package com.rouddy.smstransmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcmPayloadParserTest {
    @Test
    fun `returns null when phoneNumber is missing`() {
        val payload = FcmPayloadParser.parse(mapOf("message" to "hello"))
        assertNull(payload)
    }

    @Test
    fun `returns null when phoneNumber is blank`() {
        val payload = FcmPayloadParser.parse(
            mapOf(
                "phoneNumber" to " ",
                "message" to "hello"
            )
        )
        assertNull(payload)
    }

    @Test
    fun `returns null when message is missing`() {
        val payload = FcmPayloadParser.parse(
            mapOf(
                "phoneNumber" to "01012345678",
                "callback" to "https://example.com"
            )
        )
        assertNull(payload)
    }

    @Test
    fun `returns null when message is blank`() {
        val payload = FcmPayloadParser.parse(
            mapOf(
                "phoneNumber" to "01012345678",
                "message" to "   "
            )
        )
        assertNull(payload)
    }

    @Test
    fun `returns parsed payload when message exists`() {
        val payload = FcmPayloadParser.parse(
            mapOf(
                "phoneNumber" to "  01012345678  ",
                "message" to "  hello sms  ",
                "requestId" to " req-123 "
            )
        )

        requireNotNull(payload)
        assertEquals("01012345678", payload.phoneNumber)
        assertEquals("hello sms", payload.message)
        assertEquals("req-123", payload.requestId)
    }

    @Test
    fun `returns null requestId when requestId is missing`() {
        val payload = FcmPayloadParser.parse(
            mapOf(
                "phoneNumber" to "01012345678",
                "message" to "hello sms"
            )
        )

        requireNotNull(payload)
        assertNull(payload.requestId)
    }
}

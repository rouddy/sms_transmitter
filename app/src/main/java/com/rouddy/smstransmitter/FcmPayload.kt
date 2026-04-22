package com.rouddy.smstransmitter

data class FcmPayload(
    val message: String,
    val callback: String
)

object FcmPayloadParser {
    private const val KEY_MESSAGE = "message"
    private const val KEY_CALLBACK = "callback"

    fun parse(data: Map<String, String>): FcmPayload? {
        val message = data[KEY_MESSAGE]?.trim().orEmpty()
        if (message.isBlank()) return null

        val callback = data[KEY_CALLBACK]?.trim().orEmpty()
        return FcmPayload(message = message, callback = callback)
    }
}

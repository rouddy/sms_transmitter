package com.rouddy.smstransmitter

data class FcmPayload(
    val phoneNumber: String,
    val message: String,
    val callback: String
)

object FcmPayloadParser {
    private const val KEY_PHONE_NUMBER = "phoneNumber"
    private const val KEY_MESSAGE = "message"
    private const val KEY_CALLBACK = "callback"

    fun parse(data: Map<String, String>): FcmPayload? {
        val phoneNumber = data[KEY_PHONE_NUMBER]?.trim().orEmpty()
        if (phoneNumber.isBlank()) return null

        val message = data[KEY_MESSAGE]?.trim().orEmpty()
        if (message.isBlank()) return null

        val callback = data[KEY_CALLBACK]?.trim().orEmpty()
        return FcmPayload(phoneNumber = phoneNumber, message = message, callback = callback)
    }
}

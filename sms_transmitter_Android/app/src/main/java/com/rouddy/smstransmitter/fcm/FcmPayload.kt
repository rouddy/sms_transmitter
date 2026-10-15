package com.rouddy.smstransmitter.fcm

data class FcmPayload(
    val senderPhoneNumber: String,
    val phoneNumber: String,
    val message: String,
    val requestId: String? = null,
)

object FcmPayloadParser {
    private const val KEY_SENDER_PHONE_NUMBER = "senderPhoneNumber"
    private const val KEY_PHONE_NUMBER = "phoneNumber"
    private const val KEY_MESSAGE = "message"
    private const val KEY_REQUEST_ID = "requestId"

    fun parse(data: Map<String, String>): FcmPayload? {
        val senderPhoneNumber = data[KEY_SENDER_PHONE_NUMBER]?.trim().orEmpty()
        if (senderPhoneNumber.isBlank()) return null

        val phoneNumber = data[KEY_PHONE_NUMBER]?.trim().orEmpty()
        if (phoneNumber.isBlank()) return null

        val message = data[KEY_MESSAGE]?.trim().orEmpty()
        if (message.isBlank()) return null

        val requestId = data[KEY_REQUEST_ID]?.trim()?.takeIf { it.isNotBlank() }
        return FcmPayload(
            senderPhoneNumber = senderPhoneNumber,
            phoneNumber = phoneNumber,
            message = message,
            requestId = requestId
        )
    }
}

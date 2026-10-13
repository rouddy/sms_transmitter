package com.rouddy.smstransmitter

import android.app.Activity
import android.telephony.SmsManager

data class SendSmsResult(
    val success: Boolean,
    val error: String?
)

/** Maps the result code of an SMS sent [android.app.PendingIntent] to a [SendSmsResult]. */
object SmsSendResultCodes {
    fun toResult(resultCode: Int): SendSmsResult {
        if (resultCode == Activity.RESULT_OK) return SendSmsResult(success = true, error = null)
        val reason = when (resultCode) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "sending limit exceeded"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short code not allowed"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short code never allowed"
            SmsManager.RESULT_NETWORK_REJECT -> "network rejected"
            SmsManager.RESULT_INVALID_SMSC_ADDRESS -> "invalid SMSC address"
            SmsManager.RESULT_NETWORK_ERROR -> "network error"
            SmsManager.RESULT_NO_DEFAULT_SMS_APP -> "no default SMS app"
            else -> null
        }
        val error = if (reason != null) {
            "SMS send failed: $reason (resultCode=$resultCode)."
        } else {
            "SMS send failed (resultCode=$resultCode)."
        }
        return SendSmsResult(success = false, error = error)
    }
}

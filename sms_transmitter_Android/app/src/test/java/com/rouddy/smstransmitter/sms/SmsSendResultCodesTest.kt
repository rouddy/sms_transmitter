package com.rouddy.smstransmitter.sms

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmsSendResultCodesTest {
    @Test
    fun `RESULT_OK is success`() {
        assertEquals(SendSmsResult(success = true, error = null), SmsSendResultCodes.toResult(Activity.RESULT_OK))
    }

    @Test
    fun `known error codes are described`() {
        assertEquals(
            SendSmsResult(success = false, error = "SMS send failed: no service (resultCode=4)."),
            SmsSendResultCodes.toResult(SmsManager.RESULT_ERROR_NO_SERVICE),
        )
        assertEquals(
            SendSmsResult(success = false, error = "SMS send failed: radio off (resultCode=2)."),
            SmsSendResultCodes.toResult(SmsManager.RESULT_ERROR_RADIO_OFF),
        )
    }

    @Test
    fun `unknown codes are failures with the raw code`() {
        assertEquals(
            SendSmsResult(success = false, error = "SMS send failed (resultCode=9999)."),
            SmsSendResultCodes.toResult(9999),
        )
        // Activity.RESULT_CANCELED (0) is not a send success.
        assertFalse(SmsSendResultCodes.toResult(Activity.RESULT_CANCELED).success)
    }
}

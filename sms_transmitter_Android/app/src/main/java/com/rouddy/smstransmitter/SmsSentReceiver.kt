package com.rouddy.smstransmitter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Receives the per-part "sent" result of an SMS. Once every part of a message has a result,
 * finalizes its history row and reports the outcome to the server.
 *
 * Registered in the manifest so results are still delivered after the FCM service has stopped.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val historyId = intent.getLongExtra(EXTRA_HISTORY_ID, -1L)
        if (historyId < 0) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val senderPhoneNumber = intent.getStringExtra(EXTRA_SENDER_PHONE_NUMBER)

        val partResult = SmsSendResultCodes.toResult(resultCode)
        if (!partResult.success) {
            Log.w(TAG, "SMS part failed for historyId=$historyId: ${partResult.error}")
        }

        val historyDb = SmsHistoryDatabase(context)
        val finalResult = try {
            historyDb.recordSentPartResult(historyId, partResult.error)
        } finally {
            historyDb.close()
        } ?: return

        Log.i(TAG, "SMS send finished for historyId=$historyId requestId=$requestId success=${finalResult.success}")
        val pendingResult = goAsync()
        SmsResultReporter.report(context.applicationContext, requestId, senderPhoneNumber, finalResult) {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "SmsSentReceiver"
        private const val ACTION_SMS_SENT = "com.rouddy.smstransmitter.action.SMS_SENT"
        private const val EXTRA_HISTORY_ID = "historyId"
        private const val EXTRA_REQUEST_ID = "requestId"
        private const val EXTRA_SENDER_PHONE_NUMBER = "senderPhoneNumber"

        /** Builds the sent [PendingIntent] for one part of the message stored as [historyId]. */
        fun sentIntent(
            context: Context,
            historyId: Long,
            partIndex: Int,
            requestId: String?,
            senderPhoneNumber: String,
        ): PendingIntent {
            val intent = Intent(context, SmsSentReceiver::class.java).apply {
                action = ACTION_SMS_SENT
                // Extras are ignored when matching PendingIntents, so the data URI keeps each part distinct.
                data = Uri.parse("smstransmitter://sms-sent/$historyId/$partIndex")
                putExtra(EXTRA_HISTORY_ID, historyId)
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_SENDER_PHONE_NUMBER, senderPhoneNumber)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        }
    }
}

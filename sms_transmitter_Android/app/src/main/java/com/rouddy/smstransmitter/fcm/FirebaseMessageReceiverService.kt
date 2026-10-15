package com.rouddy.smstransmitter.fcm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.rouddy.smstransmitter.R
import com.rouddy.smstransmitter.data.SmsHistoryDatabase
import com.rouddy.smstransmitter.data.SmsLimitSettings
import com.rouddy.smstransmitter.sms.SendSmsResult
import com.rouddy.smstransmitter.sms.SmsResultReporter
import com.rouddy.smstransmitter.sms.SmsSentReceiver
import com.rouddy.smstransmitter.sms.SmsUsageCalculator
import io.reactivex.rxjava3.disposables.CompositeDisposable

class FirebaseMessageReceiverService : FirebaseMessagingService() {

    private val disposables = CompositeDisposable()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        SmsLimitSettings(applicationContext).initializePhoneNumbersIfNeeded(applicationContext)
        FcmTokenRegistrar(applicationContext, disposables).registerToken(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = FcmPayloadParser.parse(remoteMessage.data)
        if (payload == null) {
            Log.w(TAG, "Missing or empty sender/destination/message in FCM data payload.")
            return
        }

        val historyDb = SmsHistoryDatabase(applicationContext)
        try {
            // FCM delivers at least once, so the same request can arrive again.
            if (payload.requestId != null && historyDb.hasSmsHistoryForRequest(payload.requestId)) {
                Log.i(TAG, "Ignoring duplicate FCM message for requestId=${payload.requestId}")
                return
            }
            val failure = sendSmsSafely(payload, historyDb) ?: return
            SmsResultReporter.report(applicationContext, payload.requestId, payload.senderPhoneNumber, failure)
                ?.let { disposables.add(it) }
        } finally {
            historyDb.close()
        }
    }

    /**
     * Records the message in history and hands it to the radio. Returns null once handed off:
     * the radio reports a sent result per part, and [SmsSentReceiver] finalizes the history row
     * and reports to the server. Returns the failure if the message could not be handed off.
     */
    private fun sendSmsSafely(payload: FcmPayload, historyDb: SmsHistoryDatabase): SendSmsResult? {
        checkPreconditionError(historyDb, payload)?.let { error ->
            Log.w(TAG, error)
            historyDb.insertSmsHistory(
                requestId = payload.requestId,
                senderPhoneNumber = payload.senderPhoneNumber,
                phoneNumber = payload.phoneNumber,
                message = payload.message,
                smsSuccess = false,
                smsError = error
            )
            return SendSmsResult(success = false, error = error)
        }

        var historyId = -1L
        return runCatching {
            val smsManager = getSmsManager(payload.senderPhoneNumber)
            val messageParts = smsManager.divideMessage(payload.message)
            historyId = historyDb.insertSmsHistory(
                requestId = payload.requestId,
                senderPhoneNumber = payload.senderPhoneNumber,
                phoneNumber = payload.phoneNumber,
                message = payload.message,
                smsSuccess = false,
                smsError = null,
                pendingParts = messageParts.size
            )
            check(historyId != -1L) { "Failed to record SMS history." }

            val sentIntents = messageParts.indices.map { partIndex ->
                SmsSentReceiver.sentIntent(this, historyId, partIndex, payload.requestId, payload.senderPhoneNumber)
            }
            if (messageParts.size > 1) {
                smsManager.sendMultipartTextMessage(payload.phoneNumber, null, messageParts, ArrayList(sentIntents), null)
            } else {
                smsManager.sendTextMessage(payload.phoneNumber, null, payload.message, sentIntents.single(), null)
            }
            null
        }.getOrElse {
            Log.e(TAG, "Failed to send SMS.", it)
            val error = it.message ?: "Failed to send SMS."
            if (historyId != -1L) {
                historyDb.markSmsFailed(historyId, error)
            } else {
                historyDb.insertSmsHistory(
                    requestId = payload.requestId,
                    senderPhoneNumber = payload.senderPhoneNumber,
                    phoneNumber = payload.phoneNumber,
                    message = payload.message,
                    smsSuccess = false,
                    smsError = error
                )
            }
            SendSmsResult(success = false, error = error)
        }
    }

    private fun checkPreconditionError(historyDb: SmsHistoryDatabase, payload: FcmPayload): String? {
        if (payload.phoneNumber.isBlank()) return "Destination phone number is empty."
        if (!hasSmsPermission()) return "SEND_SMS permission not granted."
        return checkLimitReachedError(historyDb, payload.senderPhoneNumber)
    }

    private fun getSmsManager(senderPhoneNumber: String): SmsManager {
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalArgumentException("READ_PHONE_NUMBERS or READ_PHONE_STATE permission not granted.")
        }

        val activeInfoList: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList
        var matchedSubId: Int? = null

        val normalizedTarget = PhoneNumberUtils.normalizeNumber(senderPhoneNumber)

        if (activeInfoList != null) {
            for (info in activeInfoList) {
                val simPhoneNumber = subscriptionManager.getPhoneNumber(info.subscriptionId)

                if (simPhoneNumber.isNotBlank()) {
                    val normalizedSimNumber = PhoneNumberUtils.normalizeNumber(simPhoneNumber)
                    if (normalizedSimNumber == normalizedTarget || simPhoneNumber.contains(normalizedTarget)) {
                        matchedSubId = info.subscriptionId
                        break
                    }
                }
            }
        }

        if (matchedSubId != null) {
            return getSystemService(SmsManager::class.java).createForSubscriptionId(matchedSubId)
        } else {
            throw IllegalArgumentException("No matching SIM found for $senderPhoneNumber.")
        }
    }

    private fun checkLimitReachedError(historyDb: SmsHistoryDatabase, payloadSenderPhoneNumber: String?): String? {
        val senderPhoneNumber = payloadSenderPhoneNumber ?: return null
        val senderConfig = SmsLimitSettings(applicationContext).getConfig().phoneNumbers
            .firstOrNull { it.phoneNumber == senderPhoneNumber } ?: return null
        val now = System.currentTimeMillis()

        if (SmsUsageCalculator.isDailyLimitReached(historyDb, senderConfig, now)) {
            return getString(R.string.daily_limit_reached)
        }
        if (SmsUsageCalculator.isMonthlyLimitReached(historyDb, senderConfig, now)) {
            return getString(R.string.monthly_limit_reached)
        }

        return null
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "FirebaseSmsService"
    }
}

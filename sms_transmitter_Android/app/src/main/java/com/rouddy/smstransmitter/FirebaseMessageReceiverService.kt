package com.rouddy.smstransmitter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

class FirebaseMessageReceiverService : FirebaseMessagingService() {

    private val disposables = CompositeDisposable()

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val settings = SmsLimitSettings(applicationContext)
        settings.initializePhoneNumbersIfNeeded(applicationContext)
        val config = settings.getConfig()
        val serverUrl = config.serverUrl ?: return

        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
        val disposable = GraphQlClient("$serverUrl/").registerFcmToken(
            token = token,
            deviceInfo = deviceInfo,
            phoneNumbers = config.phoneNumbers,
        )
            .subscribeOn(Schedulers.io())
            .subscribe(
                { Log.i(TAG, "FCM token registered with server.") },
                { Log.e(TAG, "Failed to register FCM token with server.", it) },
            )
        disposables.add(disposable)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = FcmPayloadParser.parse(remoteMessage.data)
        if (payload == null) {
            Log.w(TAG, "Missing or empty sender/destination/message in FCM data payload.")
            return
        }

        val historyDb = SmsHistoryDatabase(applicationContext)
        try {
            val smsResult = sendSmsSafely(
                senderPhoneNumber = payload.senderPhoneNumber,
                destinationPhoneNumber = payload.phoneNumber,
                message = payload.message,
                historyDb = historyDb
            )
            historyDb.insertSmsHistory(
                senderPhoneNumber = payload.senderPhoneNumber,
                phoneNumber = payload.phoneNumber,
                message = payload.message,
                smsSuccess = smsResult.success,
                smsError = smsResult.error
            )
            reportSmsResultSafely(payload.requestId, smsResult)
        } finally {
            historyDb.close()
        }
    }

    private fun sendSmsSafely(
        senderPhoneNumber: String,
        destinationPhoneNumber: String,
        message: String,
        historyDb: SmsHistoryDatabase
    ): SendSmsResult {
        if (destinationPhoneNumber.isBlank()) {
            Log.w(TAG, "Destination phone number is not configured.")
            return SendSmsResult(success = false, error = "Destination phone number is empty.")
        }
        if (!hasSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission not granted.")
            return SendSmsResult(success = false, error = "SEND_SMS permission not granted.")
        }
        checkLimitReachedError(historyDb, senderPhoneNumber)?.let { limitError ->
            Log.w(TAG, limitError)
            return SendSmsResult(success = false, error = limitError)
        }

        return runCatching {
            // Kotlin 예시
            val smsManager = getSmsManager(senderPhoneNumber)
            val messageParts = smsManager.divideMessage(message)
            if (messageParts.size > 1) {
                smsManager.sendMultipartTextMessage(destinationPhoneNumber, null, messageParts, null, null)
            } else {
                smsManager.sendTextMessage(destinationPhoneNumber, null, message, null, null)
            }
            SendSmsResult(success = true, error = null)
        }.onFailure {
            Log.e(TAG, "Failed to send SMS.", it)
        }.getOrElse {
            SendSmsResult(success = false, error = it.message ?: "Failed to send SMS.")
        }
    }

    private fun getSmsManager(senderPhoneNumber: String): SmsManager {
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalArgumentException("READ_PHONE_NUMBERS permission not granted.")
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
        val settings = SmsLimitSettings(applicationContext).getConfig()
        val senderPhoneNumber = payloadSenderPhoneNumber ?: return null
        val senderConfig = settings.phoneNumbers.firstOrNull { it.phoneNumber == senderPhoneNumber } ?: return null
        val now = System.currentTimeMillis()

        senderConfig.dailyLimit?.let { dailyLimit ->
            val todaySentCount = historyDb.countSuccessfulSmsBetweenForSender(
                senderPhoneNumber,
                SmsLimitSettings.getStartOfTodayMillis(now),
                SmsLimitSettings.getStartOfNextDayMillis(now)
            )
            if (todaySentCount >= dailyLimit) {
                return getString(R.string.daily_limit_reached)
            }
        }

        senderConfig.monthlyLimit?.let { monthlyLimit ->
            val monthSentCount = historyDb.countSuccessfulSmsBetweenForSender(
                senderPhoneNumber,
                SmsLimitSettings.getStartOfMonthMillis(now),
                SmsLimitSettings.getStartOfNextMonthMillis(now)
            )
            if (monthSentCount >= monthlyLimit) {
                return getString(R.string.monthly_limit_reached)
            }
        }

        return null
    }

    private fun reportSmsResultSafely(requestId: String?, smsResult: SendSmsResult) {
        if (requestId.isNullOrBlank()) return
        val config = SmsLimitSettings(applicationContext).getConfig()
        val serverUrl = config.serverUrl ?: return

        val disposable = GraphQlClient("$serverUrl/").reportSmsResult(
            requestId = requestId,
            success = smsResult.success,
            error = smsResult.error,
        )
            .subscribeOn(Schedulers.io())
            .subscribe(
                { Log.i(TAG, "SMS result reported to server for requestId=$requestId success=${smsResult.success}") },
                { Log.e(TAG, "Failed to report SMS result to server for requestId=$requestId", it) },
            )
        disposables.add(disposable)
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

private data class SendSmsResult(
    val success: Boolean,
    val error: String?
)

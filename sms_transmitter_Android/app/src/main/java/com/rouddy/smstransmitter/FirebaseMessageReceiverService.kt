package com.rouddy.smstransmitter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
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
        initializePhoneNumbersIfNeeded(settings)
        val config = settings.getConfig()
        val serverUrl = config.serverUrl ?: return

        val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
        val disposable = GraphQlClient("$serverUrl/").registerFcmToken(
            token = token,
            deviceInfo = deviceInfo,
            phoneNumbers = config.phoneNumbers,
            dailyLimit = config.dailyLimit ?: GraphQlClient.LIMIT_UNLIMITED,
            monthlyLimit = config.monthlyLimit ?: GraphQlClient.LIMIT_UNLIMITED,
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
            Log.w(TAG, "Missing or empty 'phoneNumber'/'message' in FCM data payload.")
            return
        }

        val historyDb = SmsHistoryDatabase(applicationContext)
        try {
            val smsResult = sendSmsSafely(payload.phoneNumber, payload.message, historyDb)
            historyDb.insertSmsHistory(
                phoneNumber = payload.phoneNumber,
                message = payload.message,
                smsSuccess = smsResult.success,
                smsError = smsResult.error
            )
            val now = System.currentTimeMillis()
            val dailySentCount = historyDb.countSuccessfulSmsBetween(
                SmsLimitSettings.getStartOfTodayMillis(now),
                SmsLimitSettings.getStartOfNextDayMillis(now)
            )
            val monthlySentCount = historyDb.countSuccessfulSmsBetween(
                SmsLimitSettings.getStartOfMonthMillis(now),
                SmsLimitSettings.getStartOfNextMonthMillis(now)
            )
            reportSmsResultSafely(payload.requestId, smsResult, dailySentCount, monthlySentCount)
        } finally {
            historyDb.close()
        }
    }

    private fun sendSmsSafely(
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
        checkLimitReachedError(historyDb)?.let { limitError ->
            Log.w(TAG, limitError)
            return SendSmsResult(success = false, error = limitError)
        }

        return runCatching {
            val smsManager = SmsManager.getDefault()
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

    private fun checkLimitReachedError(historyDb: SmsHistoryDatabase): String? {
        val settings = SmsLimitSettings(applicationContext).getConfig()
        val now = System.currentTimeMillis()

        settings.dailyLimit?.let { dailyLimit ->
            val todaySentCount = historyDb.countSuccessfulSmsBetween(
                SmsLimitSettings.getStartOfTodayMillis(now),
                SmsLimitSettings.getStartOfNextDayMillis(now)
            )
            if (todaySentCount >= dailyLimit) {
                return getString(R.string.daily_limit_reached)
            }
        }

        settings.monthlyLimit?.let { monthlyLimit ->
            val monthSentCount = historyDb.countSuccessfulSmsBetween(
                SmsLimitSettings.getStartOfMonthMillis(now),
                SmsLimitSettings.getStartOfNextMonthMillis(now)
            )
            if (monthSentCount >= monthlyLimit) {
                return getString(R.string.monthly_limit_reached)
            }
        }

        return null
    }

    private fun reportSmsResultSafely(requestId: String?, smsResult: SendSmsResult, dailySentCount: Int, monthlySentCount: Int) {
        if (requestId.isNullOrBlank()) return
        val config = SmsLimitSettings(applicationContext).getConfig()
        val serverUrl = config.serverUrl ?: return

        val disposable = GraphQlClient("$serverUrl/").reportSmsResult(
            requestId = requestId,
            success = smsResult.success,
            error = smsResult.error,
            dailySentCount = dailySentCount,
            monthlySentCount = monthlySentCount,
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

    private fun initializePhoneNumbersIfNeeded(settings: SmsLimitSettings) {
        if (settings.isPhoneNumbersInitialized()) return

        val phoneNumbers = PhoneNumberResolver.getAvailablePhoneNumbers(applicationContext)
        settings.savePhoneNumbers(phoneNumbers)
        settings.resetLimitsToUnlimited()
        settings.markPhoneNumbersInitialized()
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

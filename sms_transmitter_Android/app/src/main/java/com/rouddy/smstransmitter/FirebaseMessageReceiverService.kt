package com.rouddy.smstransmitter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FirebaseMessageReceiverService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val config = SmsLimitSettings(applicationContext).getConfig()
        val serverUrl = config.serverUrl ?: return

        networkExecutor.execute {
            runCatching {
                val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
                GraphQlClient("$serverUrl/graphql").registerFcmToken(
                    token = token,
                    deviceInfo = deviceInfo,
                    dailyLimit = config.dailyLimit ?: LIMIT_UNLIMITED,
                    monthlyLimit = config.monthlyLimit ?: LIMIT_UNLIMITED,
                )
                Log.i(TAG, "FCM token registered with server.")
            }.onFailure {
                Log.e(TAG, "Failed to register FCM token with server.", it)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = FcmPayloadParser.parse(remoteMessage.data)
        if (payload == null) {
            Log.w(TAG, "Missing or empty 'phoneNumber'/'message' in FCM data payload.")
            return
        }

        val historyDb = SmsHistoryDatabase(applicationContext)
        val historyRowId = try {
            val smsResult = sendSmsSafely(payload.phoneNumber, payload.message, historyDb)
            val rowId = historyDb.insertSmsHistory(
                phoneNumber = payload.phoneNumber,
                message = payload.message,
                smsSuccess = smsResult.success,
                smsError = smsResult.error,
                callbackUrl = payload.callback
            )
            reportSmsResultSafely(payload.requestId, smsResult)
            rowId
        } finally {
            historyDb.close()
        }

        triggerCallbackSafely(payload.callback, historyRowId)
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

    private fun reportSmsResultSafely(requestId: String?, smsResult: SendSmsResult) {
        if (requestId.isNullOrBlank()) return
        val config = SmsLimitSettings(applicationContext).getConfig()
        val serverUrl = config.serverUrl ?: return

        networkExecutor.execute {
            runCatching {
                GraphQlClient("$serverUrl/graphql").reportSmsResult(
                    requestId = requestId,
                    success = smsResult.success,
                    error = smsResult.error,
                )
                Log.i(TAG, "SMS result reported to server for requestId=$requestId success=${smsResult.success}")
            }.onFailure {
                Log.e(TAG, "Failed to report SMS result to server for requestId=$requestId", it)
            }
        }
    }

    private fun triggerCallbackSafely(
        callbackUrl: String?,
        rowId: Long
    ) {
        if (callbackUrl.isNullOrBlank()) return
        if (!isSupportedHttpUrl(callbackUrl)) {
            Log.w(TAG, "Invalid callback URL: $callbackUrl")
            val historyDb = SmsHistoryDatabase(applicationContext)
            try {
                historyDb.updateCallbackResult(
                    rowId = rowId,
                    callbackSuccess = false,
                    callbackError = "Invalid callback URL."
                )
            } finally {
                historyDb.close()
            }
            return
        }

        callbackExecutor.execute {
            val historyDb = try {
                SmsHistoryDatabase(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open SMS history DB for callback logging.", e)
                return@execute
            }

            try {
                runCatching {
                    val endpoint = URL(callbackUrl)
                    val connection = endpoint.openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 5_000
                        connection.readTimeout = 5_000
                        connection.inputStream.buffered().use { it.readBytes() }
                    } finally {
                        connection.disconnect()
                    }
                    historyDb.updateCallbackResult(rowId, callbackSuccess = true, callbackError = null)
                }.onFailure {
                    Log.e(TAG, "Failed to call callback URL.", it)
                    historyDb.updateCallbackResult(
                        rowId = rowId,
                        callbackSuccess = false,
                        callbackError = it.message ?: "Failed to call callback URL."
                    )
                }
            } finally {
                historyDb.close()
            }
        }
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isSupportedHttpUrl(url: String): Boolean {
        return runCatching {
            val scheme = URI(url).scheme?.lowercase()
            scheme == "http" || scheme == "https"
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "FirebaseSmsService"
        // Sent to the server when no local limit is configured; effectively unlimited since
        // daily/monthly counters reset periodically and will never approach this value.
        private const val LIMIT_UNLIMITED = Int.MAX_VALUE
        private val networkExecutor: ExecutorService = Executors.newCachedThreadPool()
        private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool()
    }
}

private data class SendSmsResult(
    val success: Boolean,
    val error: String?
)

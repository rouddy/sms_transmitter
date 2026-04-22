package com.rouddy.smstransmitter

import android.Manifest
import android.content.pm.PackageManager
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
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val payload = FcmPayloadParser.parse(remoteMessage.data)
        if (payload == null) {
            Log.w(TAG, "Missing or empty 'phoneNumber'/'message' in FCM data payload.")
            return
        }

        val smsResult = sendSmsSafely(payload.phoneNumber, payload.message)
        val historyRowId = SmsHistoryDatabase(applicationContext).run {
            val rowId = insertSmsHistory(
                phoneNumber = payload.phoneNumber,
                message = payload.message,
                smsSuccess = smsResult.success,
                smsError = smsResult.error,
                callbackUrl = payload.callback
            )
            close()
            rowId
        }

        triggerCallbackSafely(payload.callback, historyRowId)
    }

    private fun sendSmsSafely(destinationPhoneNumber: String, message: String): SendSmsResult {
        if (destinationPhoneNumber.isBlank()) {
            Log.w(TAG, "Destination phone number is not configured.")
            return SendSmsResult(success = false, error = "Destination phone number is empty.")
        }
        if (!hasSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission not granted.")
            return SendSmsResult(success = false, error = "SEND_SMS permission not granted.")
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

    private fun triggerCallbackSafely(
        callbackUrl: String?,
        rowId: Long
    ) {
        if (callbackUrl.isNullOrBlank()) return
        if (!isSupportedHttpUrl(callbackUrl)) {
            Log.w(TAG, "Invalid callback URL: $callbackUrl")
            SmsHistoryDatabase(applicationContext).run {
                updateCallbackResult(
                    rowId = rowId,
                    callbackSuccess = false,
                    callbackError = "Invalid callback URL."
                )
                close()
            }
            return
        }

        callbackExecutor.execute {
            val smsHistoryDb = SmsHistoryDatabase(applicationContext)
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
                smsHistoryDb.updateCallbackResult(rowId, callbackSuccess = true, callbackError = null)
            }.onFailure {
                Log.e(TAG, "Failed to call callback URL.", it)
                smsHistoryDb.updateCallbackResult(
                    rowId = rowId,
                    callbackSuccess = false,
                    callbackError = it.message ?: "Failed to call callback URL."
                )
            }.also {
                smsHistoryDb.close()
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
        private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool()
    }
}

private data class SendSmsResult(
    val success: Boolean,
    val error: String?
)

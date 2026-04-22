package com.rouddy.smstransmitter

import android.Manifest
import android.content.Context
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
            Log.w(TAG, "Missing or empty 'message' in FCM data payload.")
            return
        }

        val phoneNumber = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(MainActivity.KEY_PHONE_NUMBER, "")
            .orEmpty()
            .trim()

        sendSmsSafely(phoneNumber, payload.message)
        triggerCallbackSafely(payload.callback)
    }

    private fun sendSmsSafely(destinationPhoneNumber: String, message: String): Boolean {
        if (destinationPhoneNumber.isBlank()) {
            Log.w(TAG, "Destination phone number is not configured.")
            return false
        }
        if (!hasSmsPermission()) {
            Log.w(TAG, "SEND_SMS permission not granted.")
            return false
        }

        return runCatching {
            val smsManager = SmsManager.getDefault()
            val messageParts = smsManager.divideMessage(message)
            if (messageParts.size > 1) {
                smsManager.sendMultipartTextMessage(destinationPhoneNumber, null, messageParts, null, null)
            } else {
                smsManager.sendTextMessage(destinationPhoneNumber, null, message, null, null)
            }
            true
        }.onFailure {
            Log.e(TAG, "Failed to send SMS.", it)
        }.getOrDefault(false)
    }

    private fun triggerCallbackSafely(callbackUrl: String) {
        if (callbackUrl.isBlank()) return
        if (!isSupportedHttpUrl(callbackUrl)) {
            Log.w(TAG, "Invalid callback URL: $callbackUrl")
            return
        }

        callbackExecutor.execute {
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
            }.onFailure {
                Log.e(TAG, "Failed to call callback URL.", it)
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

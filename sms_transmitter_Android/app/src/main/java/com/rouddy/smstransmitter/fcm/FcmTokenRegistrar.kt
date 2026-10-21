package com.rouddy.smstransmitter.fcm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.rouddy.smstransmitter.data.SmsLimitSettings
import com.rouddy.smstransmitter.network.GraphQlClient
import com.rouddy.smstransmitter.sms.SmsUsageCalculator
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

/**
 * Registers this device's FCM token with the server. Centralizes the registration
 * flow that used to be duplicated across MainActivity, SettingsActivity and
 * FirebaseMessageReceiverService.
 */
class FcmTokenRegistrar(
    private val context: Context,
    private val disposables: CompositeDisposable,
    private val onError: (Throwable) -> Unit = { Log.e(TAG, "Failed to register FCM token with server.", it) },
) {
    /**
     * Resolves the current FCM token and registers it. Call [registerToken] instead
     * when the token is already known (e.g. from the Firebase messaging callback).
     */
    fun register() {
        fetchToken { token -> registerToken(token) }
    }

    /**
     * Like [register], but if the configured server URL differs from [previousServerUrl],
     * first deletes the token from the previous server. Deletion failures are ignored.
     */
    fun registerAfterServerChange(previousServerUrl: String?) {
        val serverUrl = SmsLimitSettings(context).getConfig().serverUrl ?: return
        fetchToken { token ->
            if (previousServerUrl != null && previousServerUrl != serverUrl) {
                deleteToken(previousServerUrl, token)
            }
            registerToken(token)
        }
    }

    /** Resolves the current FCM token and passes it to [onResolved]. */
    fun fetchToken(onResolved: (String) -> Unit) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Failed to get FCM token.", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result ?: return@addOnCompleteListener
            onResolved(token)
        }
    }

    /**
     * Registers a known FCM token along with the configured phone numbers and their confirmed
     * sent counts, which may include messages relayed for another server.
     */
    fun registerToken(token: String) {
        val config = SmsLimitSettings(context).getConfig()
        val serverUrl = config.serverUrl ?: return

        val disposable = GraphQlClient("$serverUrl/").registerFcmToken(
            token = token,
            deviceInfo = deviceInfo,
            phoneNumbers = config.phoneNumbers,
            sentCounts = SmsUsageCalculator.confirmedSentCounts(context, config.phoneNumbers.map { it.phoneNumber }),
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { Log.i(TAG, "FCM token registered with server.") },
                onError,
            )
        disposables.add(disposable)
    }

    private fun deleteToken(serverUrl: String, token: String) {
        val disposable = GraphQlClient("$serverUrl/").deleteFcmTokenByToken(token)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { Log.i(TAG, "FCM token deleted from previous server: $serverUrl") },
                { Log.w(TAG, "Failed to delete FCM token from previous server (ignored): $serverUrl", it) },
            )
        disposables.add(disposable)
    }

    private val deviceInfo: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        private const val TAG = "FcmTokenRegistrar"
    }
}
package com.rouddy.smstransmitter

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
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

    /** Registers a known FCM token along with the configured phone numbers. */
    fun registerToken(token: String) {
        val config = SmsLimitSettings(context).getConfig()
        val serverUrl = config.serverUrl ?: return

        val disposable = GraphQlClient("$serverUrl/").registerFcmToken(
            token = token,
            deviceInfo = deviceInfo,
            phoneNumbers = config.phoneNumbers,
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { Log.i(TAG, "FCM token registered with server.") },
                onError,
            )
        disposables.add(disposable)
    }

    private val deviceInfo: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        private const val TAG = "FcmTokenRegistrar"
    }
}
package com.rouddy.smstransmitter

import android.content.Context
import android.util.Log
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers

/** Reports the final result of an SMS request back to the server. */
object SmsResultReporter {
    private const val TAG = "SmsResultReporter"

    /**
     * Reports [result] for [requestId] with the confirmed sent counts of [senderPhoneNumber].
     * [onComplete] runs once the report finishes, fails or is skipped (no request ID or server
     * URL), so callers can release a pending broadcast.
     */
    fun report(
        context: Context,
        requestId: String?,
        senderPhoneNumber: String?,
        result: SendSmsResult,
        onComplete: () -> Unit = {},
    ): Disposable? {
        if (requestId.isNullOrBlank()) {
            onComplete()
            return null
        }
        val serverUrl = SmsLimitSettings(context).getConfig().serverUrl ?: run {
            onComplete()
            return null
        }

        val sentCounts = senderPhoneNumber?.let {
            SmsUsageCalculator.confirmedSentCounts(context, listOf(it)).getValue(it)
        }

        return GraphQlClient("$serverUrl/").reportSmsResult(
            requestId = requestId,
            success = result.success,
            error = result.error,
            sentCounts = sentCounts,
        )
            .subscribeOn(Schedulers.io())
            .doFinally(onComplete)
            .subscribe(
                { Log.i(TAG, "SMS result reported to server for requestId=$requestId success=${result.success}") },
                { Log.e(TAG, "Failed to report SMS result to server for requestId=$requestId", it) },
            )
    }
}

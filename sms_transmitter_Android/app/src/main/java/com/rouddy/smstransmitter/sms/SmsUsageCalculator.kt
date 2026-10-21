package com.rouddy.smstransmitter.sms

import android.content.Context
import com.rouddy.smstransmitter.R
import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import com.rouddy.smstransmitter.data.SmsHistoryDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Confirmed sent counts for one sender, sent to the server so it can sync its counts.
 * [daily] is for [countedOn] (device-local date) and [monthly] for its month.
 */
data class SentCounts(
    val countedOn: LocalDate,
    val daily: Int,
    val monthly: Int,
)

/**
 * Computes per-sender SMS usage for the current day/month. Centralizes the
 * count/format/limit-check logic that used to be duplicated across MainActivity,
 * UsageOverlayService and FirebaseMessageReceiverService.
 */
object SmsUsageCalculator {
    /** Counts successful and in-flight messages, for local limit checks and usage display. */
    fun sentToday(db: SmsHistoryDatabase, senderPhoneNumber: String, nowMillis: Long): Int =
        db.countSmsBetweenForSender(
            senderPhoneNumber,
            startOfTodayMillis(nowMillis),
            startOfNextDayMillis(nowMillis),
            includePending = true,
        )

    /** Counts successful and in-flight messages, for local limit checks and usage display. */
    fun sentThisMonth(db: SmsHistoryDatabase, senderPhoneNumber: String, nowMillis: Long): Int =
        db.countSmsBetweenForSender(
            senderPhoneNumber,
            startOfMonthMillis(nowMillis),
            startOfNextMonthMillis(nowMillis),
            includePending = true,
        )

    /**
     * Counts only messages confirmed as sent. In-flight messages may still fail, and the
     * server never lowers counts within a period, so they are left out of what it receives.
     */
    fun confirmedSentCounts(db: SmsHistoryDatabase, senderPhoneNumber: String, nowMillis: Long): SentCounts =
        SentCounts(
            countedOn = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate(),
            daily = db.countSmsBetweenForSender(
                senderPhoneNumber,
                startOfTodayMillis(nowMillis),
                startOfNextDayMillis(nowMillis),
                includePending = false,
            ),
            monthly = db.countSmsBetweenForSender(
                senderPhoneNumber,
                startOfMonthMillis(nowMillis),
                startOfNextMonthMillis(nowMillis),
                includePending = false,
            ),
        )

    fun confirmedSentCounts(
        context: Context,
        senderPhoneNumbers: List<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, SentCounts> {
        val db = SmsHistoryDatabase(context)
        try {
            return senderPhoneNumbers.associateWith { confirmedSentCounts(db, it, nowMillis) }
        } finally {
            db.close()
        }
    }

    fun isDailyLimitReached(db: SmsHistoryDatabase, config: PhoneNumberLimitConfig, nowMillis: Long): Boolean {
        val limit = config.dailyLimit ?: return false
        return sentToday(db, config.phoneNumber, nowMillis) >= limit
    }

    fun isMonthlyLimitReached(db: SmsHistoryDatabase, config: PhoneNumberLimitConfig, nowMillis: Long): Boolean {
        val limit = config.monthlyLimit ?: return false
        return sentThisMonth(db, config.phoneNumber, nowMillis) >= limit
    }

    fun formatUsageLine(context: Context, periodLabel: String, limit: Int?, sentCount: Int): String =
        if (limit != null && limit > 0) {
            context.getString(
                R.string.sms_usage_with_limit,
                periodLabel,
                sentCount,
                limit,
                (limit - sentCount).coerceAtLeast(0),
            )
        } else {
            context.getString(R.string.sms_usage_unlimited, periodLabel, sentCount)
        }

    private fun startOfTodayMillis(nowMillis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun startOfNextDayMillis(nowMillis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = startOfTodayMillis(nowMillis)
            add(Calendar.DAY_OF_MONTH, 1)
        }
        return calendar.timeInMillis
    }

    private fun startOfMonthMillis(nowMillis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun startOfNextMonthMillis(nowMillis: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = startOfMonthMillis(nowMillis)
            add(Calendar.MONTH, 1)
        }
        return calendar.timeInMillis
    }
}
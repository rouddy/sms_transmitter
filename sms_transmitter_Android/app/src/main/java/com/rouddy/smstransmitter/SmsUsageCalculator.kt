package com.rouddy.smstransmitter

import android.content.Context
import java.util.Calendar

/**
 * Computes per-sender SMS usage for the current day/month. Centralizes the
 * count/format/limit-check logic that used to be duplicated across MainActivity,
 * UsageOverlayService and FirebaseMessageReceiverService.
 */
object SmsUsageCalculator {
    fun sentToday(db: SmsHistoryDatabase, senderPhoneNumber: String, nowMillis: Long): Int =
        db.countSuccessfulSmsBetweenForSender(
            senderPhoneNumber,
            startOfTodayMillis(nowMillis),
            startOfNextDayMillis(nowMillis),
        )

    fun sentThisMonth(db: SmsHistoryDatabase, senderPhoneNumber: String, nowMillis: Long): Int =
        db.countSuccessfulSmsBetweenForSender(
            senderPhoneNumber,
            startOfMonthMillis(nowMillis),
            startOfNextMonthMillis(nowMillis),
        )

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
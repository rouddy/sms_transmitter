package com.rouddy.smstransmitter

import android.content.Context
import java.util.Calendar

data class SmsLimitConfig(
    val dailyLimit: Int?,
    val monthlyLimit: Int?
)

class SmsLimitSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getConfig(): SmsLimitConfig {
        return SmsLimitConfig(
            dailyLimit = readLimit(KEY_DAILY_LIMIT),
            monthlyLimit = readLimit(KEY_MONTHLY_LIMIT)
        )
    }

    fun saveConfig(dailyLimit: Int?, monthlyLimit: Int?) {
        preferences.edit()
            .putString(KEY_DAILY_LIMIT, dailyLimit?.toString())
            .putString(KEY_MONTHLY_LIMIT, monthlyLimit?.toString())
            .apply()
    }

    private fun readLimit(key: String): Int? {
        val value = preferences.getString(key, null)?.trim()
        if (value.isNullOrEmpty()) return null
        return value.toIntOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private const val PREF_NAME = "sms_limits"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_MONTHLY_LIMIT = "monthly_limit"

        fun getStartOfTodayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return calendar.timeInMillis
        }

        fun getStartOfNextDayMillis(nowMillis: Long = System.currentTimeMillis()): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = getStartOfTodayMillis(nowMillis)
                add(Calendar.DAY_OF_MONTH, 1)
            }
            return calendar.timeInMillis
        }

        fun getStartOfMonthMillis(nowMillis: Long = System.currentTimeMillis()): Long {
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

        fun getStartOfNextMonthMillis(nowMillis: Long = System.currentTimeMillis()): Long {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = getStartOfMonthMillis(nowMillis)
                add(Calendar.MONTH, 1)
            }
            return calendar.timeInMillis
        }
    }
}

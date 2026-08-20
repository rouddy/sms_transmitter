package com.rouddy.smstransmitter

import android.content.Context
import com.rouddy.smstransmitter.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class PhoneNumberLimitConfig(
    val phoneNumber: String,
    val dailyLimit: Int?,
    val monthlyLimit: Int?,
)

data class SmsLimitConfig(
    val phoneNumbers: List<PhoneNumberLimitConfig>,
    val overlayEnabled: Boolean,
    val serverUrl: String?,
)

class SmsLimitSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun initializePhoneNumbersIfNeeded(context: Context): Boolean {
        if (isPhoneNumbersInitialized()) return true
        if (!PhoneNumberResolver.hasPhoneNumberPermission(context)) return false

        val detectedPhoneNumbers = PhoneNumberResolver.getAvailablePhoneNumbers(context)
        val mergedConfigs = mergePhoneNumbers(getConfig().phoneNumbers, detectedPhoneNumbers)
        savePhoneNumberConfigs(mergedConfigs)
        markPhoneNumbersInitialized()
        return true
    }

    fun getConfig(): SmsLimitConfig {
        val phoneNumbers = readPhoneNumberConfigs().ifEmpty {
            readLegacyPhoneNumbers().map { phoneNumber ->
                PhoneNumberLimitConfig(
                    phoneNumber = phoneNumber,
                    dailyLimit = readLegacyLimit(KEY_DAILY_LIMIT),
                    monthlyLimit = readLegacyLimit(KEY_MONTHLY_LIMIT),
                )
            }
        }
        return SmsLimitConfig(
            phoneNumbers = phoneNumbers,
            overlayEnabled = preferences.getBoolean(KEY_OVERLAY_ENABLED, false),
            serverUrl = preferences.getString(KEY_SERVER_URL, null)?.trim()?.takeIf { it.isNotBlank() }
                ?: "${BuildConfig.BASE_URL}:${BuildConfig.PORT}",
        )
    }

    fun savePhoneNumberConfigs(phoneNumbers: List<PhoneNumberLimitConfig>) {
        val normalizedPhoneNumbers = phoneNumbers
            .mapNotNull { config ->
                PhoneNumberResolver.normalizePhoneNumber(config.phoneNumber)?.let { phoneNumber ->
                    PhoneNumberLimitConfig(
                        phoneNumber = phoneNumber,
                        dailyLimit = config.dailyLimit?.takeIf { it > 0 },
                        monthlyLimit = config.monthlyLimit?.takeIf { it > 0 },
                    )
                }
            }
            .distinctBy { it.phoneNumber }
            .sortedBy { it.phoneNumber }

        val jsonArray = JSONArray()
        normalizedPhoneNumbers.forEach { config ->
            jsonArray.put(
                JSONObject().apply {
                    put("phoneNumber", config.phoneNumber)
                    if (config.dailyLimit != null) put("dailyLimit", config.dailyLimit)
                    if (config.monthlyLimit != null) put("monthlyLimit", config.monthlyLimit)
                }
            )
        }

        preferences.edit()
            .putString(KEY_PHONE_NUMBER_CONFIGS, jsonArray.toString())
            .putString(
                KEY_PHONE_NUMBERS,
                normalizedPhoneNumbers.joinToString(PHONE_NUMBER_SEPARATOR.toString()) { it.phoneNumber }
            )
            .remove(KEY_DAILY_LIMIT)
            .remove(KEY_MONTHLY_LIMIT)
            .apply()
    }

    fun isPhoneNumbersInitialized(): Boolean {
        return preferences.getBoolean(KEY_PHONE_NUMBERS_INITIALIZED, false)
    }

    fun markPhoneNumbersInitialized() {
        preferences.edit()
            .putBoolean(KEY_PHONE_NUMBERS_INITIALIZED, true)
            .apply()
    }

    fun saveServerUrl(url: String?) {
        preferences.edit()
            .putString(KEY_SERVER_URL, url)
            .apply()
    }

    fun removeServerUrl() {
        preferences.edit()
            .remove(KEY_SERVER_URL)
            .apply()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_OVERLAY_ENABLED, enabled)
            .apply()
    }

    fun saveOverlayPosition(x: Int, y: Int) {
        preferences.edit()
            .putInt(KEY_OVERLAY_POS_X, x)
            .putInt(KEY_OVERLAY_POS_Y, y)
            .apply()
    }

    fun getOverlayPosition(): Pair<Int, Int>? {
        if (!preferences.contains(KEY_OVERLAY_POS_X) || !preferences.contains(KEY_OVERLAY_POS_Y)) {
            return null
        }
        return preferences.getInt(KEY_OVERLAY_POS_X, 0) to preferences.getInt(KEY_OVERLAY_POS_Y, 0)
    }

    private fun mergePhoneNumbers(
        existingConfigs: List<PhoneNumberLimitConfig>,
        phoneNumbers: List<String>
    ): List<PhoneNumberLimitConfig> {
        val existingByPhoneNumber = existingConfigs.associateBy { it.phoneNumber }
        return phoneNumbers
            .mapNotNull { PhoneNumberResolver.normalizePhoneNumber(it) }
            .distinct()
            .sorted()
            .map { phoneNumber ->
                existingByPhoneNumber[phoneNumber]
                    ?: PhoneNumberLimitConfig(phoneNumber = phoneNumber, dailyLimit = null, monthlyLimit = null)
            }
    }

    private fun readPhoneNumberConfigs(): List<PhoneNumberLimitConfig> {
        val rawJson = preferences.getString(KEY_PHONE_NUMBER_CONFIGS, null)?.trim().orEmpty()
        if (rawJson.isBlank()) return emptyList()

        return runCatching {
            val jsonArray = JSONArray(rawJson)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val phoneNumber = PhoneNumberResolver.normalizePhoneNumber(item.optString("phoneNumber"))
                        ?: continue
                    val dailyLimit = item.optInt("dailyLimit").takeIf { item.has("dailyLimit") && it > 0 }
                    val monthlyLimit = item.optInt("monthlyLimit").takeIf { item.has("monthlyLimit") && it > 0 }
                    add(
                        PhoneNumberLimitConfig(
                            phoneNumber = phoneNumber,
                            dailyLimit = dailyLimit,
                            monthlyLimit = monthlyLimit,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readLegacyPhoneNumbers(): List<String> {
        val value = preferences.getString(KEY_PHONE_NUMBERS, null).orEmpty()
        if (value.isBlank()) return emptyList()
        return value.split(PHONE_NUMBER_SEPARATOR)
            .mapNotNull { PhoneNumberResolver.normalizePhoneNumber(it) }
            .distinct()
    }

    private fun readLegacyLimit(key: String): Int? {
        val value = preferences.getString(key, null)?.trim()
        if (value.isNullOrEmpty()) return null
        return value.toIntOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private const val PREF_NAME = "sms_limits"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_MONTHLY_LIMIT = "monthly_limit"
        private const val KEY_PHONE_NUMBERS = "phone_numbers"
        private const val KEY_PHONE_NUMBER_CONFIGS = "phone_number_configs"
        private const val KEY_PHONE_NUMBERS_INITIALIZED = "phone_numbers_initialized"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_POS_X = "overlay_pos_x"
        private const val KEY_OVERLAY_POS_Y = "overlay_pos_y"
        private const val PHONE_NUMBER_SEPARATOR = '\n'

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

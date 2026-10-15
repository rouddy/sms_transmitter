package com.rouddy.smstransmitter.data

import android.content.Context
import com.rouddy.smstransmitter.BuildConfig
import com.rouddy.smstransmitter.sms.PhoneNumberResolver
import org.json.JSONArray
import org.json.JSONObject

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

    init {
        removeLegacyKeys()
    }

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
        return SmsLimitConfig(
            phoneNumbers = readPhoneNumberConfigs(),
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

    private fun removeLegacyKeys() {
        val legacyKeys = LEGACY_KEYS.filter { preferences.contains(it) }
        if (legacyKeys.isEmpty()) return

        preferences.edit().apply {
            legacyKeys.forEach { remove(it) }
        }.apply()
    }

    companion object {
        private const val PREF_NAME = "sms_limits"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PHONE_NUMBER_CONFIGS = "phone_number_configs"
        private const val KEY_PHONE_NUMBERS_INITIALIZED = "phone_numbers_initialized"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_OVERLAY_POS_X = "overlay_pos_x"
        private const val KEY_OVERLAY_POS_Y = "overlay_pos_y"

        // 단일 발신번호·단일 한도 구조의 키. 0.2.0부터 KEY_PHONE_NUMBER_CONFIGS가 유일한 소스이며,
        // 0.2.0 이전 출시본에는 phone_numbers 키가 없어 이 값들로 번호별 설정을 복원할 수 없다.
        private val LEGACY_KEYS = listOf("daily_limit", "monthly_limit", "phone_numbers")
    }
}

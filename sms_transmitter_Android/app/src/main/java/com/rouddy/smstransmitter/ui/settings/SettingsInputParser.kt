package com.rouddy.smstransmitter.ui.settings

import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import com.rouddy.smstransmitter.sms.PhoneNumberResolver

data class PhoneNumberLimitDraft(
    val phoneNumber: String,
    val dailyLimit: String,
    val monthlyLimit: String,
)

sealed interface LimitInput {
    data object Empty : LimitInput
    data object Invalid : LimitInput
    data class Value(val limit: Int) : LimitInput
}

/** Parses raw settings form text. Free of Android views so it can be unit-tested. */
object SettingsInputParser {
    private val PHONE_NUMBER_DELIMITER_REGEX = "[,\\n\\r]+".toRegex()
    private val URL_PATTERN = "^(https?://[^/]*)/?$".toRegex()

    fun parsePhoneNumbers(raw: String?): List<String> {
        return raw.orEmpty()
            .split(PHONE_NUMBER_DELIMITER_REGEX)
            .mapNotNull { PhoneNumberResolver.normalizePhoneNumber(it) }
            .distinct()
    }

    fun parseLimit(raw: String?): LimitInput {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return LimitInput.Empty
        val parsed = value.toIntOrNull() ?: return LimitInput.Invalid
        return if (parsed > 0) LimitInput.Value(parsed) else LimitInput.Invalid
    }

    /** Returns null if any limit is not a positive integer. Empty limits mean "no limit". */
    fun toPhoneNumberConfigs(drafts: List<PhoneNumberLimitDraft>): List<PhoneNumberLimitConfig>? {
        return drafts.map { draft ->
            val dailyLimit = parseLimit(draft.dailyLimit)
            val monthlyLimit = parseLimit(draft.monthlyLimit)
            if (dailyLimit == LimitInput.Invalid || monthlyLimit == LimitInput.Invalid) return null
            PhoneNumberLimitConfig(
                phoneNumber = draft.phoneNumber,
                dailyLimit = (dailyLimit as? LimitInput.Value)?.limit,
                monthlyLimit = (monthlyLimit as? LimitInput.Value)?.limit,
            )
        }
    }

    /**
     * Strips a trailing slash from an `http(s)://host[:port]` URL. Returns null for blank
     * or malformed input, which callers treat as "clear the saved URL".
     */
    fun parseServerUrl(raw: String?): String? {
        return URL_PATTERN.find(raw?.trim().orEmpty())?.groupValues?.get(1)
    }
}

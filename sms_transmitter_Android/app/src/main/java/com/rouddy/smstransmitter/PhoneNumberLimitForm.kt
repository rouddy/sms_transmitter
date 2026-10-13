package com.rouddy.smstransmitter

import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Builds one daily/monthly limit input row per phone number inside [container]. */
class PhoneNumberLimitForm(private val container: LinearLayout) {
    private val context = container.context
    private val rows = linkedMapOf<String, PhoneNumberLimitRow>()

    /**
     * Rebuilds the rows for [phoneNumbers]. Text typed into existing rows is kept;
     * new rows are prefilled from [savedConfigs].
     */
    fun sync(
        phoneNumbers: List<String>,
        savedConfigs: Map<String, PhoneNumberLimitConfig> = emptyMap()
    ) {
        val currentDrafts = rows.mapValues { (phoneNumber, row) -> row.toDraft(phoneNumber) }

        container.removeAllViews()
        rows.clear()

        phoneNumbers.forEach { phoneNumber ->
            val savedConfig = savedConfigs[phoneNumber]
            val draft = currentDrafts[phoneNumber]
            val row = createRow(
                phoneNumber = phoneNumber,
                dailyLimit = draft?.dailyLimit ?: savedConfig?.dailyLimit?.toString().orEmpty(),
                monthlyLimit = draft?.monthlyLimit ?: savedConfig?.monthlyLimit?.toString().orEmpty(),
            )
            container.addView(row.container)
            rows[phoneNumber] = row
        }

        if (phoneNumbers.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = context.getString(R.string.no_phone_numbers_configured)
                }
            )
        }
    }

    /** Returns the configs for [phoneNumbers], or null if any limit input is invalid. */
    fun collect(phoneNumbers: List<String>): List<PhoneNumberLimitConfig>? {
        val drafts = phoneNumbers.mapNotNull { phoneNumber -> rows[phoneNumber]?.toDraft(phoneNumber) }
        return SettingsInputParser.toPhoneNumberConfigs(drafts)
    }

    private fun createRow(
        phoneNumber: String,
        dailyLimit: String,
        monthlyLimit: String
    ): PhoneNumberLimitRow {
        val rowContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        val titleView = TextView(context).apply {
            text = phoneNumber
        }
        val limitRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val dailyLimitInput = EditText(context).apply {
            hint = context.getString(R.string.daily_limit_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(dailyLimit)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val monthlyLimitInput = EditText(context).apply {
            hint = context.getString(R.string.monthly_limit_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(monthlyLimit)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        limitRow.addView(dailyLimitInput)
        limitRow.addView(monthlyLimitInput)
        rowContainer.addView(titleView)
        rowContainer.addView(limitRow)
        return PhoneNumberLimitRow(rowContainer, dailyLimitInput, monthlyLimitInput)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

private data class PhoneNumberLimitRow(
    val container: LinearLayout,
    val dailyLimitInput: EditText,
    val monthlyLimitInput: EditText,
) {
    fun toDraft(phoneNumber: String) = PhoneNumberLimitDraft(
        phoneNumber = phoneNumber,
        dailyLimit = dailyLimitInput.text?.toString().orEmpty(),
        monthlyLimit = monthlyLimitInput.text?.toString().orEmpty(),
    )
}

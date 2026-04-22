package com.rouddy.smstransmitter

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var dailyLimitInput: EditText
    private lateinit var monthlyLimitInput: EditText
    private lateinit var saveButton: Button
    private lateinit var smsLimitSettings: SmsLimitSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        smsLimitSettings = SmsLimitSettings(this)
        dailyLimitInput = findViewById(R.id.input_daily_limit)
        monthlyLimitInput = findViewById(R.id.input_monthly_limit)
        saveButton = findViewById(R.id.button_save_limits)

        bindCurrentValues()
        saveButton.setOnClickListener { saveValues() }
    }

    private fun bindCurrentValues() {
        val config = smsLimitSettings.getConfig()
        dailyLimitInput.setText(config.dailyLimit?.toString().orEmpty())
        monthlyLimitInput.setText(config.monthlyLimit?.toString().orEmpty())
    }

    private fun saveValues() {
        val dailyLimit = parseLimitInput(dailyLimitInput.text?.toString())
        val monthlyLimit = parseLimitInput(monthlyLimitInput.text?.toString())
        if (dailyLimit == INVALID_LIMIT || monthlyLimit == INVALID_LIMIT) {
            Toast.makeText(this, R.string.invalid_limit_input, Toast.LENGTH_LONG).show()
            return
        }

        smsLimitSettings.saveConfig(
            dailyLimit = dailyLimit.takeIf { it != EMPTY_LIMIT },
            monthlyLimit = monthlyLimit.takeIf { it != EMPTY_LIMIT }
        )
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun parseLimitInput(raw: String?): Int {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return EMPTY_LIMIT
        val parsed = value.toIntOrNull() ?: return INVALID_LIMIT
        return if (parsed > 0) parsed else INVALID_LIMIT
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EMPTY_LIMIT = -1
        private const val INVALID_LIMIT = -2
    }
}

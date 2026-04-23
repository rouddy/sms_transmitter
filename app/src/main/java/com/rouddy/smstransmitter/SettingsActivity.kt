package com.rouddy.smstransmitter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {
    private lateinit var dailyLimitInput: EditText
    private lateinit var monthlyLimitInput: EditText
    private lateinit var saveButton: Button
    private lateinit var overlaySwitch: SwitchMaterial
    private lateinit var smsLimitSettings: SmsLimitSettings
    private var bindingOverlaySwitch = false
    private var pendingOverlayEnable = false

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingOverlayEnable && hasOverlayPermission()) {
                setOverlaySwitchChecked(true)
                applyOverlayState(enabled = true)
            } else if (pendingOverlayEnable) {
                setOverlaySwitchChecked(false)
                applyOverlayState(enabled = false)
                Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            }
            pendingOverlayEnable = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        smsLimitSettings = SmsLimitSettings(this)
        dailyLimitInput = findViewById(R.id.input_daily_limit)
        monthlyLimitInput = findViewById(R.id.input_monthly_limit)
        saveButton = findViewById(R.id.button_save_limits)
        overlaySwitch = findViewById(R.id.switch_overlay_ui)

        bindCurrentValues()
        saveButton.setOnClickListener { saveValues() }
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingOverlaySwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                val dailyLimit = parseLimitInput(dailyLimitInput.text?.toString())
                val monthlyLimit = parseLimitInput(monthlyLimitInput.text?.toString())
                if (!canEnableOverlayWithLimits(dailyLimit, monthlyLimit)) {
                    Toast.makeText(this, R.string.overlay_requires_limits, Toast.LENGTH_LONG).show()
                    setOverlaySwitchChecked(false)
                    return@setOnCheckedChangeListener
                }
                if (!hasOverlayPermission()) {
                    pendingOverlayEnable = true
                    requestOverlayPermission()
                    return@setOnCheckedChangeListener
                }
                applyOverlayState(enabled = true)
            } else {
                pendingOverlayEnable = false
                applyOverlayState(enabled = false)
            }
        }
    }

    private fun bindCurrentValues() {
        val config = smsLimitSettings.getConfig()
        dailyLimitInput.setText(config.dailyLimit?.toString().orEmpty())
        monthlyLimitInput.setText(config.monthlyLimit?.toString().orEmpty())
        setOverlaySwitchChecked(config.overlayEnabled)
    }

    private fun saveValues() {
        val dailyLimit = parseLimitInput(dailyLimitInput.text?.toString())
        val monthlyLimit = parseLimitInput(monthlyLimitInput.text?.toString())
        if (dailyLimit == INVALID_LIMIT || monthlyLimit == INVALID_LIMIT) {
            Toast.makeText(this, R.string.invalid_limit_input, Toast.LENGTH_LONG).show()
            return
        }

        val canEnableOverlayWithLimits = canEnableOverlayWithLimits(dailyLimit, monthlyLimit)
        val requestedOverlayEnabled = overlaySwitch.isChecked
        val shouldEnableOverlay = requestedOverlayEnabled && canEnableOverlayWithLimits &&
            hasOverlayPermission()
        if (requestedOverlayEnabled && !canEnableOverlayWithLimits) {
            Toast.makeText(this, R.string.overlay_requires_limits, Toast.LENGTH_LONG).show()
            setOverlaySwitchChecked(false)
        } else if (requestedOverlayEnabled && !hasOverlayPermission()) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            setOverlaySwitchChecked(false)
        }

        smsLimitSettings.saveConfig(
            dailyLimit = dailyLimit.takeIf { it != EMPTY_LIMIT },
            monthlyLimit = monthlyLimit.takeIf { it != EMPTY_LIMIT }
        )
        applyOverlayState(shouldEnableOverlay)

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

    private fun setOverlaySwitchChecked(isChecked: Boolean) {
        bindingOverlaySwitch = true
        overlaySwitch.isChecked = isChecked
        bindingOverlaySwitch = false
    }

    private fun canEnableOverlayWithLimits(dailyLimit: Int, monthlyLimit: Int): Boolean {
        return dailyLimit > 0 && monthlyLimit > 0
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        overlayPermissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun applyOverlayState(enabled: Boolean) {
        smsLimitSettings.setOverlayEnabled(enabled)
        if (enabled) {
            UsageOverlayService.start(this)
        } else {
            UsageOverlayService.stop(this)
        }
    }

    companion object {
        private const val EMPTY_LIMIT = -1
        private const val INVALID_LIMIT = -2
    }
}

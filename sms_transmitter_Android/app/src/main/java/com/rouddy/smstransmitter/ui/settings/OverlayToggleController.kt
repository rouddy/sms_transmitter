package com.rouddy.smstransmitter.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rouddy.smstransmitter.R
import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import com.rouddy.smstransmitter.data.SmsLimitSettings
import com.rouddy.smstransmitter.ui.overlay.UsageOverlayService

enum class OverlayEnablementStatus {
    ENABLED,
    LIMITS_MISSING,
    PERMISSION_MISSING;

    companion object {
        /** The overlay shows usage against limits, so at least one limit must be set. */
        fun evaluate(phoneNumbers: List<PhoneNumberLimitConfig>?, hasPermission: Boolean): OverlayEnablementStatus {
            if (phoneNumbers.orEmpty().none { it.dailyLimit != null || it.monthlyLimit != null }) {
                return LIMITS_MISSING
            }
            if (!hasPermission) {
                return PERMISSION_MISSING
            }
            return ENABLED
        }
    }
}

/**
 * Drives the overlay switch: checks limits and the draw-over-apps permission when it is
 * turned on, requests the permission if needed, and starts/stops [UsageOverlayService].
 *
 * Must be created before the activity is STARTED because it registers an activity result launcher.
 */
class OverlayToggleController(
    private val activity: ComponentActivity,
    private val overlaySwitch: SwitchMaterial,
    private val smsLimitSettings: SmsLimitSettings,
    private val currentPhoneNumberConfigs: () -> List<PhoneNumberLimitConfig>?,
) {
    private var bindingSwitch = false
    private var pendingEnable = false

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingEnable && hasPermission()) {
                setChecked(true)
                applyState(enabled = true)
            } else if (pendingEnable) {
                setChecked(false)
                applyState(enabled = false)
                showToast(R.string.overlay_permission_required)
            }
            pendingEnable = false
        }

    init {
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!bindingSwitch) onSwitchToggled(isChecked)
        }
    }

    /** Updates the switch without triggering the enable/disable flow. */
    fun setChecked(isChecked: Boolean) {
        bindingSwitch = true
        overlaySwitch.isChecked = isChecked
        bindingSwitch = false
    }

    /**
     * Applies the switch state when settings are saved. If the overlay was requested but
     * cannot be enabled, explains why, unchecks the switch and disables the overlay.
     */
    fun applyOnSave(phoneNumberConfigs: List<PhoneNumberLimitConfig>) {
        if (!overlaySwitch.isChecked) {
            applyState(enabled = false)
            return
        }
        when (evaluate(phoneNumberConfigs)) {
            OverlayEnablementStatus.LIMITS_MISSING -> showToast(R.string.overlay_requires_limits)
            OverlayEnablementStatus.PERMISSION_MISSING -> showToast(R.string.overlay_permission_required)
            OverlayEnablementStatus.ENABLED -> {
                applyState(enabled = true)
                return
            }
        }
        setChecked(false)
        applyState(enabled = false)
    }

    private fun onSwitchToggled(isChecked: Boolean) {
        if (!isChecked) {
            pendingEnable = false
            applyState(enabled = false)
            return
        }
        when (evaluate(currentPhoneNumberConfigs())) {
            OverlayEnablementStatus.LIMITS_MISSING -> {
                showToast(R.string.overlay_requires_limits)
                setChecked(false)
            }
            OverlayEnablementStatus.PERMISSION_MISSING -> {
                pendingEnable = true
                requestPermission()
            }
            OverlayEnablementStatus.ENABLED -> applyState(enabled = true)
        }
    }

    private fun evaluate(phoneNumberConfigs: List<PhoneNumberLimitConfig>?): OverlayEnablementStatus =
        OverlayEnablementStatus.evaluate(phoneNumberConfigs, hasPermission())

    private fun hasPermission(): Boolean = Settings.canDrawOverlays(activity)

    private fun requestPermission() {
        permissionLauncher.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
        )
    }

    private fun applyState(enabled: Boolean) {
        smsLimitSettings.setOverlayEnabled(enabled)
        if (enabled) {
            UsageOverlayService.start(activity)
        } else {
            UsageOverlayService.stop(activity)
        }
    }

    private fun showToast(messageRes: Int) {
        Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show()
    }
}

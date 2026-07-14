package com.rouddy.smstransmitter

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.messaging.FirebaseMessaging
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

class SettingsActivity : AppCompatActivity() {
    private lateinit var dailyLimitInput: EditText
    private lateinit var monthlyLimitInput: EditText
    private lateinit var serverUrlInput: EditText
    private lateinit var saveButton: Button
    private lateinit var overlaySwitch: SwitchMaterial
    private lateinit var smsLimitSettings: SmsLimitSettings
    private var bindingOverlaySwitch = false
    private var pendingOverlayEnable = false
    private val disposables = CompositeDisposable()

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
        serverUrlInput = findViewById(R.id.input_server_url)
        saveButton = findViewById(R.id.button_save_limits)
        overlaySwitch = findViewById(R.id.switch_overlay_ui)

        bindCurrentValues()
        saveButton.setOnClickListener { saveValues() }
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingOverlaySwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                val dailyLimit = parseLimitInput(dailyLimitInput.text?.toString())
                val monthlyLimit = parseLimitInput(monthlyLimitInput.text?.toString())
                when (evaluateOverlayEnablement(dailyLimit, monthlyLimit)) {
                    OverlayEnablementStatus.LIMITS_MISSING -> {
                        Toast.makeText(this, R.string.overlay_requires_limits, Toast.LENGTH_LONG).show()
                        setOverlaySwitchChecked(false)
                        return@setOnCheckedChangeListener
                    }
                    OverlayEnablementStatus.PERMISSION_MISSING -> {
                        pendingOverlayEnable = true
                        requestOverlayPermission()
                        return@setOnCheckedChangeListener
                    }
                    OverlayEnablementStatus.ENABLED -> Unit
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
        serverUrlInput.setText(config.serverUrl.orEmpty())
        setOverlaySwitchChecked(config.overlayEnabled)
    }

    private fun saveValues() {
        val dailyLimit = parseLimitInput(dailyLimitInput.text?.toString())
        val monthlyLimit = parseLimitInput(monthlyLimitInput.text?.toString())
        if (dailyLimit == INVALID_LIMIT || monthlyLimit == INVALID_LIMIT) {
            Toast.makeText(this, R.string.invalid_limit_input, Toast.LENGTH_LONG).show()
            return
        }

        val requestedOverlayEnabled = overlaySwitch.isChecked
        val enablementStatus = evaluateOverlayEnablement(dailyLimit, monthlyLimit)
        val shouldEnableOverlay = requestedOverlayEnabled && enablementStatus == OverlayEnablementStatus.ENABLED
        if (requestedOverlayEnabled) {
            when (enablementStatus) {
                OverlayEnablementStatus.LIMITS_MISSING -> {
                    Toast.makeText(this, R.string.overlay_requires_limits, Toast.LENGTH_LONG).show()
                    setOverlaySwitchChecked(false)
                }
                OverlayEnablementStatus.PERMISSION_MISSING -> {
                    Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
                    setOverlaySwitchChecked(false)
                }
                OverlayEnablementStatus.ENABLED -> Unit
            }
        }

        smsLimitSettings.saveConfig(
            dailyLimit = dailyLimit.takeIf { it != EMPTY_LIMIT },
            monthlyLimit = monthlyLimit.takeIf { it != EMPTY_LIMIT }
        )
        smsLimitSettings.saveServerUrl(serverUrlInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() })
        applyOverlayState(shouldEnableOverlay)
        registerFcmTokenWithServer()

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

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun setOverlaySwitchChecked(isChecked: Boolean) {
        bindingOverlaySwitch = true
        overlaySwitch.isChecked = isChecked
        bindingOverlaySwitch = false
    }

    private fun canEnableOverlayWithLimits(dailyLimit: Int, monthlyLimit: Int): Boolean {
        return dailyLimit > 0 || monthlyLimit > 0
    }

    private fun evaluateOverlayEnablement(dailyLimit: Int, monthlyLimit: Int): OverlayEnablementStatus {
        if (!canEnableOverlayWithLimits(dailyLimit, monthlyLimit)) {
            return OverlayEnablementStatus.LIMITS_MISSING
        }
        if (!hasOverlayPermission()) {
            return OverlayEnablementStatus.PERMISSION_MISSING
        }
        return OverlayEnablementStatus.ENABLED
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

    private fun registerFcmTokenWithServer() {
        val config = smsLimitSettings.getConfig()
        val serverUrl = config.serverUrl ?: return

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Failed to get FCM token.", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result ?: return@addOnCompleteListener
            val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
            GraphQlClient("$serverUrl/").registerFcmToken(
                token = token,
                deviceInfo = deviceInfo,
                dailyLimit = config.dailyLimit ?: GraphQlClient.LIMIT_UNLIMITED,
                monthlyLimit = config.monthlyLimit ?: GraphQlClient.LIMIT_UNLIMITED,
            )
                .subscribeOn(Schedulers.io())
                .subscribe(
                    { Log.i(TAG, "FCM token registered with server from SettingsActivity.") },
                    { Log.e(TAG, "Failed to register FCM token with server from SettingsActivity.", it) },
                )
                .also { disposables.add(it) }
        }
    }

    private enum class OverlayEnablementStatus {
        ENABLED,
        LIMITS_MISSING,
        PERMISSION_MISSING
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val EMPTY_LIMIT = -1
        private const val INVALID_LIMIT = -2
    }
}

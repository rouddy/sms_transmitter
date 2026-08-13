package com.rouddy.smstransmitter

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.messaging.FirebaseMessaging
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers

class SettingsActivity : AppCompatActivity() {
    private lateinit var phoneNumbersInput: EditText
    private lateinit var phoneNumberLimitsContainer: LinearLayout
    private lateinit var serverUrlInput: EditText
    private lateinit var saveButton: Button
    private lateinit var overlaySwitch: SwitchMaterial
    private lateinit var smsLimitSettings: SmsLimitSettings
    private val phoneNumberLimitInputs = linkedMapOf<String, PhoneNumberLimitInputViews>()
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
        phoneNumbersInput = findViewById(R.id.input_phone_numbers)
        phoneNumberLimitsContainer = findViewById(R.id.phone_number_limits_container)
        serverUrlInput = findViewById(R.id.input_server_url)
        saveButton = findViewById(R.id.button_save_limits)
        overlaySwitch = findViewById(R.id.switch_overlay_ui)

        bindCurrentValues()
        phoneNumbersInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                syncPhoneNumberLimitInputs(parsePhoneNumbers(s?.toString()))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        saveButton.setOnClickListener { saveValues() }
        overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingOverlaySwitch) return@setOnCheckedChangeListener
            val phoneNumberConfigs = collectPhoneNumberConfigs(showValidationError = false)
            if (isChecked) {
                when (evaluateOverlayEnablement(phoneNumberConfigs)) {
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
        phoneNumbersInput.setText(config.phoneNumbers.joinToString(separator = "\n") { it.phoneNumber })
        syncPhoneNumberLimitInputs(config.phoneNumbers.map { it.phoneNumber }, config.phoneNumbers.associateBy { it.phoneNumber })
        serverUrlInput.setText(config.serverUrl.orEmpty())
        setOverlaySwitchChecked(config.overlayEnabled)
    }

    private fun saveValues() {
        val phoneNumberConfigs = collectPhoneNumberConfigs(showValidationError = true) ?: return

        val requestedOverlayEnabled = overlaySwitch.isChecked
        val enablementStatus = evaluateOverlayEnablement(phoneNumberConfigs)
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

        smsLimitSettings.savePhoneNumberConfigs(phoneNumberConfigs)
        val serverUrl = serverUrlInput.text
            ?.toString()?.trim()
            ?.let {
                URL_PATTERN.find(it)?.let { matchResult ->
                    matchResult.groupValues[1]
                } ?: ""
            }
        if (serverUrl?.isEmpty() == true) {
            smsLimitSettings.removeServerUrl()
        } else if (serverUrl?.equals(smsLimitSettings.getConfig().serverUrl) == false) {
            smsLimitSettings.saveServerUrl(serverUrl)
        }
        applyOverlayState(shouldEnableOverlay)
        registerFcmTokenWithServer()

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun collectPhoneNumberConfigs(showValidationError: Boolean): List<PhoneNumberLimitConfig>? {
        val phoneNumbers = parsePhoneNumbers(phoneNumbersInput.text?.toString())
        val configs = mutableListOf<PhoneNumberLimitConfig>()
        for (phoneNumber in phoneNumbers) {
            val inputs = phoneNumberLimitInputs[phoneNumber] ?: continue
            val dailyLimit = parseLimitInput(inputs.dailyLimitInput.text?.toString())
            val monthlyLimit = parseLimitInput(inputs.monthlyLimitInput.text?.toString())
            if (dailyLimit == INVALID_LIMIT || monthlyLimit == INVALID_LIMIT) {
                if (showValidationError) {
                    Toast.makeText(this, R.string.invalid_limit_input, Toast.LENGTH_LONG).show()
                }
                return null
            }
            configs += PhoneNumberLimitConfig(
                phoneNumber = phoneNumber,
                dailyLimit = dailyLimit.takeIf { it != EMPTY_LIMIT },
                monthlyLimit = monthlyLimit.takeIf { it != EMPTY_LIMIT },
            )
        }
        return configs
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

    private fun canEnableOverlayWithLimits(phoneNumbers: List<PhoneNumberLimitConfig>?): Boolean {
        return phoneNumbers.orEmpty().any { it.dailyLimit != null || it.monthlyLimit != null }
    }

    private fun evaluateOverlayEnablement(phoneNumbers: List<PhoneNumberLimitConfig>?): OverlayEnablementStatus {
        if (!canEnableOverlayWithLimits(phoneNumbers)) {
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
                phoneNumbers = config.phoneNumbers,
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { Log.i(TAG, "FCM token registered with server from SettingsActivity.") },
                    {
                        Log.e(TAG, "Failed to register FCM token with server from SettingsActivity.", it)
                        Toast.makeText(this@SettingsActivity, R.string.server_connection_failed, Toast.LENGTH_SHORT).show()
                    },
                )
                .also { disposables.add(it) }
        }
    }

    private fun syncPhoneNumberLimitInputs(
        phoneNumbers: List<String>,
        savedConfigs: Map<String, PhoneNumberLimitConfig> = emptyMap()
    ) {
        val currentValues = phoneNumberLimitInputs.mapValues { (_, inputs) ->
            PhoneNumberLimitDraft(
                dailyLimit = inputs.dailyLimitInput.text?.toString().orEmpty(),
                monthlyLimit = inputs.monthlyLimitInput.text?.toString().orEmpty(),
            )
        }

        phoneNumberLimitsContainer.removeAllViews()
        phoneNumberLimitInputs.clear()

        phoneNumbers.forEach { phoneNumber ->
            val savedConfig = savedConfigs[phoneNumber]
            val draft = currentValues[phoneNumber]
            val row = createPhoneNumberLimitRow(
                phoneNumber = phoneNumber,
                dailyLimit = draft?.dailyLimit ?: savedConfig?.dailyLimit?.toString().orEmpty(),
                monthlyLimit = draft?.monthlyLimit ?: savedConfig?.monthlyLimit?.toString().orEmpty(),
            )
            phoneNumberLimitsContainer.addView(row.container)
            phoneNumberLimitInputs[phoneNumber] = row
        }

        if (phoneNumbers.isEmpty()) {
            phoneNumberLimitsContainer.addView(
                TextView(this).apply {
                    text = getString(R.string.no_phone_numbers_configured)
                }
            )
        }
    }

    private fun createPhoneNumberLimitRow(
        phoneNumber: String,
        dailyLimit: String,
        monthlyLimit: String
    ): PhoneNumberLimitInputViews {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
        val titleView = TextView(this).apply {
            text = phoneNumber
        }
        val limitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val dailyLimitInput = EditText(this).apply {
            hint = getString(R.string.daily_limit_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(dailyLimit)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val monthlyLimitInput = EditText(this).apply {
            hint = getString(R.string.monthly_limit_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(monthlyLimit)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        limitRow.addView(dailyLimitInput)
        limitRow.addView(monthlyLimitInput)
        container.addView(titleView)
        container.addView(limitRow)
        return PhoneNumberLimitInputViews(container, dailyLimitInput, monthlyLimitInput)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class OverlayEnablementStatus {
        ENABLED,
        LIMITS_MISSING,
        PERMISSION_MISSING
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val EMPTY_LIMIT = -1
        private const val INVALID_LIMIT = -2
        private val URL_PATTERN = "^(https?://[^/]*)/?$".toRegex()
        private val PHONE_NUMBER_DELIMITER_REGEX = "[,\\n\\r]+".toRegex()
    }

    private fun parsePhoneNumbers(raw: String?): List<String> {
        return raw.orEmpty()
            .split(PHONE_NUMBER_DELIMITER_REGEX)
            .mapNotNull { PhoneNumberResolver.normalizePhoneNumber(it) }
            .distinct()
    }
}

private data class PhoneNumberLimitDraft(
    val dailyLimit: String,
    val monthlyLimit: String,
)

private data class PhoneNumberLimitInputViews(
    val container: LinearLayout,
    val dailyLimitInput: EditText,
    val monthlyLimitInput: EditText,
)

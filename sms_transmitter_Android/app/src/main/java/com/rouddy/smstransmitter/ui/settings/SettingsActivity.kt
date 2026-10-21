package com.rouddy.smstransmitter.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rouddy.smstransmitter.R
import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import com.rouddy.smstransmitter.data.SmsLimitSettings
import com.rouddy.smstransmitter.fcm.FcmTokenRegistrar
import io.reactivex.rxjava3.disposables.CompositeDisposable

class SettingsActivity : AppCompatActivity() {
    private lateinit var phoneNumbersInput: EditText
    private lateinit var serverUrlInput: EditText
    private lateinit var saveButton: Button
    private lateinit var smsLimitSettings: SmsLimitSettings
    private lateinit var phoneNumberLimitForm: PhoneNumberLimitForm
    private lateinit var overlayToggleController: OverlayToggleController
    private val disposables = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        smsLimitSettings = SmsLimitSettings(this)
        phoneNumbersInput = findViewById(R.id.input_phone_numbers)
        serverUrlInput = findViewById(R.id.input_server_url)
        saveButton = findViewById(R.id.button_save_limits)
        phoneNumberLimitForm = PhoneNumberLimitForm(findViewById(R.id.phone_number_limits_container))
        overlayToggleController = OverlayToggleController(
            activity = this,
            overlaySwitch = findViewById(R.id.switch_overlay_ui),
            smsLimitSettings = smsLimitSettings,
            currentPhoneNumberConfigs = ::collectPhoneNumberConfigs,
        )

        bindCurrentValues()
        phoneNumbersInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                phoneNumberLimitForm.sync(SettingsInputParser.parsePhoneNumbers(s?.toString()))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        saveButton.setOnClickListener { saveValues() }
    }

    private fun bindCurrentValues() {
        val config = smsLimitSettings.getConfig()
        phoneNumbersInput.setText(config.phoneNumbers.joinToString(separator = "\n") { it.phoneNumber })
        phoneNumberLimitForm.sync(config.phoneNumbers.map { it.phoneNumber }, config.phoneNumbers.associateBy { it.phoneNumber })
        serverUrlInput.setText(config.serverUrl.orEmpty())
        overlayToggleController.setChecked(config.overlayEnabled)
    }

    private fun saveValues() {
        val previousServerUrl = smsLimitSettings.getConfig().serverUrl
        val phoneNumberConfigs = collectPhoneNumberConfigs() ?: run {
            Toast.makeText(this, R.string.invalid_limit_input, Toast.LENGTH_LONG).show()
            return
        }

        smsLimitSettings.savePhoneNumberConfigs(phoneNumberConfigs)
        val serverUrl = SettingsInputParser.parseServerUrl(serverUrlInput.text?.toString())
        if (serverUrl == null) {
            smsLimitSettings.removeServerUrl()
        } else if (serverUrl != previousServerUrl) {
            smsLimitSettings.saveServerUrl(serverUrl)
        }
        overlayToggleController.applyOnSave(phoneNumberConfigs)
        FcmTokenRegistrar(this, disposables) {
            Log.e(TAG, "Failed to register FCM token with server from SettingsActivity.", it)
            Toast.makeText(this, R.string.server_connection_failed, Toast.LENGTH_SHORT).show()
        }.registerAfterServerChange(previousServerUrl)

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun collectPhoneNumberConfigs(): List<PhoneNumberLimitConfig>? {
        return phoneNumberLimitForm.collect(SettingsInputParser.parsePhoneNumbers(phoneNumbersInput.text?.toString()))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}

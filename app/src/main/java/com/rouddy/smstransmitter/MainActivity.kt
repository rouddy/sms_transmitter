package com.rouddy.smstransmitter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.sms_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ensureSmsPermission()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val numberInput = findViewById<EditText>(R.id.phone_number_input)
        numberInput.setText(prefs.getString(KEY_PHONE_NUMBER, ""))

        findViewById<Button>(R.id.save_button).setOnClickListener {
            val phoneNumber = numberInput.text.toString().trim()
            if (phoneNumber.isEmpty() || !Patterns.PHONE.matcher(phoneNumber).matches()) {
                Toast.makeText(this, R.string.invalid_phone_number, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_PHONE_NUMBER, phoneNumber).apply()
            Toast.makeText(this, R.string.phone_number_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    companion object {
        const val PREFS_NAME = "sms_transmitter_prefs"
        const val KEY_PHONE_NUMBER = "phone_number"
    }
}

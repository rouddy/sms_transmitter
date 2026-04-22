package com.rouddy.smstransmitter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var historyContentView: TextView
    private lateinit var smsHistoryDb: SmsHistoryDatabase

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.sms_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        smsHistoryDb = SmsHistoryDatabase(this)
        historyContentView = findViewById(R.id.history_content)

        ensureSmsPermission()
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    override fun onDestroy() {
        smsHistoryDb.close()
        super.onDestroy()
    }

    private fun ensureSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun renderHistory() {
        val histories = smsHistoryDb.getRecentSmsHistories()
        if (histories.isEmpty()) {
            historyContentView.text = getString(R.string.no_history)
            return
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val content = histories.joinToString(separator = "\n\n") { item ->
            val smsStatus = if (item.smsSuccess) {
                getString(R.string.sms_success)
            } else {
                getString(R.string.sms_failure)
            }
            val callbackStatus = when (item.callbackSuccess) {
                true -> getString(R.string.callback_success)
                false -> getString(R.string.callback_failure)
                null -> getString(R.string.callback_pending)
            }
            buildString {
                append("시간: ${formatter.format(Date(item.createdAtMillis))}\n")
                append("수신번호: ${item.phoneNumber}\n")
                append("메시지: ${item.message}\n")
                append("SMS: $smsStatus")
                if (!item.smsError.isNullOrBlank()) append(" (${item.smsError})")
                append("\n")
                append("Callback: $callbackStatus")
                if (!item.callbackUrl.isNullOrBlank()) append(" [${item.callbackUrl}]")
                if (!item.callbackError.isNullOrBlank()) append(" (${item.callbackError})")
            }
        }
        historyContentView.text = content
    }
}

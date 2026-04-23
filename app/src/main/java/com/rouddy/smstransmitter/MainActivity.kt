package com.rouddy.smstransmitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var remainingCountView: TextView
    private lateinit var historyContentView: TextView
    private lateinit var smsHistoryDb: SmsHistoryDatabase
    private lateinit var smsLimitSettings: SmsLimitSettings
    private val historyDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.sms_permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        smsLimitSettings = SmsLimitSettings(this)
        smsHistoryDb = SmsHistoryDatabase(this)
        remainingCountView = findViewById(R.id.remaining_count_content)
        historyContentView = findViewById(R.id.history_content)

        ensureSmsPermission()
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        refreshOverlayUi()
    }

    override fun onDestroy() {
        smsHistoryDb.close()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun ensureSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun renderHistory() {
        renderRemainingCounts()

        val histories = smsHistoryDb.getRecentSmsHistories()
        if (histories.isEmpty()) {
            historyContentView.text = getString(R.string.no_history)
            return
        }

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
                append("시간: ${historyDateFormatter.format(Date(item.createdAtMillis))}\n")
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

    private fun renderRemainingCounts() {
        val now = System.currentTimeMillis()
        val config = smsLimitSettings.getConfig()

        val todaySentCount = smsHistoryDb.countSuccessfulSmsBetween(
            SmsLimitSettings.getStartOfTodayMillis(now),
            SmsLimitSettings.getStartOfNextDayMillis(now)
        )
        val monthSentCount = smsHistoryDb.countSuccessfulSmsBetween(
            SmsLimitSettings.getStartOfMonthMillis(now),
            SmsLimitSettings.getStartOfNextMonthMillis(now)
        )

        val dailyRemainingText = formatRemainingText(config.dailyLimit, todaySentCount)
        val monthlyRemainingText = formatRemainingText(config.monthlyLimit, monthSentCount)

        remainingCountView.text = getString(
            R.string.remaining_sms_counts,
            dailyRemainingText,
            monthlyRemainingText
        )
    }

    private fun formatRemainingText(limit: Int?, sentCount: Int): String {
        return limit?.let { (it - sentCount).coerceAtLeast(0).toString() }
            ?: getString(R.string.unlimited)
    }

    private fun refreshOverlayUi() {
        val config = smsLimitSettings.getConfig()
        val shouldShowOverlay = config.overlayEnabled &&
            config.dailyLimit != null &&
            config.monthlyLimit != null &&
            Settings.canDrawOverlays(this)
        if (shouldShowOverlay) {
            UsageOverlayService.start(this)
        } else {
            UsageOverlayService.stop(this)
        }
    }
}

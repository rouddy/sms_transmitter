package com.rouddy.smstransmitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var usageSummaryView: TextView
    private lateinit var historyContentView: TextView
    private lateinit var smsHistoryDb: SmsHistoryDatabase
    private lateinit var smsLimitSettings: SmsLimitSettings
    private val disposables = CompositeDisposable()
    private val historyDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val smsGranted = result[Manifest.permission.SEND_SMS] ?: hasPermission(Manifest.permission.SEND_SMS)
            if (!smsGranted) {
                Toast.makeText(this, R.string.sms_permission_required, Toast.LENGTH_LONG).show()
            }
            val readPhoneStateGranted =
                result[Manifest.permission.READ_PHONE_STATE] ?: hasPermission(Manifest.permission.READ_PHONE_STATE)
            val readPhoneNumbersGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result[Manifest.permission.READ_PHONE_NUMBERS] ?: hasPermission(Manifest.permission.READ_PHONE_NUMBERS)
            } else {
                true
            }
            if (!readPhoneStateGranted || !readPhoneNumbersGranted) {
                Toast.makeText(this, R.string.phone_number_permission_required, Toast.LENGTH_LONG).show()
            } else {
                smsLimitSettings.initializePhoneNumbersIfNeeded(this)
                registerFcmTokenWithServer()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        smsLimitSettings = SmsLimitSettings(this)
        smsHistoryDb = SmsHistoryDatabase(this)
        usageSummaryView = findViewById(R.id.usage_summary_content)
        historyContentView = findViewById(R.id.history_content)

        ensureSmsPermission()
        registerFcmTokenWithServer()
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
        refreshOverlayUi()
    }

    override fun onDestroy() {
        smsHistoryDb.close()
        disposables.clear()
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
        val permissionsToRequest = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !hasPermission(Manifest.permission.READ_PHONE_NUMBERS)
        ) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun registerFcmTokenWithServer() {
        smsLimitSettings.initializePhoneNumbersIfNeeded(this)
        val serverUrl = smsLimitSettings.getConfig().serverUrl ?: return

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Failed to get FCM token.", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result ?: return@addOnCompleteListener
            val deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
            val config = smsLimitSettings.getConfig()
            val disposable = GraphQlClient("$serverUrl/").registerFcmToken(
                token = token,
                deviceInfo = deviceInfo,
                phoneNumbers = config.phoneNumbers,
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { Log.i(TAG, "FCM token registered with server from MainActivity.") },
                    {
                        Log.e(TAG, "Failed to register FCM token with server from MainActivity.", it)
                        Toast.makeText(this@MainActivity, R.string.server_connection_failed, Toast.LENGTH_SHORT).show()
                    },
                )
            disposables.add(disposable)
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
            buildString {
                append("시간: ${historyDateFormatter.format(Date(item.createdAtMillis))}\n")
                append("${getString(R.string.sender_phone_number_label)}: ${item.senderPhoneNumber ?: "-"}\n")
                append("수신번호: ${item.phoneNumber}\n")
                append("메시지: ${item.message}\n")
                append("SMS: $smsStatus")
                if (!item.smsError.isNullOrBlank()) append(" (${item.smsError})")
            }
        }
        historyContentView.text = content
    }

    private fun renderRemainingCounts() {
        val now = System.currentTimeMillis()
        val config = smsLimitSettings.getConfig()
        if (config.phoneNumbers.isEmpty()) {
            usageSummaryView.text = getString(R.string.default_usage_summary)
            return
        }

        val summary = config.phoneNumbers.joinToString(separator = "\n\n") { phoneConfig ->
            val todaySentCount = smsHistoryDb.countSuccessfulSmsBetweenForSender(
                phoneConfig.phoneNumber,
                SmsLimitSettings.getStartOfTodayMillis(now),
                SmsLimitSettings.getStartOfNextDayMillis(now)
            )
            val monthSentCount = smsHistoryDb.countSuccessfulSmsBetweenForSender(
                phoneConfig.phoneNumber,
                SmsLimitSettings.getStartOfMonthMillis(now),
                SmsLimitSettings.getStartOfNextMonthMillis(now)
            )
            buildString {
                append(getString(R.string.phone_number_usage_header, phoneConfig.phoneNumber))
                append('\n')
                append(
                    formatUsageLine(
                        periodLabel = getString(R.string.period_daily),
                        limit = phoneConfig.dailyLimit,
                        sentCount = todaySentCount
                    )
                )
                append('\n')
                append(
                    formatUsageLine(
                        periodLabel = getString(R.string.period_monthly),
                        limit = phoneConfig.monthlyLimit,
                        sentCount = monthSentCount
                    )
                )
            }
        }
        usageSummaryView.text = summary
    }

    private fun formatUsageLine(periodLabel: String, limit: Int?, sentCount: Int): String {
        return if (limit != null && limit > 0) {
            getString(
                R.string.sms_usage_with_limit,
                periodLabel,
                sentCount,
                limit,
                (limit - sentCount).coerceAtLeast(0)
            )
        } else {
            getString(R.string.sms_usage_unlimited, periodLabel, sentCount)
        }
    }

    private fun refreshOverlayUi() {
        val config = smsLimitSettings.getConfig()
        val shouldShowOverlay = config.overlayEnabled &&
            config.phoneNumbers.any { it.dailyLimit != null || it.monthlyLimit != null } &&
            Settings.canDrawOverlays(this)
        if (shouldShowOverlay) {
            UsageOverlayService.start(this)
        } else {
            UsageOverlayService.stop(this)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

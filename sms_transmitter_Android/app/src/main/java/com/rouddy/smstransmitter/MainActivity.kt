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
import android.view.View
import android.widget.ProgressBar
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
    private lateinit var dailyUsageView: TextView
    private lateinit var monthlyUsageView: TextView
    private lateinit var dailyUsageProgress: ProgressBar
    private lateinit var monthlyUsageProgress: ProgressBar
    private lateinit var historyContentView: TextView
    private lateinit var smsHistoryDb: SmsHistoryDatabase
    private lateinit var smsLimitSettings: SmsLimitSettings
    private val disposables = CompositeDisposable()
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
        dailyUsageView = findViewById(R.id.daily_usage_content)
        monthlyUsageView = findViewById(R.id.monthly_usage_content)
        dailyUsageProgress = findViewById(R.id.progress_daily_usage)
        monthlyUsageProgress = findViewById(R.id.progress_monthly_usage)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.SEND_SMS)
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
            val disposable = GraphQlClient("$serverUrl/").registerFcmToken(
                token = token,
                deviceInfo = deviceInfo,
                dailyLimit = config.dailyLimit ?: GraphQlClient.LIMIT_UNLIMITED,
                monthlyLimit = config.monthlyLimit ?: GraphQlClient.LIMIT_UNLIMITED,
            )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { Log.i(TAG, "FCM token registered with server from MainActivity.") },
                    { Log.e(TAG, "Failed to register FCM token with server from MainActivity.", it) },
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

        renderUsageRow(
            limit = config.dailyLimit,
            sentCount = todaySentCount,
            periodLabel = getString(R.string.period_daily),
            textView = dailyUsageView,
            progressBar = dailyUsageProgress
        )
        renderUsageRow(
            limit = config.monthlyLimit,
            sentCount = monthSentCount,
            periodLabel = getString(R.string.period_monthly),
            textView = monthlyUsageView,
            progressBar = monthlyUsageProgress
        )
    }

    private fun renderUsageRow(
        limit: Int?,
        sentCount: Int,
        periodLabel: String,
        textView: TextView,
        progressBar: ProgressBar
    ) {
        if (limit != null && limit > 0) {
            val remainingCount = (limit - sentCount).coerceAtLeast(0)
            textView.text = getString(
                R.string.sms_usage_with_limit,
                periodLabel,
                sentCount,
                limit,
                remainingCount
            )
            progressBar.visibility = View.VISIBLE
            progressBar.max = limit
            progressBar.progress = sentCount.coerceAtMost(limit)
        } else {
            textView.text = getString(
                R.string.sms_usage_unlimited,
                periodLabel,
                sentCount
            )
            progressBar.visibility = View.GONE
        }
    }

    private fun refreshOverlayUi() {
        val config = smsLimitSettings.getConfig()
        val shouldShowOverlay = config.overlayEnabled &&
            (config.dailyLimit != null || config.monthlyLimit != null) &&
            Settings.canDrawOverlays(this)
        if (shouldShowOverlay) {
            UsageOverlayService.start(this)
        } else {
            UsageOverlayService.stop(this)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

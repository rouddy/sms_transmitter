package com.rouddy.smstransmitter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var usageSummaryView: TextView
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var emptyHistoryView: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var historyAdapter: SmsHistoryAdapter

    private lateinit var smsHistoryDb: SmsHistoryDatabase
    private lateinit var smsLimitSettings: SmsLimitSettings
    private val disposables = CompositeDisposable()
    private val historyDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val smsGranted = result[Manifest.permission.SEND_SMS] ?: hasPermission(Manifest.permission.SEND_SMS)
            val readPhoneNumbersGranted = result[Manifest.permission.READ_PHONE_NUMBERS] ?: hasPermission(Manifest.permission.READ_PHONE_NUMBERS)
            val readPhoneStateGranted = result[Manifest.permission.READ_PHONE_STATE] ?: hasPermission(Manifest.permission.READ_PHONE_STATE)

            if (!smsGranted) {
                Toast.makeText(this, R.string.sms_permission_required, Toast.LENGTH_LONG).show()
            }

            if (!readPhoneNumbersGranted || !readPhoneStateGranted) {
                Toast.makeText(this, R.string.phone_number_permission_required, Toast.LENGTH_LONG).show()
            }

            if (smsGranted && readPhoneNumbersGranted && readPhoneStateGranted) {
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
        historyRecyclerView = findViewById(R.id.history_recycler_view)
        emptyHistoryView = findViewById(R.id.empty_history_view)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)

        historyAdapter = SmsHistoryAdapter(historyDateFormatter)
        historyRecyclerView.adapter = historyAdapter

        swipeRefreshLayout.setOnRefreshListener {
            renderHistory()
        }

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
        if (!hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun registerFcmTokenWithServer() {
        smsLimitSettings.initializePhoneNumbersIfNeeded(this)
        FcmTokenRegistrar(this, disposables) {
            Log.e(TAG, "Failed to register FCM token with server from MainActivity.", it)
            Toast.makeText(this, R.string.server_connection_failed, Toast.LENGTH_SHORT).show()
        }.register()
    }

    private fun renderHistory() {
        renderRemainingCounts()

        val histories = smsHistoryDb.getRecentSmsHistories()
        swipeRefreshLayout.isRefreshing = false

        if (histories.isEmpty()) {
            historyRecyclerView.visibility = View.GONE
            emptyHistoryView.visibility = View.VISIBLE
            return
        }

        historyRecyclerView.visibility = View.VISIBLE
        emptyHistoryView.visibility = View.GONE
        historyAdapter.submitList(histories)
    }

    private fun renderRemainingCounts() {
        val now = System.currentTimeMillis()
        val config = smsLimitSettings.getConfig()
        if (config.phoneNumbers.isEmpty()) {
            usageSummaryView.text = getString(R.string.default_usage_summary)
            return
        }

        val summary = config.phoneNumbers.joinToString(separator = "\n\n") { phoneConfig ->
            val todaySentCount = SmsUsageCalculator.sentToday(smsHistoryDb, phoneConfig.phoneNumber, now)
            val monthSentCount = SmsUsageCalculator.sentThisMonth(smsHistoryDb, phoneConfig.phoneNumber, now)
            buildString {
                append(getString(R.string.phone_number_usage_header, phoneConfig.phoneNumber))
                append('\n')
                append(
                    SmsUsageCalculator.formatUsageLine(
                        this@MainActivity,
                        getString(R.string.period_daily),
                        phoneConfig.dailyLimit,
                        todaySentCount,
                    )
                )
                append('\n')
                append(
                    SmsUsageCalculator.formatUsageLine(
                        this@MainActivity,
                        getString(R.string.period_monthly),
                        phoneConfig.monthlyLimit,
                        monthSentCount,
                    )
                )
            }
        }
        usageSummaryView.text = summary
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

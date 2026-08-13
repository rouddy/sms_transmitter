package com.rouddy.smstransmitter

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class UsageOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var smsLimitSettings: SmsLimitSettings
    private lateinit var smsHistoryDatabase: SmsHistoryDatabase
    private var overlayContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshOverlay()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        smsLimitSettings = SmsLimitSettings(this)
        smsHistoryDatabase = SmsHistoryDatabase(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_REFRESH, null -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                refreshOverlay()
                mainHandler.removeCallbacks(refreshRunnable)
                mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        removeOverlay()
        smsHistoryDatabase.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshOverlay() {
        val config = smsLimitSettings.getConfig()
        val limitedPhoneNumbers = config.phoneNumbers.filter { it.dailyLimit != null || it.monthlyLimit != null }
        if (!config.overlayEnabled || limitedPhoneNumbers.isEmpty()) {
            removeOverlay()
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        val container = ensureOverlayContainer()
        container.removeAllViews()

        limitedPhoneNumbers.forEachIndexed { index, phoneConfig ->
            if (index > 0) {
                addDivider(container)
            }
            container.addView(
                TextView(this).apply {
                    setTextColor(Color.WHITE)
                    text = buildString {
                        append(phoneConfig.phoneNumber)
                        append('\n')
                        append(
                            formatUsageLine(
                                periodLabel = getString(R.string.period_daily),
                                limit = phoneConfig.dailyLimit,
                                sentCount = smsHistoryDatabase.countSuccessfulSmsBetweenForSender(
                                    phoneConfig.phoneNumber,
                                    SmsLimitSettings.getStartOfTodayMillis(now),
                                    SmsLimitSettings.getStartOfNextDayMillis(now)
                                )
                            )
                        )
                        append('\n')
                        append(
                            formatUsageLine(
                                periodLabel = getString(R.string.period_monthly),
                                limit = phoneConfig.monthlyLimit,
                                sentCount = smsHistoryDatabase.countSuccessfulSmsBetweenForSender(
                                    phoneConfig.phoneNumber,
                                    SmsLimitSettings.getStartOfMonthMillis(now),
                                    SmsLimitSettings.getStartOfNextMonthMillis(now)
                                )
                            )
                        )
                    }
                }
            )
        }
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

    private fun createOverlayContainer(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setBackgroundColor(Color.argb(160, 20, 20, 20))
            setOnTouchListener(DragTouchListener())
        }
        overlayContainer = container
        return container
    }

    private fun addDivider(container: LinearLayout) {
        container.addView(
            View(this).apply {
                setBackgroundColor(Color.argb(120, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1f)
                ).apply {
                    topMargin = dp(8f)
                    bottomMargin = dp(8f)
                }
            }
        )
    }

    private fun ensureOverlayContainer(): LinearLayout {
        overlayContainer?.let { return it }
        val container = createOverlayContainer()
        addOverlay(container)
        return container
    }

    private fun addOverlay(container: LinearLayout) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val defaultPosition = smsLimitSettings.getOverlayPosition()
            ?: (dp(24f) to dp(120f))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = defaultPosition.first
            y = defaultPosition.second
        }

        layoutParams = params
        windowManager.addView(container, params)
    }

    private fun removeOverlay() {
        overlayContainer?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayContainer = null
        layoutParams = null
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var startTouchRawX = 0f
        private var startTouchRawY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchRawX = event.rawX
                    startTouchRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - startTouchRawX).toInt()
                    params.y = startY + (event.rawY - startTouchRawY).toInt()
                    windowManager.updateViewLayout(v, params)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    smsLimitSettings.saveOverlayPosition(params.x, params.y)
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L
        const val ACTION_START = "com.rouddy.smstransmitter.overlay.START"
        const val ACTION_STOP = "com.rouddy.smstransmitter.overlay.STOP"
        const val ACTION_REFRESH = "com.rouddy.smstransmitter.overlay.REFRESH"

        fun start(context: Context) {
            context.startService(Intent(context, UsageOverlayService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, UsageOverlayService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}

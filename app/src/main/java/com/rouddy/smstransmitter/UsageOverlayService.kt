package com.rouddy.smstransmitter

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlin.math.min

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
        if (!config.overlayEnabled || config.dailyLimit == null || config.monthlyLimit == null) {
            removeOverlay()
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        val todaySentCount = smsHistoryDatabase.countSuccessfulSmsBetween(
            SmsLimitSettings.getStartOfTodayMillis(now),
            SmsLimitSettings.getStartOfNextDayMillis(now)
        )
        val monthSentCount = smsHistoryDatabase.countSuccessfulSmsBetween(
            SmsLimitSettings.getStartOfMonthMillis(now),
            SmsLimitSettings.getStartOfNextMonthMillis(now)
        )

        val container = overlayContainer ?: createOverlayContainer().also { addOverlay(it) }
        container.removeAllViews()

        container.addView(
            RadialUsageView(this).apply {
                setUsage(
                    label = getString(R.string.overlay_daily_label),
                    limit = config.dailyLimit,
                    sent = todaySentCount
                )
            }
        )
        container.addView(
            RadialUsageView(this).apply {
                setUsage(
                    label = getString(R.string.overlay_monthly_label),
                    limit = config.monthlyLimit,
                    sent = monthSentCount
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(16f) }
        )
    }

    private fun createOverlayContainer(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            setBackgroundColor(Color.argb(130, 20, 20, 20))
            setOnTouchListener(DragTouchListener())
        }
        overlayContainer = container
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

private class RadialUsageView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        color = Color.argb(130, 255, 255, 255)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(8f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#4CAF50")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(22f).toFloat()
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(12f).toFloat()
    }

    private var label = ""
    private var remaining = 0
    private var progress = 0f
    private val sizePx = dp(100f)

    init {
        layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
    }

    fun setUsage(label: String, limit: Int, sent: Int) {
        this.label = label
        if (limit <= 0) {
            remaining = 0
            progress = 0f
            invalidate()
            return
        }
        remaining = (limit - sent).coerceAtLeast(0)
        progress = (sent.coerceAtMost(limit).toFloat() / limit.toFloat()).coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - trackPaint.strokeWidth

        canvas.drawCircle(cx, cy, radius, trackPaint)
        canvas.drawArc(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            -90f,
            progress * 360f,
            false,
            progressPaint
        )
        canvas.drawText(remaining.toString(), cx, cy + dp(8f), valuePaint)
        canvas.drawText(label, cx, cy - dp(12f), labelPaint)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}

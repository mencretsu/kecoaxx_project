package com.example.ngontol

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

@Suppress("DEPRECATION")
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormatter by lazy {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    companion object {
        private var serviceRunning = false

        fun isServiceRunning(context: Context): Boolean {
            val am = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            return am.getRunningServices(Integer.MAX_VALUE).any {
                it.service.className == OverlayService::class.java.name
            }
        }
    }

    private val logTemplates = arrayOf(
        "INFO" to "Init [%d:%d] >> memory: %dMB OK",
        "INFO" to "Pool 192.168.%d.%d authenticated",
        "JOB" to "JobID:%d | threads: %d running",
        "HASH" to "0x%x accepted | diff:%d verified",
        "WARN" to "retry in %ds | connection unstable",
        "OK" to "Share ok | %dms | total:%d"
    )

    private val logRunnable = object : Runnable {
        override fun run() {
            overlayView?.findViewById<TextView>(R.id.dummyLog)?.let { tv ->
                val (level, template) = logTemplates.random()
                val msg = formatLog(level, template)
                addColoredLog(tv, level, msg)
            }
            handler.postDelayed(this, Random.nextLong(1000, 2000))
        }
    }

    private fun formatLog(level: String, template: String): String {
        return when (level) {
            "INFO" -> String.format(template, Random.nextInt(1000, 9999),
                Random.nextInt(10, 99), Random.nextInt(500, 16000))
            "OK" -> String.format(template, Random.nextInt(100, 999),
                Random.nextInt(1000, 9999))
            "JOB" -> String.format(template, Random.nextInt(100000, 999999),
                Random.nextInt(1, 16))
            "HASH" -> String.format(template, Random.nextInt(10000000, 99999999),
                Random.nextInt(1, 1000))
            "WARN" -> String.format(template, Random.nextInt(1, 5))
            else -> template
        }
    }

    private fun addColoredLog(tv: TextView, level: String, msg: String) {
        val color = when (level) {
            "OK" -> Color.GREEN
            "WARN" -> Color.YELLOW
            "ERROR" -> Color.RED
            else -> Color.WHITE
        }

        val logLine = "[${timeFormatter.format(System.currentTimeMillis())}] $level $msg\n"
        val spannable = SpannableString(logLine).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        tv.append(spannable)

        // FIX: Ambil reference dulu, baru post
        val scrollView = overlayView?.findViewById<ScrollView>(R.id.logScroll)
        scrollView?.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceRunning = true
        startForeground(2, createNotification())

        if (overlayView == null) {
            setupOverlay()
            handler.postDelayed(logRunnable, 1000)
        }

        return START_STICKY
    }

    @SuppressLint("InflateParams")
    private fun setupOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_dummy, null).apply {
            contentDescription = "OVERLAY_IGNORE"
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        wm?.addView(overlayView, params)
    }

    private fun createNotification(): Notification {
        val channelId = "overlay_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mode Mining Aktif")
            .setContentText("Overlay berjalan")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        serviceRunning = false
        handler.removeCallbacks(logRunnable)
        overlayView?.let { wm?.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
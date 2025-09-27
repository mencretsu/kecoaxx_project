package com.example.ngontol

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.ngontol.firebase.FirebaseManager
import com.example.ngontol.services.S1Service
import com.example.ngontol.services.S2Service
import com.example.ngontol.services.S3Service
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.LinkedHashSet

@Suppress("DEPRECATION")
class MyBotService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "BOT_CH"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.ngontol.STOP"
        const val TAG = "BOT"

        var instance: MyBotService? = null
            private set

        // ✅ Single source of truth
        @Volatile
        private var isServiceEnabled = false

        @Volatile
        private var allowedToRun = false

        fun isServiceActive(): Boolean = instance != null && isServiceEnabled
    }

    private val processed = LinkedHashSet<String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📥 onStartCommand: action = ${intent?.action}")

        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "🛑 ACTION_STOP received - stopping bot")

            // ✅ Stop bot logic
            isServiceEnabled = false
            allowedToRun = false

            getSharedPreferences("bot_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_alive", false)
                .apply()

            // ✅ Broadcast ke MainActivity
            sendBroadcast(Intent("com.example.ngontol.BOT_STATUS_CHANGED"))

            // ✅ Cancel notif
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.cancel(NOTIF_ID)

            // ✅ Stop foreground tapi JANGAN stopSelf()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                stopForeground(true)
            }

            Log.d(TAG, "✅ Bot stopped, accessibility tetap ON")

            // ✅ PENTING: Return START_STICKY, biarkan accessibility tetap hidup
            return START_STICKY
        }

        // Start bot jika belum running
        if (!isServiceEnabled) {
            checkDeviceStatus()
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ onServiceConnected - Accessibility Service Connected")
        instance = this

        // Auto-start jika belum running
        if (!isServiceEnabled) {
            checkDeviceStatus()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkDeviceStatus() {
        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val db = FirebaseDatabase.getInstance(FirebaseManager.DB_URL)
        val deviceRef = db.getReference("devices").child(deviceId)

        // ✅ Launch coroutine untuk Firebase init
        serviceScope.launch {
            try {
                FirebaseManager.ensureDeviceInitialized(applicationContext)
                FirebaseManager.updateLastSeen(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Firebase init error: ${e.message}")
            }
        }

        // Cek status awal
        deviceRef.child("status").get().addOnSuccessListener { snapshot ->
            val status = snapshot.getValue(String::class.java) ?: "on"
            if (status == "off") {
                allowedToRun = false
                Log.w(TAG, "⛔ Device diblokir dari awal, bot tidak jalan")
                showToast("⛔ Bot tidak bisa start (status OFF)")
            } else {
                allowedToRun = true
                startBotIfNeeded()
            }
        }.addOnFailureListener {
            // Fallback: kalau Firebase error, allow bot jalan
            Log.e(TAG, "⚠️ Firebase check failed, allowing start")
            allowedToRun = true
            startBotIfNeeded()
        }

        // Realtime listener untuk monitor perubahan status
        deviceRef.child("status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java) ?: "on"

                if (status == "off" && allowedToRun) {
                    // Changed from ON to OFF
                    allowedToRun = false
                    isServiceEnabled = false

                    getSharedPreferences("bot_prefs", MODE_PRIVATE).edit()
                        .putBoolean("service_alive", false)
                        .apply()

                    sendBroadcast(Intent("com.example.ngontol.BOT_STATUS_CHANGED"))

                    val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notifManager.cancel(NOTIF_ID)

                    showToast("⛔ Bot berhenti (status OFF)")
                    Log.w(TAG, "⛔ Device diblokir realtime, bot stop")

                } else if (status == "on" && !allowedToRun) {
                    // Changed from OFF to ON
                    allowedToRun = true
                    startBotIfNeeded()
                    showToast("✅ Bot aktif [status ON]")
                    Log.d(TAG, "✅ Device dibuka blokirnya, bot jalan lagi")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Listener cancelled: ${error.message}")
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startBotIfNeeded() {
        if (!allowedToRun) {
            Log.w(TAG, "⛔ Bot tidak dijalankan karena blocked")
            return
        }

        if (isServiceEnabled) {
            Log.d(TAG, "⚠️ Bot already running, skip start")
            return
        }

        Log.d(TAG, "🚀 Starting bot...")
        isServiceEnabled = true

        getSharedPreferences("bot_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_alive", true)
            .apply()

        // ✅ Broadcast ke MainActivity
        sendBroadcast(Intent("com.example.ngontol.BOT_STATUS_CHANGED"))

        val pkg = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("last_app_package", "") ?: ""

        ensureBotChannel()
        showNotif()

        when (pkg) {
            "com.voicemaker.android" -> S1Service.start(this, serviceScope) { isServiceEnabled }
            "com.hwsj.club" -> S2Service.start(this, serviceScope) { isServiceEnabled }
            "com.fiya.android" -> S3Service.start(this, serviceScope) { isServiceEnabled }
        }

        Log.d(TAG, "✅ Bot started successfully")
    }

    @SuppressLint("ForegroundServiceType")
    private fun showNotif() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MyBotService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val appName = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("last_app_name", "") ?: ""

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bot)
            .setContentTitle("KECOAXX BOT")
            .setContentText("AKTIF • $appName")
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "[ Stop Bot ]", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        // ✅ Buat channel dulu
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bot Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }

        // ✅ SELALU pakai startForeground() untuk notif permanen
        // Android < 13: langsung startForeground
        // Android >= 13: tetap startForeground tapi notif jadi biasa (sistem handle otomatis)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(NOTIF_ID, notif)
        } else {
            // Android 13+ tetap pakai startForeground, tapi notif tampil biasa
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, notif)
        }
    }

    private fun ensureBotChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ✅ Check both flags sebelum process event
        if (!allowedToRun || !isServiceEnabled) return

        val pkg = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("last_app_package", "") ?: ""

        when (pkg) {
            "com.voicemaker.android" -> S1Service.onAccessibilityEvent(this, serviceScope) { isServiceEnabled }
            "com.hwsj.club" -> S2Service.onAccessibilityEvent(this, serviceScope) { isServiceEnabled }
            "com.fiya.android" -> S3Service.onAccessibilityEvent(this, serviceScope) { isServiceEnabled }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ Service interrupted")
    }

    // ✅ Handle clear recent apps - re-show notif
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "📱 Task removed (Recent apps cleared)")

        // ✅ Jika bot masih running, re-create notif
        if (isServiceEnabled && allowedToRun) {
            Log.d(TAG, "🔄 Re-showing notification after task removed")

            // Cancel dulu, baru show lagi
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.cancel(NOTIF_ID)

            // Show notif lagi
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showNotif()
            }, 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 onDestroy called")
        cleanupResources()
    }

    // ✅ Called when accessibility service is disabled manually
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "🔌 onUnbind - Accessibility Service Disabled")
        cleanupResources()
        return super.onUnbind(intent)
    }

    private fun cleanupResources() {
        Log.d(TAG, "🧹 Cleaning up resources...")

        isServiceEnabled = false
        instance = null
        serviceScope.cancel()

        getSharedPreferences("bot_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_alive", false)
            .apply()

        sendBroadcast(Intent("com.example.ngontol.BOT_STATUS_CHANGED"))

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancel(NOTIF_ID)

        Log.d(TAG, "✅ Cleanup complete")
    }

    private fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
            .apply { setGravity(Gravity.CENTER, 0, 0) }
            .show()
    }
}
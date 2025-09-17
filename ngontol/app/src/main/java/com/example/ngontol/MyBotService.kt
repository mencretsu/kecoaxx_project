package com.example.ngontol

import kotlinx.coroutines.*
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.util.LinkedHashSet
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.ngontol.firebase.FirebaseManager

@Suppress("DEPRECATION")
class MyBotService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "BOT_CH"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.ngontol.STOP"
        var instance: MyBotService? = null
            private set
        private const val TAG = "BOT"
        private var isServiceEnabled = false
        private var allowedToRun = false

    }

    private val processed = LinkedHashSet<String>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /* -------- lifecycle -------- */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.cancel(NOTIF_ID)

            stopForeground(true)
            stopSelf()
            disableSelf()
            isServiceEnabled = false
            return START_NOT_STICKY
        }

        // 🔥 cek device status sebelum jalan
        checkDeviceStatus()

        return START_STICKY
    }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkDeviceStatus() {
        // device register kalau baru
        FirebaseManager.ensureDeviceInitialized(applicationContext)

        // update lastSeen
        FirebaseManager.updateLastSeen(applicationContext)

        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val db = FirebaseDatabase.getInstance(
            "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"
        )
        val deviceRef = db.getReference("devices").child(deviceId)

        // cek status awal
        deviceRef.child("status").get().addOnSuccessListener { snapshot ->
            val status = snapshot.getValue(String::class.java) ?: "on"
            if (status == "off") {
                allowedToRun = false
                stopSelf()
                Log.w(TAG, "⛔ Device diblokir dari awal, service stop")
            } else {
                allowedToRun = true
                startBotIfNeeded()
            }
        }

        // realtime listener
        deviceRef.child("status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java) ?: "on"
                if (status == "off") {
                    allowedToRun = false
                    stopSelf()
                    Toast.makeText(applicationContext, "⛔ Bot berhenti (status OFF)", Toast.LENGTH_LONG)
                        .apply { setGravity(Gravity.CENTER, 0, 0) }
                        .show()
                    Log.w(TAG, "⛔ Device diblokir realtime, service stop")
                } else {
                    if (!allowedToRun) {
                        allowedToRun = true
                        startBotIfNeeded()
                        Log.d(TAG, "✅ Device dibuka blokirnya, service jalan lagi")
                    }
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
        if (isServiceEnabled) return  // sudah jalan, jangan double

        isServiceEnabled = true
        instance = this

        getSharedPreferences("bot_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_alive", true)
            .apply()

        val pkg = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("selected_package", "") ?: ""

        ensureBotChannel()
        showNotif()

        when (pkg) {
            "com.xxx" -> {
                S1Service.start(this, serviceScope) { isServiceEnabled }
            }
            "com.xyx" -> {
                S2Service.start(this, serviceScope) { isServiceEnabled }
            }
        }

    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        checkDeviceStatus()

        // Jalanin loop scroll
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

        val stopIntent = Intent(this, MyBotService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val namepkg = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("last_app_name", "") ?: ""

        val notif = NotificationCompat.Builder(
            this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL_ID else ""
        )
            .setSmallIcon(R.drawable.ic_bot)
            .setContentTitle("KECOAXX BOT")
            .setContentText("AKTIF • $namepkg")
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "[ Stop Bot ]", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

// Buat channel kalau Android >= O
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bot Service",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }

// Start foreground cuma kalau Android < 33
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startForeground(NOTIF_ID, notif)
        } else {
            // Android 13+ cukup show notif biasa
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, notif)
        }


    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        super.onDestroy()
        instance = null
        serviceScope.cancel()

        getSharedPreferences("bot_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_alive", false)
            .apply()

        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancel(NOTIF_ID)
        stopForeground(true)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
//        Log.d(TAG, "📡 Event masuk: ${event?.eventType}")
        if (!allowedToRun)
            return

        if (!isServiceEnabled) return

        val pkg = getSharedPreferences("bot_prefs", MODE_PRIVATE)
            .getString("last_app_package", "") ?: ""

//        Log.d(TAG, "selected_package = '$pkg'")

        when (pkg) {
            "com.xxx" -> {
                S1Service.onAccessibilityEvent(this, event, serviceScope) { isServiceEnabled }
//                Log.d(TAG, "onAccessibilityEvent: sugo")
            }
            "com.xyx" -> {
                S2Service.onAccessibilityEvent(this, event, serviceScope) { isServiceEnabled }
//                Log.d(TAG, "onAccessibilityEvent: timo")
            }
        }
    }

    private fun ensureBotChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "Bot Channel", NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}

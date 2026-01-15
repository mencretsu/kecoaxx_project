package com.example.ngontol

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.ngontol.auth.UserAuthManager
import com.google.firebase.database.*
import kotlinx.coroutines.tasks.await
import androidx.core.content.edit

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    const val DB_URL = "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"

    private val db by lazy { FirebaseDatabase.getInstance(DB_URL) }

    /**
     * Load semua keys dari assets
     */
    private fun loadKeysFromAssets(context: Context): List<String> {
        return try {
            context.assets.open("api_keys.txt")
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && it.startsWith("AIza") }
                        .toList()
                }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading keys from assets: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch semua keys dari assets dan save ke cache
     */
    suspend fun fetchMaxKeys(context: Context) {
        val prefs = context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

        // Cek local cache dulu
        val cachedKeys = prefs.getStringSet("apiKeyx", emptySet())
        if (!cachedKeys.isNullOrEmpty()) {
            Log.d(TAG, "✅ Menggunakan ${cachedKeys.size} cached keys")
            return
        }

        try {
            // Load semua keys dari assets
            val allKeys = loadKeysFromAssets(context)
            if (allKeys.isEmpty()) {
                Log.e(TAG, "❌ Tidak ada keys! Pastikan api_keys.txt ada di assets/")
                return
            }

            Log.d(TAG, "📦 Loaded ${allKeys.size} keys dari assets")

            // Save semua keys ke local cache
            prefs.edit { putStringSet("apiKeyx", allKeys.toSet()) }
            Log.d(TAG, "✅ Saved ${allKeys.size} keys to cache")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching keys: ${e.message}", e)
        }
    }

    /**
     * Reload keys dari assets (untuk manual refresh)
     */
    suspend fun reloadKeys(context: Context) {
        val prefs = context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

        try {
            val allKeys = loadKeysFromAssets(context)
            if (allKeys.isEmpty()) {
                Log.e(TAG, "❌ Tidak ada keys di assets!")
                return
            }

            // Clear old cache dan save yang baru
            prefs.edit {
                remove("apiKeyx")
                putStringSet("apiKeyx", allKeys.toSet())
            }

            // Clear cooldowns & error counters
            context.getSharedPreferences("key_cooldown", Context.MODE_PRIVATE).edit().clear().apply()
            context.getSharedPreferences("key_errors", Context.MODE_PRIVATE).edit().clear().apply()

            Log.d(TAG, "🔄 Keys reloaded: ${allKeys.size} keys")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reloading keys: ${e.message}", e)
        }
    }

    /**
     * Get device reference di users/{accessCode}/devices/{deviceId}
     */
    private fun getDeviceRef(context: Context): DatabaseReference {
        val accessCode = UserAuthManager.getAccessCode(context) ?: "unknown"
        val deviceId = UserAuthManager.getDeviceId(context)
        return db.getReference("users/$accessCode/devices/$deviceId")
    }

    /**
     * Get user reference di users/{accessCode}
     */
    private fun getUserRef(context: Context): DatabaseReference {
        val accessCode = UserAuthManager.getAccessCode(context) ?: "unknown"
        return db.getReference("users/$accessCode")
    }

    /**
     * Get current battery level
     */
    private fun getBatteryLevel(context: Context): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level != -1 && scale != -1) {
            (level / scale.toFloat() * 100).toInt()
        } else {
            -1
        }
    }

    /**
     * Update last seen
     */
    fun updateLastSeen(context: Context) {
        getDeviceRef(context).child("lastSeen")
            .setValue(System.currentTimeMillis())
    }

    /**
     * Update battery level ke Firebase
     */
    fun updateBatteryLevel(context: Context) {
        val batteryLevel = getBatteryLevel(context)
        if (batteryLevel >= 0) {
            getDeviceRef(context).child("battery").setValue(batteryLevel)
        }
    }

    /**
     * Increment sent counter (device & user level)
     */
    fun incrementSent(context: Context) {
        val accessCode = UserAuthManager.getAccessCode(context) ?: "unknown"
        val deviceRef = getDeviceRef(context)

        // Update device level
        deviceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val total = (currentData.child("totalSent").getValue(Int::class.java) ?: 0) + 1
                val sentToday = (currentData.child("sentToday").getValue(Int::class.java) ?: 0) + 1
                currentData.child("totalSent").value = total
                currentData.child("sentToday").value = sentToday
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    Log.e(TAG, "❌ Gagal update device sent: ${error.message}")
                } else {
                    updateBatteryLevel(context)
                    // Update user level total
                    incrementUserTotalSent(accessCode)

                    // Update sent history
                    updateSentHistory(context)
                }
            }
        })
    }

    /**
     * Update sent history (daily)
     */
    private fun updateSentHistory(context: Context) {
        try {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val accessCode = UserAuthManager.getAccessCode(context) ?: "unknown"
            val deviceId = UserAuthManager.getDeviceId(context)

            val historyRef = db.getReference("sentHistory/$today/$accessCode/$deviceId")

            historyRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val current = currentData.getValue(Int::class.java) ?: 0
                    currentData.value = current + 1
                    return Transaction.success(currentData)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    if (error != null) {
                        Log.e(TAG, "❌ Gagal update history: ${error.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error update sent history: ${e.message}")
        }
    }

    /**
     * Increment total sent di user level
     */
    private fun incrementUserTotalSent(accessCode: String) {
        val userRef = db.getReference("users/$accessCode")
        userRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val total = (currentData.child("totalSent").getValue(Int::class.java) ?: 0) + 1
                currentData.child("totalSent").value = total
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    Log.e(TAG, "❌ Gagal update user totalSent: ${error.message}")
                }
            }
        })
    }

    /**
     * Initialize device
     */
    suspend fun ensureDeviceInitialized(context: Context) {
        val accessCode = UserAuthManager.getAccessCode(context)
        if (accessCode == null) {
            Log.e(TAG, "❌ Access code not found, skip device init")
            return
        }

        val deviceRef = getDeviceRef(context)

        try {
            val deviceSnapshot = deviceRef.get().await()
            val isNew = !deviceSnapshot.exists()

            saveDeviceData(context, deviceRef, isNew)

            if (isNew) {
                updateUserDeviceCount(accessCode)
            }

            Log.d(TAG, "✅ Device initialized/updated for accessCode: $accessCode")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initialize device: ${e.message}", e)
        }
    }

    /**
     * Update total devices count di user level
     */
    private suspend fun updateUserDeviceCount(accessCode: String) {
        try {
            val userRef = db.getReference("users/$accessCode")
            val devicesRef = userRef.child("devices")

            val snapshot = devicesRef.get().await()
            val deviceCount = snapshot.childrenCount.toInt()

            userRef.child("totalDevices").setValue(deviceCount).await()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error update device count: ${e.message}")
        }
    }

    /**
     * Simpan data device
     */
    private suspend fun saveDeviceData(
        context: Context,
        deviceRef: DatabaseReference,
        isNew: Boolean
    ) {
        val persona = PersonaManager.getPersona(context)
        val brand = Build.BRAND
        val model = Build.MODEL
        val sdkInt = Build.VERSION.SDK_INT
        val deviceName = "$brand $model (SDK $sdkInt)"

        var pkgName = persona?.appPackage ?: "-"
        pkgName = when (pkgName) {
            "com.voicemaker.android" -> "sugo"
            "com.fiya.android" -> "sugo lite"
            "com.hwsj.club" -> "timo"
            "com.real.unshow.linky" -> "linky"
            "com.zr.siya" -> "siya"
            "com.toki.android" -> "toki"
            else -> pkgName
        }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        val batteryLevel = getBatteryLevel(context)
        val username = UserAuthManager.getUsername(context) ?: "unknown"
        val deviceId = UserAuthManager.getDeviceId(context)

        val data = mutableMapOf(
            "status" to "online",
            "lastSeen" to System.currentTimeMillis(),
            "deviceName" to deviceName,
            "appVersion" to appVersion,
            "botName" to (persona?.botName ?: "ayang"),
            "pkgName" to pkgName,
            "username" to username,  // Username terakhir
            "deviceId" to deviceId,
            "lastLogin" to System.currentTimeMillis()
        )

        if (batteryLevel >= 0) {
            data["battery"] = batteryLevel
        }

        if (isNew) {
            data["totalSent"] = 0
            data["sentToday"] = 0
            data["firstLogin"] = System.currentTimeMillis()
            data["createdAt"] = System.currentTimeMillis()
            deviceRef.setValue(data).await()
            Log.d(TAG, "✨ Device baru terdaftar: $deviceId (User: $username, Battery: $batteryLevel%)")
        } else {
            deviceRef.updateChildren(data as Map<String, Any>).await()
            Log.d(TAG, "🔄 Device metadata updated: $deviceId (User: $username, Battery: $batteryLevel%)")
        }
    }

    /**
     * Reset daily counter (dipanggil setiap hari)
     */
    suspend fun resetDailyCounters(context: Context) {
        val accessCode = UserAuthManager.getAccessCode(context) ?: return

        try {
            val devicesRef = db.getReference("users/$accessCode/devices")
            val snapshot = devicesRef.get().await()

            val updates = mutableMapOf<String, Any>()
            for (device in snapshot.children) {
                val deviceId = device.key ?: continue
                updates["$deviceId/sentToday"] = 0
            }

            if (updates.isNotEmpty()) {
                devicesRef.updateChildren(updates).await()
                Log.d(TAG, "✅ Reset daily counters for ${updates.size} devices")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error reset daily counters: ${e.message}")
        }
    }

    /**
     * Get device statistics
     */
    suspend fun getDeviceStats(context: Context): DeviceStats? {
        val accessCode = UserAuthManager.getAccessCode(context) ?: return null

        return try {
            val deviceRef = getDeviceRef(context)
            val snapshot = deviceRef.get().await()

            DeviceStats(
                totalSent = snapshot.child("totalSent").getValue(Int::class.java) ?: 0,
                sentToday = snapshot.child("sentToday").getValue(Int::class.java) ?: 0,
                lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L,
                battery = snapshot.child("battery").getValue(Int::class.java) ?: -1,
                status = snapshot.child("status").getValue(String::class.java) ?: "offline"
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error get device stats: ${e.message}")
            null
        }
    }
}

data class DeviceStats(
    val totalSent: Int,
    val sentToday: Int,
    val lastSeen: Long,
    val battery: Int,
    val status: String
)
package com.example.ngontol.firebase

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.ngontol.PersonaManager
import com.google.firebase.database.*
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    const val DB_URL = "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"

    private val db by lazy { FirebaseDatabase.getInstance(DB_URL) }

    /**
     * Fetch SEMUA API keys dari Firebase
     */
    suspend fun fetchMaxKeys(context: Context, maxKeys: Int = 16) {
        val prefs = context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)

        // Cek apakah sudah ada keys di SharedPreferences
        val existingKeys = prefs.getStringSet("apiKey2", emptySet())
        if (!existingKeys.isNullOrEmpty()) {
            Log.d(TAG, "✅ Menggunakan ${existingKeys.size} keys yang sudah ada")
            return
        }

        // Ambil SEMUA keys dari Firebase (gak pake range)
        val keysRef = db.getReference("apiKeys/list")
        val snapshot = keysRef.get().await()

        val allKeys = snapshot.children
            .mapNotNull { it.getValue(String::class.java) }
            .toMutableSet()

        if (allKeys.isEmpty()) {
            Log.w(TAG, "⚠️ Tidak ada key di Firebase")
            return
        }

        prefs.edit().putStringSet("apiKey2", allKeys).apply()
        Log.d(TAG, "✨ Fetched ${allKeys.size} keys dari Firebase (semua keys)")
    }

    private fun getDeviceRef(context: Context): DatabaseReference {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return db.getReference("devices").child(deviceId)
    }

    fun updateLastSeen(context: Context) {
        getDeviceRef(context).child("lastSeen").setValue(System.currentTimeMillis())
    }

    fun incrementSent(context: Context) {
        val deviceRef = getDeviceRef(context)
        deviceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val total = (currentData.child("totalSent").getValue(Int::class.java) ?: 0) + 1
                val sentToday = (currentData.child("sentToday").getValue(Int::class.java) ?: 0) + 1
                currentData.child("totalSent").value = total
                currentData.child("sentToday").value = sentToday
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) Log.e(TAG, "❌ Gagal update sent: ${error.message}")
            }
        })
    }

    /**
     * Initialize device tanpa range (simplified)
     */
    suspend fun ensureDeviceInitialized(context: Context) {
        val deviceRef = getDeviceRef(context)

        try {
            val deviceSnapshot = deviceRef.get().await()

            // Update atau buat baru device data
            saveDeviceData(context, deviceRef, isNew = !deviceSnapshot.exists())
            Log.d(TAG, "✅ Device initialized/updated")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initialize device: ${e.message}", e)
        }
    }

    /**
     * Simpan data device (tanpa range)
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
            else -> pkgName
        }

        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        val data = mutableMapOf(
            "status" to "on",
            "lastSeen" to System.currentTimeMillis(),
            "deviceName" to deviceName,
            "appVersion" to appVersion,
            "botName" to (persona?.botName ?: "ayang"),
            "pkgName" to pkgName
        )

        if (isNew) {
            data["totalSent"] = 0
            data["sentToday"] = 0
            deviceRef.setValue(data).await()
            Log.d(TAG, "✨ Device baru terdaftar")
        } else {
            deviceRef.updateChildren(data as Map<String, Any>).await()
            Log.d(TAG, "🔄 Device metadata updated")
        }
    }
}
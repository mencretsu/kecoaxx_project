package com.example.ngontol.firebase

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.ngontol.PersonaManager
import com.google.firebase.database.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private const val DB_URL =
        "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val disabledKeys = mutableSetOf<String>() // memory cache biar nggak ambil key yg baru disable
    private val db by lazy { FirebaseDatabase.getInstance(DB_URL) }
    // ambil reference ke device node
    private fun getDeviceRef(context: Context): DatabaseReference {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        val db = FirebaseDatabase.getInstance(DB_URL)
        return db.getReference("devices").child(deviceId)
    }


    // update lastSeen
    fun updateLastSeen(context: Context) {
        getDeviceRef(context).child("lastSeen").setValue(System.currentTimeMillis())
    }

    // increment totalSent aman (transaction)

    fun incrementSent(context: Context) {
        val deviceRef = getDeviceRef(context)

        deviceRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                // totalSent
                var total = currentData.child("totalSent").getValue(Int::class.java) ?: 0
                total++
                currentData.child("totalSent").value = total

                // sentToday (reset udah diurus cronjob, jadi langsung increment aja)
                var todaySent = currentData.child("sentToday").getValue(Int::class.java) ?: 0
                todaySent++
                currentData.child("sentToday").value = todaySent

                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?
            ) {
                if (error != null) Log.e(TAG, "❌ Gagal update sent: ${error.message}")
                else Log.d(
                    TAG,
                    "📨 totalSent = ${snapshot?.child("totalSent")?.value}, sentToday = ${snapshot?.child("sentToday")?.value}"
                )
            }
        })
    }

    // set status default kalau device baru
    fun ensureDeviceInitialized(context: Context) {
        val deviceRef = getDeviceRef(context)

        deviceRef.get().addOnSuccessListener { snapshot ->
            val brand = Build.BRAND
            val model = Build.MODEL
            val sdkInt = Build.VERSION.SDK_INT
            val deviceName = "$brand $model (SDK $sdkInt)"
            val persona = PersonaManager.getPersona(context) // context bisa applicationContext
            val botName = persona?.botName ?: "ayang"
            val appVersion = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
            val initData = mapOf(
                "status" to "on",
                "lastSeen" to System.currentTimeMillis(),
                "totalSent" to 0,
                "sentToday" to 0,   // cuma kepake saat device BARU
                "deviceName" to deviceName,
                "appVersion" to appVersion,
                "botName" to botName
            )

            if (!snapshot.exists()) {
                // device baru → set semua field (termasuk sentToday=0)
                deviceRef.setValue(initData)
                Log.d(TAG, "✨ Device baru terdaftar di Firebase")
            } else {
                // device lama → JANGAN update sentToday/totalSent
                val updateData = mapOf(
                    "status" to "on",
                    "lastSeen" to System.currentTimeMillis(),
                    "deviceName" to deviceName,
                    "appVersion" to appVersion,
                    "botName" to botName
                )

                deviceRef.updateChildren(updateData)
                Log.d(TAG, "🔄 Device lama diupdate dengan field tambahan")
            }

        }
    }
    suspend fun getAvailableApiKey(): String? {
        val keysRef = db.getReference("apikeys/api_keys")
        val snapshot = keysRef.get().await()

        for (child in snapshot.children) {
            val key = child.child("key").getValue(String::class.java) ?: continue
            if (key in disabledKeys) continue

            val usedToday = child.child("usedToday").getValue(Int::class.java) ?: 0
            val minuteCount = child.child("minuteCount").getValue(Int::class.java) ?: 0
            val disabled = child.child("disabled").getValue(Boolean::class.java) ?: false
            val cooldownUntil = child.child("cooldownUntil").getValue(Long::class.java) ?: 0
            val now = System.currentTimeMillis()

            if (disabled || now < cooldownUntil) continue
            if (usedToday >= 200 || minuteCount >= 60) continue

            // increment atomik
            val keyRef = child.ref
            val success = keyRef.runTransaction(object : Transaction.Handler {
                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    var today = currentData.child("usedToday").getValue(Int::class.java) ?: 0
                    var minute = currentData.child("minuteCount").getValue(Int::class.java) ?: 0
                    if (today >= 200 || minute >= 60) return Transaction.abort()
                    currentData.child("usedToday").value = today + 1
                    currentData.child("minuteCount").value = minute + 1
                    currentData.child("lastUsed").value = now
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?
                ) {}
            })

            if (success != null) return key
        }

        return null
    }

    suspend fun disableApiKey(apiKey: String) {
        val keysRef = db.getReference("apikeys/api_keys")
        val snapshot = keysRef.orderByChild("key").equalTo(apiKey).get().await()
        for (child in snapshot.children) {
            child.ref.child("disabled").setValue(true).await()
            disabledKeys.add(apiKey)
            Log.w(TAG, "🚫 Key $apiKey disabled permanen")
        }
    }

    suspend fun cooldownApiKey(apiKey: String, seconds: Long = 60) {
        val keysRef = db.getReference("apikeys/api_keys")
        val until = System.currentTimeMillis() + seconds * 1000
        val snapshot = keysRef.orderByChild("key").equalTo(apiKey).get().await()
        for (child in snapshot.children) {
            child.ref.child("cooldownUntil").setValue(until).await()
            Log.w(TAG, "⏳ Key $apiKey cooldown sampai $until")
        }
    }

}

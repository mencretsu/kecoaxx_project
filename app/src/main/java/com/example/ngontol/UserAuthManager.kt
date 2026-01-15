package com.example.ngontol.auth

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.example.ngontol.FirebaseManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object UserAuthManager {
    private const val TAG = "UserAuthManager"
    private const val PREFS_NAME = "user_auth"
    private const val KEY_USERNAME = "username"
    private const val KEY_ACCESS_CODE = "access_code"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_DEVICE_ID = "device_id"

    private val db by lazy { FirebaseDatabase.getInstance(FirebaseManager.DB_URL) }

    /**
     * Check apakah user sudah login
     */
    fun isLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LOGGED_IN, false)
    }

    /**
     * Get username yang tersimpan (display name)
     */
    fun getUsername(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, null)
    }

    /**
     * Get access code yang tersimpan (primary key)
     */
    fun getAccessCode(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACCESS_CODE, null)
    }

    /**
     * Get stored device ID
     */
    fun getStoredDeviceId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null)
    }

    /**
     * Login with access code (username bebas, yang terakhir diinput jadi display name)
     */
    suspend fun authenticate(
        context: Context,
        username: String,
        accessCode: String
    ): AuthResult {
        if (username.isBlank() || accessCode.isBlank()) {
            return AuthResult.Error("Username & kode akses tidak boleh kosong")
        }

        // Sanitize access code (hapus spasi, uppercase)
        val sanitizedCode = accessCode.trim().replace(" ", "")
        val sanitizedUsername = username.trim()

        try {
            val authRef = db.getReference("auth/$sanitizedCode")
            val snapshot = authRef.get().await()

            return if (snapshot.exists()) {
                // Access code valid
                saveUserLocally(context, sanitizedUsername, sanitizedCode)

                // Update auth dengan username terbaru
                updateAuthData(sanitizedCode, sanitizedUsername)

                // Init/update user structure dengan username terbaru
                initUserDeviceStructure(context, sanitizedCode, sanitizedUsername)

                // Register device dengan username terbaru
                registerDevice(context, sanitizedCode, sanitizedUsername)

                // Update username di semua devices yang pakai access code ini
                updateUsernameInAllDevices(sanitizedCode, sanitizedUsername)

                AuthResult.Success(sanitizedUsername, sanitizedCode)
            } else {
                AuthResult.Error("❌ Kode akses tidak valid!")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Auth error: ${e.message}", e)
            return AuthResult.Error("Error: ${e.message}")
        }
    }

    /**
     * Update auth data (currentUsername & lastLogin)
     */
    private suspend fun updateAuthData(accessCode: String, username: String) {
        try {
            val authRef = db.getReference("auth/$accessCode")
            val updates = mapOf(
                "currentUsername" to username,
                "lastLogin" to System.currentTimeMillis()
            )

            // Check if createdAt exists
            val snapshot = authRef.get().await()
            if (!snapshot.child("createdAt").exists()) {
                authRef.child("createdAt").setValue(System.currentTimeMillis()).await()
            }

            authRef.updateChildren(updates).await()
            Log.d(TAG, "✅ Auth updated: $accessCode → $username")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error update auth: ${e.message}")
        }
    }

    /**
     * Update username di semua devices yang menggunakan access code ini
     */
    private suspend fun updateUsernameInAllDevices(accessCode: String, username: String) {
        try {
            val devicesRef = db.getReference("users/$accessCode/devices")
            val snapshot = devicesRef.get().await()

            val updates = mutableMapOf<String, Any>()
            for (device in snapshot.children) {
                val deviceId = device.key ?: continue
                updates["$deviceId/username"] = username
                updates["$deviceId/lastLogin"] = System.currentTimeMillis()
            }

            if (updates.isNotEmpty()) {
                devicesRef.updateChildren(updates).await()
                Log.d(TAG, "✅ Updated username in ${updates.size/2} devices")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error update username in devices: ${e.message}")
        }
    }

    /**
     * Init struktur users/{accessCode}/
     */
    private suspend fun initUserDeviceStructure(
        context: Context,
        accessCode: String,
        username: String
    ) {
        val userRef = db.getReference("users/$accessCode")

        try {
            val snapshot = userRef.get().await()

            if (!snapshot.exists()) {
                // Init user node dengan default values
                userRef.setValue(mapOf(
                    "displayName" to username,
                    "totalDevices" to 0,
                    "totalSent" to 0,
                    "devices" to emptyMap<String, Any>(),
                    "createdAt" to System.currentTimeMillis()
                )).await()
                Log.d(TAG, "✅ User structure created: $accessCode with username: $username")
            } else {
                // Update display name dengan username terbaru
                val currentName = snapshot.child("displayName").getValue(String::class.java)
                if (currentName != username) {
                    userRef.child("displayName").setValue(username).await()
                    Log.d(TAG, "✅ Display name updated: $currentName → $username")
                } else {
                    Log.d(TAG, "ℹ️ Display name unchanged: $username")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error init user structure: ${e.message}")
        }
    }

    /**
     * REGISTER DEVICE KE users/{accessCode}/devices/{deviceId}
     */
    private suspend fun registerDevice(
        context: Context,
        accessCode: String,
        username: String
    ) {
        val deviceId = getDeviceId(context)
        val deviceRef = db.getReference("users/$accessCode/devices/$deviceId")

        try {
            val snapshot = deviceRef.get().await()
            val now = System.currentTimeMillis()

            if (!snapshot.exists()) {
                // Device baru - CREATE
                val deviceData = mapOf(
                    "deviceId" to deviceId,
                    "deviceName" to getDeviceName(),
                    "username" to username,
                    "firstLogin" to now,
                    "lastLogin" to now,
                    "totalSent" to 0,
                    "sentToday" to 0,
                    "status" to "active",
                    "createdAt" to now
                )
                deviceRef.setValue(deviceData).await()

                // INCREMENT totalDevices
                val userRef = db.getReference("users/$accessCode")
                val totalDevices = userRef.child("totalDevices").get().await()
                    .getValue(Int::class.java) ?: 0
                userRef.child("totalDevices").setValue(totalDevices + 1).await()

                Log.d(TAG, "✅ New device registered: $deviceId ($username)")
            } else {
                // Device sudah ada - UPDATE dengan username terbaru
                val updates = mapOf(
                    "username" to username,
                    "lastLogin" to now,
                    "status" to "active"
                )
                deviceRef.updateChildren(updates).await()

                Log.d(TAG, "✅ Device updated: $deviceId with username: $username")
            }

            // Save device ID locally
            saveDeviceIdLocally(context, deviceId)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error register device: ${e.message}")
        }
    }

    /**
     * Get device name (brand + model)
     */
    private fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
            .replace("unknown", "Device")
            .trim()
    }

    /**
     * Save user data ke local storage
     */
    private fun saveUserLocally(context: Context, username: String, accessCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_USERNAME, username)
            putString(KEY_ACCESS_CODE, accessCode)
            putBoolean(KEY_LOGGED_IN, true)
        }
    }

    /**
     * Save device ID locally
     */
    private fun saveDeviceIdLocally(context: Context, deviceId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_DEVICE_ID, deviceId)
        }
    }

    /**
     * Logout user
     */
    fun logout(context: Context) {
        // Update device status to offline sebelum logout
        val accessCode = getAccessCode(context)
        val deviceId = getStoredDeviceId(context)

        if (accessCode != null && deviceId != null) {
            try {
                val deviceRef = db.getReference("users/$accessCode/devices/$deviceId")
                deviceRef.child("status").setValue("offline")
                deviceRef.child("lastSeen").setValue(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating device status on logout: ${e.message}")
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            clear()
        }
        Log.d(TAG, "🔴 User logged out")
    }

    /**
     * Verify apakah device ini milik access code tertentu
     */
    suspend fun verifyDeviceOwnership(context: Context, accessCode: String): Boolean {
        val deviceId = getDeviceId(context)
        return suspendCoroutine { continuation ->
            val ref = db.getReference("users/$accessCode/devices/$deviceId")
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    continuation.resume(snapshot.exists())
                }
                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(false)
                }
            })
        }
    }

    /**
     * Get semua devices yang terdaftar dengan access code ini
     */
    suspend fun getUserDevices(context: Context): List<DeviceInfo>? {
        val accessCode = getAccessCode(context) ?: return null

        return try {
            val devicesRef = db.getReference("users/$accessCode/devices")
            val snapshot = devicesRef.get().await()

            val devices = mutableListOf<DeviceInfo>()
            for (child in snapshot.children) {
                val device = DeviceInfo(
                    deviceId = child.child("deviceId").getValue(String::class.java) ?: "",
                    deviceName = child.child("deviceName").getValue(String::class.java) ?: "",
                    username = child.child("username").getValue(String::class.java) ?: "",
                    firstLogin = child.child("firstLogin").getValue(Long::class.java) ?: 0L,
                    lastLogin = child.child("lastLogin").getValue(Long::class.java) ?: 0L,
                    totalSent = child.child("totalSent").getValue(Int::class.java) ?: 0,
                    status = child.child("status").getValue(String::class.java) ?: "offline",
                    battery = child.child("battery").getValue(Int::class.java) ?: -1
                )
                devices.add(device)
            }

            devices
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error get devices: ${e.message}")
            null
        }
    }

    /**
     * Get user info (total devices, total sent)
     */
    suspend fun getUserInfo(context: Context): UserInfo? {
        val accessCode = getAccessCode(context) ?: return null

        return try {
            val userRef = db.getReference("users/$accessCode")
            val snapshot = userRef.get().await()

            UserInfo(
                displayName = snapshot.child("displayName").getValue(String::class.java) ?: "",
                totalDevices = snapshot.child("totalDevices").getValue(Int::class.java) ?: 0,
                totalSent = snapshot.child("totalSent").getValue(Int::class.java) ?: 0,
                accessCode = accessCode,
                createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error get user info: ${e.message}")
            null
        }
    }

    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    /**
     * Check jika device ini sudah terdaftar dengan access code lain
     */
    suspend fun isDeviceRegisteredWithOtherCode(context: Context): Boolean {
        val deviceId = getDeviceId(context)
        return suspendCoroutine { continuation ->
            val usersRef = db.getReference("users")
            usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (user in snapshot.children) {
                        val devices = user.child("devices")
                        if (devices.hasChild(deviceId)) {
                            continuation.resume(true)
                            return
                        }
                    }
                    continuation.resume(false)
                }
                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(false)
                }
            })
        }
    }
}

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val username: String,
    val firstLogin: Long,
    val lastLogin: Long,
    val totalSent: Int,
    val status: String,
    val battery: Int
)

data class UserInfo(
    val displayName: String,
    val totalDevices: Int,
    val totalSent: Int,
    val accessCode: String,
    val createdAt: Long
)

sealed class AuthResult {
    data class Success(val username: String, val accessCode: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}
package com.example.ngontol.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale

object LicenseManager {
    private const val PREF_NAME = "license_prefs"
    private const val KEY_LICENSE = "license_key"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_EXPIRY = "expiry_date"
    private const val KEY_USER_NAME = "user_name"

    private const val DB_URL = "https://kecoaxx-db898-default-rtdb.asia-southeast1.firebasedatabase.app"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Aktivasi lisensi dengan key
     */
    suspend fun activateLicense(
        context: Context,
        licenseKey: String,
        callback: (Boolean, String) -> Unit
    ) {
        try {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )

            val db = FirebaseDatabase.getInstance(DB_URL)
            val licenseRef = db.getReference("licenses").child(licenseKey)

            val snapshot = licenseRef.get().await()

            if (!snapshot.exists()) {
                callback(false, "❌ Kode lisensi tidak valid")
                return
            }

            val status = snapshot.child("status").getValue(String::class.java)
            val boundDevice = snapshot.child("deviceId").getValue(String::class.java)
            val expiryStr = snapshot.child("expiryDate").getValue(String::class.java)
            val userName = snapshot.child("userName").getValue(String::class.java) ?: "User"

            // Cek status
            if (status != "active") {
                callback(false, "❌ Lisensi sudah dinonaktifkan")
                return
            }

            // Cek device binding
            if (!boundDevice.isNullOrEmpty() && boundDevice != deviceId) {
                callback(false, "❌ Lisensi sudah terikat dengan device lain")
                return
            }

            // Cek expiry
            if (!expiryStr.isNullOrEmpty()) {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val expiryDate = format.parse(expiryStr)
                if (expiryDate != null && expiryDate.before(Date())) {
                    callback(false, "❌ Lisensi sudah kadaluarsa")
                    return
                }
            }

            // Bind device jika belum
            if (boundDevice.isNullOrEmpty()) {
                licenseRef.child("deviceId").setValue(deviceId).await()
                licenseRef.child("activatedAt").setValue(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                ).await()
            }

            // Update last used
            licenseRef.child("lastUsed").setValue(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            ).await()

            // Simpan ke local
            getPrefs(context).edit().apply {
                putString(KEY_LICENSE, licenseKey)
                putString(KEY_DEVICE_ID, deviceId)
                putString(KEY_EXPIRY, expiryStr)
                putString(KEY_USER_NAME, userName)
                apply()
            }

            callback(true, "✅ Lisensi aktif! Selamat datang $userName")

        } catch (e: Exception) {
            Log.e("LicenseManager", "Error aktivasi: ${e.message}", e)
            callback(false, "❌ Gagal aktivasi: ${e.message}")
        }
    }

    /**
     * Cek apakah lisensi masih valid
     */
    suspend fun validateLicense(context: Context): Boolean {
        try {
            val prefs = getPrefs(context)
            val licenseKey = prefs.getString(KEY_LICENSE, null) ?: return false
            val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return false

            val currentDeviceId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )

            // Cek device ID match
            if (deviceId != currentDeviceId) return false

            // Cek expiry local
            val expiryStr = prefs.getString(KEY_EXPIRY, null)
            if (!expiryStr.isNullOrEmpty()) {
                val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val expiryDate = format.parse(expiryStr)
                if (expiryDate != null && expiryDate.before(Date())) {
                    return false
                }
            }

            // Validate dengan Firebase
            val db = FirebaseDatabase.getInstance(DB_URL)
            val snapshot = db.getReference("licenses").child(licenseKey).get().await()

            if (!snapshot.exists()) return false

            val status = snapshot.child("status").getValue(String::class.java)
            return status == "active"

        } catch (e: Exception) {
            Log.e("LicenseManager", "Error validasi: ${e.message}", e)
            return false
        }
    }

    /**
     * Get license info
     */
    fun getLicenseInfo(context: Context): LicenseInfo? {
        val prefs = getPrefs(context)
        val key = prefs.getString(KEY_LICENSE, null) ?: return null
        val expiry = prefs.getString(KEY_EXPIRY, null)
        val userName = prefs.getString(KEY_USER_NAME, "User") ?: "User"

        return LicenseInfo(key, userName, expiry)
    }

    /**
     * Logout / hapus lisensi local
     */
    fun logout(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    data class LicenseInfo(
        val key: String,
        val userName: String,
        val expiryDate: String?
    )
}
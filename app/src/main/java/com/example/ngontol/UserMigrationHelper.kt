package com.example.ngontol

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * Helper untuk migrate dari old User system ke new Access Code system
 */
object UserMigrationHelper {
    private const val TAG = "UserMigration"

    /**
     * Check & migrate old user data
     * Return access code if found, null if not
     */
    fun migrateOldUserData(context: Context): String? {
        // Check berbagai kemungkinan SharedPreferences name
        val possiblePrefsNames = listOf(
            "user_prefs",
            "user_data",
            "auth_prefs",
            "app_prefs",
            "login_prefs"
        )

        for (prefsName in possiblePrefsNames) {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

            // Try to find user data in various keys
            val possibleKeys = listOf(
                "user_data",
                "user",
                "current_user",
                "logged_user"
            )

            for (key in possibleKeys) {
                val json = prefs.getString(key, null)
                if (!json.isNullOrBlank()) {
                    try {
                        val gson = Gson()
                        val user = gson.fromJson(json, User::class.java)

                        if (user != null && user.password.isNotBlank()) {
                            Log.d(TAG, "✅ Found old user data: ${user.username}")
                            Log.d(TAG, "   Using password as access code: ${user.password}")

                            // Use password as access code
                            return user.password
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing user JSON: ${e.message}")
                    }
                }
            }

            // Also check for direct password field
            val directPassword = prefs.getString("password", null)
            if (!directPassword.isNullOrBlank()) {
                Log.d(TAG, "✅ Found direct password field")
                return directPassword
            }
        }

        Log.d(TAG, "⚠️ No old user data found")
        return null
    }

    /**
     * Check if old user system exists
     */
    fun hasOldUserData(context: Context): Boolean {
        return migrateOldUserData(context) != null
    }

    /**
     * Clear all old user data after successful migration
     */
    fun clearOldUserData(context: Context) {
        val prefsNames = listOf(
            "user_prefs",
            "user_data",
            "auth_prefs",
            "app_prefs",
            "login_prefs"
        )

        for (name in prefsNames) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }

        Log.d(TAG, "🗑️ Old user data cleared")
    }

    /**
     * Auto-migrate: detect old system and auto-login with access code
     */
    suspend fun autoMigrate(context: Context): MigrationResult {
        val oldAccessCode = migrateOldUserData(context)

        if (oldAccessCode.isNullOrBlank()) {
            return MigrationResult.NoOldData
        }

        // Try to authenticate with old password as access code
        val result = com.example.ngontol.auth.UserAuthManager.authenticate(
            context,
            "migrated_user", // default username
            oldAccessCode
        )

        return when (result) {
            is com.example.ngontol.auth.AuthResult.Success -> {
                clearOldUserData(context)
                MigrationResult.Success(oldAccessCode)
            }
            is com.example.ngontol.auth.AuthResult.Error -> {
                MigrationResult.Failed(result.message)
            }
        }
    }
}

sealed class MigrationResult {
    object NoOldData : MigrationResult()
    data class Success(val accessCode: String) : MigrationResult()
    data class Failed(val error: String) : MigrationResult()
}
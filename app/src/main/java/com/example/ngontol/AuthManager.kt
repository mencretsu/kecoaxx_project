package com.example.ngontol.auth

import android.content.Context
import androidx.core.content.edit
import com.example.ngontol.User

object AuthManager {

    private const val PREFS = "auth_prefs"
    private const val KEY_PHONE = "phone"
    private const val KEY_PASSWORD = "password"
    private const val KEY_BALANCE = "balance"

    fun saveUser(context: Context, user: User) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_PHONE, user.phone)
            putString(KEY_PASSWORD, user.password)
            putInt(KEY_BALANCE, user.tokenBalance)
        }
    }

    fun getSavedUser(context: Context): User? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val phone = prefs.getString(KEY_PHONE, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        val balance = prefs.getInt(KEY_BALANCE, 0)

        return User(
            id = phone,
            username = phone,
            phone = phone,
            password = password,   // ✅ sekarang ada
            tokenBalance = balance
        )
    }

    fun updateToken(context: Context, phone: String, amount: Int) {
        val user = getSavedUser(context) ?: return
        if (user.phone != phone) return
        val updated = user.copy(tokenBalance = user.tokenBalance + amount)
        saveUser(context, updated)
    }

    fun clearUser(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { clear() }
    }

    suspend fun loginOrRegister(
        context: Context,
        phone: String,
        password: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedPhone = prefs.getString(KEY_PHONE, null)
        val savedPass = prefs.getString(KEY_PASSWORD, null)

        if (savedPhone == phone && savedPass == password) {
            callback(true, "Login sukses")
        } else if (savedPhone == phone && savedPass != password) {
            callback(false, "Password salah ❌")
        } else {
            // auto-register
            val user = User(
                id = phone,
                username = phone,
                phone = phone,
                password = password,   // ✅ isi password
                tokenBalance = 0
            )
            saveUser(context, user)
            callback(true, "Registrasi sukses ✅")
        }
    }
    fun resetPassword(context: Context, phone: String, newPassword: String): Boolean {
        val user = getSavedUser(context) ?: return false
        return if (user.phone == phone) {
            val updated = user.copy(password = newPassword)
            saveUser(context, updated)
            true
        } else {
            false
        }
    }


}

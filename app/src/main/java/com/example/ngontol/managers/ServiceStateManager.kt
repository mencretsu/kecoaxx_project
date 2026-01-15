package com.example.ngontol.managers

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.ngontol.utils.BotConstants

class ServiceStateManager {
    private val TAG = "StateManager"
    var lastRelogCheck = System.currentTimeMillis()  // ✅ Timer terpisah untuk relog
    var lastRun = 0L
    var lastCacheClear = System.currentTimeMillis()
    var lastRestartTime = 0L
    var lastRestartComplete = 0L
    var lastAppLaunchTime = 0L  // ✅ Track last launch time
    var interval = BotConstants.DEFAULT_INTERVAL
    val processed = LinkedHashSet<String>()

    var isHandlingChat = false
    var needRestart = false
    var isLaunching = false  // ✅ Prevent double launch
    @Volatile
    var lastEventProcessTime: Long = 0L
    fun shouldSkipRestart(now: Long): Boolean {
        val timeSinceLastRestart = now - lastRestartTime
        val should = lastRestartTime > 0 && timeSinceLastRestart < BotConstants.MIN_RESTART_INTERVAL

        return should
    }

    fun shouldSkipPostRestart(now: Long): Boolean {
        val timeSinceRestartComplete = now - lastRestartComplete
        val should = lastRestartComplete > 0 && timeSinceRestartComplete < BotConstants.POST_RESTART_COOLDOWN

        return should
    }

    fun shouldClearCache(): Boolean {
        val now = System.currentTimeMillis()
        val timeSince = now - lastCacheClear
        return timeSince >= 30000L  // Auto clear setiap 30 detik
    }
    fun shouldRelog(): Boolean {
        val now = System.currentTimeMillis()
        val timeSince = now - lastRelogCheck
        val should = timeSince >= BotConstants.CACHE_CLEAR_INTERVAL

        return should
    }
    fun shouldProcess(now: Long): Boolean {
        return now - lastRun >= interval
    }

    fun updateLastRun(now: Long) {
        lastRun = now
    }
    fun clearCache() {
        processed.clear()
        lastCacheClear = System.currentTimeMillis()
//        Log.d(TAG, "🧹 Cache cleared")
    }
    @SuppressLint("UseKtx")
    fun reset(service: AccessibilityService) {
        Log.d(TAG, "🔄 State reset called")
        lastCacheClear = System.currentTimeMillis()
        lastRelogCheck = System.currentTimeMillis()  // ✅ Reset relog timer juga
        lastRestartComplete = 0L
        processed.clear()
        isHandlingChat = false
        needRestart = false

        service.getSharedPreferences(BotConstants.PREF_NAME_CACHE, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun addProcessed(key: String) {
        processed.add(key)
        if (processed.size > BotConstants.MAX_CACHE) {
            processed.remove(processed.first())
        }
    }

    @SuppressLint("UseKtx")
    fun saveProcessed(service: AccessibilityService) {
        service.getSharedPreferences(BotConstants.PREF_NAME_CACHE, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(BotConstants.PREF_KEY_PROCESSED, processed)
            .apply()
    }
}
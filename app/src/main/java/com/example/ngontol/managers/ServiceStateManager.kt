package com.example.ngontol.managers

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.ngontol.utils.BotConstants

class ServiceStateManager {
    private val TAG = "StateManager"

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

    fun shouldSkipRestart(now: Long): Boolean {
        val timeSinceLastRestart = now - lastRestartTime
        val should = lastRestartTime > 0 && timeSinceLastRestart < BotConstants.MIN_RESTART_INTERVAL

        if (should) {
            val remaining = (BotConstants.MIN_RESTART_INTERVAL - timeSinceLastRestart) / 1000
            Log.d(TAG, "⏸️ Restart cooldown: ${remaining}s remaining")
        }

        return should
    }

    fun shouldSkipPostRestart(now: Long): Boolean {
        val timeSinceRestartComplete = now - lastRestartComplete
        val should = lastRestartComplete > 0 && timeSinceRestartComplete < BotConstants.POST_RESTART_COOLDOWN

        if (should) {
            val remaining = (BotConstants.POST_RESTART_COOLDOWN - timeSinceRestartComplete) / 1000
            Log.d(TAG, "⏸️ Post-restart cooldown: ${remaining}s remaining")
        }

        return should
    }

    fun shouldClearCache(): Boolean {
        val now = System.currentTimeMillis()
        val timeSince = now - lastCacheClear
        val should = timeSince >= BotConstants.CACHE_CLEAR_INTERVAL

        if (should) {
            val minutes = timeSince / 1000 / 60
            Log.d(TAG, "🔄 Should clear cache: ${minutes} minutes since last clear")
        }

        return should
    }

    fun shouldProcess(now: Long): Boolean {
        return now - lastRun >= interval
    }

    fun updateLastRun(now: Long) {
        lastRun = now
    }

    @SuppressLint("UseKtx")
    fun reset(service: AccessibilityService) {
        Log.d(TAG, "🔄 State reset called")
        lastCacheClear = System.currentTimeMillis()
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
package com.example.ngontol

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.annotation.RequiresApi

class KeepAwakeActivity : Activity() {
    companion object { private const val TAG = "KeepAwakeActivity" }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            // Show over lockscreen + turn screen on (modern APIs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                window.addFlags(
                    LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }

            // Keep screen on while visible
            window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON or LayoutParams.FLAG_DISMISS_KEYGUARD)

            // Try to dismiss keyguard with callback (will succeed only if OS allows)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, @RequiresApi(Build.VERSION_CODES.O)
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.d(TAG, "dismiss keyguard: succeeded")
                }
                override fun onDismissError() {
                    Log.d(TAG, "dismiss keyguard: error")
                }
                override fun onDismissCancelled() {
                    Log.d(TAG, "dismiss keyguard: cancelled")
                }
            })

            // Force window brightness to max for this Activity (doesn't need WRITE_SETTINGS)
            val attrs = window.attributes
            attrs.screenBrightness = 1.0f // 0..1.0
            window.attributes = attrs

            // Optional: set a tiny view so Activity stays visible
            val view = android.view.View(this)
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContentView(view)

            Log.d(TAG, "KeepAwakeActivity created: flags set, brightness forced")
        } catch (t: Throwable) {
            Log.e(TAG, "KeepAwakeActivity onCreate error: ${t.message}")
            finish()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("finish_keep_awake", false) == true) {
            Log.d(TAG, "KeepAwakeActivity finishing on request.")
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "KeepAwakeActivity destroyed.")
    }
}
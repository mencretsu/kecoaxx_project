// MessageSender.kt - FIXED
package com.example.ngontol.helpers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ngontol.BotKeyboard
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.models.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.io.path.Path

object MessageSender {
    private const val TAG = "MessageSender"

    // Di MessageSender.kt - tambahkan function yang optimized
    suspend fun send(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String,
        config: AppConfig
    ) = withContext(Dispatchers.IO) {  // ← PASTIKAN di background!

        // HAPUS SEMUA LOG DARI SINI!

        val editText = if (input.className == "android.widget.EditText") {
            input
        } else {
            (0 until input.childCount).mapNotNull { input.getChild(it) }
                .firstOrNull { it.className == "android.widget.EditText" && it.isClickable }
                ?: input
        }

        // Simple click tanpa log
//        try {
//            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//        } catch (e: Exception) { }

        delay(100)

        // Paste text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        try {
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            return@withContext
        }

        // Delay untuk paste
        val pasteDelay = when {
            text.length <= 50 -> 200L
            text.length <= 100 -> 300L
            else -> 400L
        }

        delay(pasteDelay)

        // Cari send button dan click
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        val sendButton = root?.findAccessibilityNodeInfosByViewId(config.sendViewId)
            ?.firstOrNull { it.isClickable && !WindowFilterHelper.isOwnOverlay(it) }

        if (sendButton != null) {
            try {
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                // Fallback ke enter
                try {
                    BotKeyboard.instance?.pressEnter()
                } catch (e2: Exception) { }
            }
        } else {
            // Fallback ke enter
            try {
                BotKeyboard.instance?.pressEnter()
            } catch (e: Exception) { }
        }

        delay(100)
    }

    suspend fun sendWithPasteGesture(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String,
        config: AppConfig
    ) {
        delay(600)

        val editText = if (input.className == "android.widget.EditText") {
            input
        } else {
            (0 until input.childCount).mapNotNull { input.getChild(it) }
                .firstOrNull { it.className == "android.widget.EditText" && it.isClickable }
                ?: input
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Log.d(TAG, "✅ Text pasted: ${text.take(50)}...")

        val delayTime = when {
            text.length <= 30 -> (400..600).random().toLong()
            text.length <= 100 -> (600..900).random().toLong()
            else -> (800..1200).random().toLong()
        }
        delay(delayTime)

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        val sendBtn = root?.findAccessibilityNodeInfosByViewId(config.sendViewId)
            ?.firstOrNull { !WindowFilterHelper.isOwnOverlay(it) }

        if (sendBtn == null) {
            Log.e(TAG, "❌ Send button not found after paste!")
            return
        }

        val sendClicked = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (sendClicked) {
            Log.d(TAG, "✅ Message sent with paste + click send button")
        } else {
            Log.e(TAG, "❌ Failed to click send button")
        }
    }

    suspend fun sendWithPasteAndEnter(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String
    ) {
        delay(600)

        val editText = if (input.className == "android.widget.EditText") {
            input
        } else {
            (0 until input.childCount).mapNotNull { input.getChild(it) }
                .firstOrNull { it.className == "android.widget.EditText" && it.isClickable }
                ?: input
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Log.d(TAG, "✅ Text pasted: ${text.take(50)}...")

        val delayTime = when {
            text.length <= 30 -> (400..600).random().toLong()
            text.length <= 100 -> (600..900).random().toLong()
            else -> (800..1200).random().toLong()
        }
        delay(delayTime)

        Log.d(TAG, "⏎ Pressing Enter to send...")
        BotKeyboard.instance?.pressEnter()

        Log.d(TAG, "✅ Message sent with paste + enter")
    }

    suspend fun sendWithGesture(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String,
        config: AppConfig
    ) {
        delay(500)

        val editText = if (input.className == "android.widget.EditText") {
            input
        } else {
            (0 until input.childCount).mapNotNull { input.getChild(it) }
                .firstOrNull { it.className == "android.widget.EditText" && it.isClickable }
                ?: input
        }

        val clicked = editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            Log.w(TAG, "⚠️ Click input failed, trying focus...")
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        BotKeyboard.instance?.typeText(text)

        delay(500)

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        val sendBtn = root?.findAccessibilityNodeInfosByViewId(config.sendViewId)
            ?.firstOrNull { !WindowFilterHelper.isOwnOverlay(it) }

        if (sendBtn == null) {
            Log.e(TAG, "❌ Send button not found after typing!")
            return
        }

        val sendClicked = sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (sendClicked) {
            Log.d(TAG, "✅ Message sent")
        } else {
            Log.e(TAG, "❌ Failed to click send button")
        }
    }

    // FIXED: Paste + Enter dengan timing yang bener
    suspend fun sendWithEnter(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String
    ) = withContext(Dispatchers.IO) {  // ← INI YG PALING KRITIS!

        // HAPUS SEMUA LOG DARI SINI!
        // Log.d(TAG, "📝 Starting sendWithEnter...") ← HAPUS!

        val editText = if (input.className == "android.widget.EditText") {
            input
        } else {
            (0 until input.childCount).mapNotNull { input.getChild(it) }
                .firstOrNull { it.className == "android.widget.EditText" && it.isClickable }
                ?: input
        }

        // Simple click tanpa log
        try {
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) { /* ignore */ }

        delay(100)

        // Paste text tanpa log
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        try {
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            return@withContext
        }

        // Delay lebih pendek
        val pasteDelay = when {
            text.length <= 50 -> 200L
            text.length <= 100 -> 300L
            else -> 400L
        }

        delay(pasteDelay)

        // Press enter
        try {
            BotKeyboard.instance?.pressEnter()
        } catch (e: Exception) {
            return@withContext
        }

        delay(100)
    }
    suspend fun sendWithKeyboardEnter(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String
    ) {
        delay(1000)

        val editText = if (input.className == "android.widget.EditText") {
            input
        } else {
            (0 until input.childCount).mapNotNull { input.getChild(it) }
                .firstOrNull { it.className == "android.widget.EditText" && it.isClickable }
                ?: input
        }

        val clicked = editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            Log.w(TAG, "⚠️ Click input failed, trying focus...")
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        delay(500)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        Log.d(TAG, "✅ Text pasted: ${text.take(50)}...")

        val delayTime = when {
            text.length <= 30 -> (400..600).random().toLong()
            text.length <= 100 -> (600..900).random().toLong()
            else -> (800..1200).random().toLong()
        }

        Log.d(TAG, "⏱️ Waiting ${delayTime}ms before pressing enter...")
        delay(delayTime)

        Log.d(TAG, "⏎ Pressing enter...")
        BotKeyboard.instance?.pressEnter()

        Log.d(TAG, "✅ Message sent with paste + enter key")
    }
    // ✅ Versi lebih cepat tanpa typing delay panjang (untuk automation)
    suspend fun sendWithGestureFast(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String,
        config: AppConfig
    ) {
        Log.d(TAG, "=== START SEND WITH GESTURE (FAST) ===")

        // Delay lebih pendek untuk automation
        val typingDelay = when {
            text.length <= 30 -> 300L
            text.length <= 100 -> 500L
            else -> 700L
        }

        // ✅ Click input field dengan gesture
        val rect = Rect().apply { input.getBoundsInScreen(this) }
        val path = android.graphics.Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        try {
            service.dispatchGesture(gesture, null, null)
//            Log.d(TAG, "✅ Input field clicked via gesture")
        } catch (t: Throwable) {
//            Log.w(TAG, "⚠️ Click input failed: ${t.message}")
            input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        delay(200L)

        // ✅ Type text via custom keyboard
//        Log.d(TAG, "⌨️ Typing text: ${text.take(50)}...")
        BotKeyboard.instance?.typeText(text)

        delay(typingDelay)

        // ✅ Click send button dengan gesture
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        val sendBtn = root?.findAccessibilityNodeInfosByViewId(config.sendViewId)
            ?.firstOrNull { !WindowFilterHelper.isOwnOverlay(it) && it.isClickable }

        if (sendBtn != null) {
            val sendRect = Rect().apply { sendBtn.getBoundsInScreen(this) }
            val sendPath = android.graphics.Path().apply {
                moveTo(sendRect.centerX().toFloat(), sendRect.centerY().toFloat())
            }

            val sendGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(sendPath, 0, 100))
                .build()

            try {
                service.dispatchGesture(sendGesture, null, null)
                Log.d(TAG, "✅ Send button clicked via gesture")
            } catch (t: Throwable) {
                sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } else {
            BotKeyboard.instance?.pressEnter()
        }

        Log.d(TAG, "=== MESSAGE SENT (FAST) ===")
    }
}
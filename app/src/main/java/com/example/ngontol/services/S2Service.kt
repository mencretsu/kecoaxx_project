package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.ngontol.BotKeyboard
import com.example.ngontol.PersonaManager
import com.example.ngontol.Persona
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.config.AppConfigs
import com.example.ngontol.helpers.NodeFinder
import com.example.ngontol.managers.ChatHistoryManager
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.processors.OpenerSelector
import com.example.ngontol.processors.ReplyGenerator
import com.example.ngontol.utils.sanitize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object S2Service : BaseAppService(AppConfigs.MOMO) {

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S2Service started")
        onAccessibilityEvent(service, scope, isRunning)
    }

    override fun extractChatMessage(
        row: AccessibilityNodeInfo,
        service: AccessibilityService
    ): ChatMessage? {
        val rect = Rect().apply { row.getBoundsInScreen(this) }
        if (rect.width() <= 0 || rect.height() <= 0) return null

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return null
        val titleNode = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/title")
            ?.firstOrNull { node ->
                if (WindowFilterHelper.isOwnOverlay(node, service.packageName)) return@firstOrNull false
                val rectTitle = Rect().apply { node.getBoundsInScreen(this) }
                rect.contains(rectTitle)
            }

        val name = titleNode?.text?.toString().orEmpty()
        if (name.isBlank()) return null

        return ChatMessage(name, "", 1)
    }

    // ✅ OVERRIDE handleCancelButtons untuk pakai gesture click
    override fun handleCancelButtons(service: AccessibilityService, root: AccessibilityNodeInfo) {
        val cancelBtn = NodeFinder.findCancelButton(root, config, service.packageName)

        if (cancelBtn != null) {
            Log.d(TAG, "🚫 Cancel button found, clicking with gesture...")

            val clicked = clickWithGesture(service, cancelBtn)
            if (clicked) {
                Log.d(TAG, "✅ Cancel button clicked successfully")
            } else {
                Log.w(TAG, "⚠️ Failed to click cancel button with gesture")
            }
        }
    }

    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return

        // ✅ Handle cancel sebelum klik chat
        handleCancelButtons(service, root)
        delay(300L)

        val rows = root.findAccessibilityNodeInfosByViewId(config.listViewId)
            .filter {
                !WindowFilterHelper.isOwnOverlay(it, service.packageName) &&
                        it.isClickable
            }

        // ✅ Cari row yang match dengan message.name
        val row = rows.find { rowNode ->
            val titleNode = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/title")
                ?.firstOrNull { node ->
                    if (WindowFilterHelper.isOwnOverlay(node, service.packageName)) return@firstOrNull false
                    val rect = Rect().apply { node.getBoundsInScreen(this) }
                    val rowRect = Rect().apply { rowNode.getBoundsInScreen(this) }
                    rowRect.contains(rect)
                }

            val name = titleNode?.text?.toString().orEmpty()
            name == message.name
        } ?: run {
            Log.w(TAG, "⚠️ Row not found for ${message.name}")
            return
        }

        // ✅ Klik chat (mark as read)
        if (!clickWithGesture(service, row)) return
        delay(1500L)

        // ✅ Handle cancel setelah masuk chat
        val rootAfterClick = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        if (rootAfterClick != null) {
            handleCancelButtons(service, rootAfterClick)
            delay(300L)
        }

        // ✅ Jika shouldSkip = true, langsung back (read only)
        if (shouldSkip) {
            Log.d(TAG, "⭐️ Skipped message from ${message.name} - read only, no reply")
            delay(500L)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        val input = NodeFinder.waitForInput(service, config) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }
        delay(1500L)

        val lastMessage = waitForLastMessage(service) ?: OpenerSelector.getOpener()
        val finalMessage = message.copy(message = lastMessage)

        val persona = PersonaManager.getPersona(service) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        val userCity = waitForCity(service, persona)

        scope.launch {
            try {
                val reply = ReplyGenerator.generate(service, finalMessage, persona, userCity)
                sendReplyWithGesture(service, input, reply)

                val safeName = message.name.sanitize()
                val userId = safeName.ifBlank { "user_${message.name.hashCode()}" }
                ChatHistoryManager.addMessage(
                    service, userId, message.name, persona.botName,
                    finalMessage.message, reply
                )

                delay(900)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(500)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                Log.e(TAG, "Failed: ${e.message}")
                try {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } catch (_: Exception) {}
            }
        }
    }

    private fun clickWithGesture(
        service: AccessibilityService,
        node: AccessibilityNodeInfo
    ): Boolean {
        if (WindowFilterHelper.isOwnOverlay(node, service.packageName)) {
            Log.w(TAG, "⚠️ Node is overlay, skip click")
            return false
        }

        val rect = Rect().apply { node.getBoundsInScreen(this) }

        // Validate rect
        if (rect.width() <= 0 || rect.height() <= 0) {
            Log.w(TAG, "⚠️ Invalid rect: $rect")
            return false
        }

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        Log.d(TAG, "📍 Clicking at: ($centerX, $centerY)")

        val path = Path().apply { moveTo(centerX, centerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return try {
            service.dispatchGesture(gesture, null, null)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Click gesture failed: ${t.message}")
            false
        }
    }

    private suspend fun sendReplyWithGesture(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String
    ) {
        val delayTime = if (text.length <= 30) {
            (590..799).random().toLong()
        } else {
            (770..1090).random().toLong()
        }

        val rect = Rect().apply { input.getBoundsInScreen(this) }
        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        try {
            service.dispatchGesture(gesture, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Click input failed: ${t.message}")
        }

        delay(300)
        BotKeyboard.instance?.typeText(text)
        delay(delayTime)

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        val sendBtn = root?.findAccessibilityNodeInfosByViewId(config.sendViewId)
            ?.firstOrNull { !WindowFilterHelper.isOwnOverlay(it, service.packageName) }

        if (sendBtn != null) {
            val sendRect = Rect().apply { sendBtn.getBoundsInScreen(this) }
            val sendPath = Path().apply {
                moveTo(sendRect.centerX().toFloat(), sendRect.centerY().toFloat())
            }
            val sendGesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(sendPath, 0, 100))
                .build()

            try {
                service.dispatchGesture(sendGesture, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "Click send failed: ${t.message}")
            }
        }
    }

    private fun waitForCity(
        service: AccessibilityService,
        persona: Persona,
        timeoutMs: Long = 2000
    ): String {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: continue

            val cityNode = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/cityView")
                ?.firstOrNull {
                    it.text != null && it.text.isNotBlank() &&
                            !WindowFilterHelper.isOwnOverlay(it, service.packageName)
                }

            val cityText = cityNode?.text?.toString()?.trim()
            if (!cityText.isNullOrEmpty()) return cityText

            Thread.sleep(120)
        }

        return persona.address
    }

    private fun waitForLastMessage(
        service: AccessibilityService,
        timeoutMs: Long = 5000
    ): String? {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: continue
            val messages = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/content")
                ?.filter { !WindowFilterHelper.isOwnOverlay(it, service.packageName) }

            if (!messages.isNullOrEmpty()) {
                val text = messages.lastOrNull()?.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) return text
            }

            Thread.sleep(150)
        }
        return null
    }

}
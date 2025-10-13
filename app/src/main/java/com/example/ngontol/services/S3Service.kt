package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.ngontol.PersonaManager
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.config.AppConfigs
import com.example.ngontol.helpers.MessageSender
import com.example.ngontol.helpers.NodeFinder
import com.example.ngontol.managers.ChatHistoryManager
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.processors.ReplyGenerator
import com.example.ngontol.utils.clickSafely
import com.example.ngontol.utils.findChildText
import com.example.ngontol.utils.sanitize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object S3Service : BaseAppService(AppConfigs.FIYA) {

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S3Service started")
        onAccessibilityEvent(service, scope, isRunning)
    }

    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return
        val rows = root.findAccessibilityNodeInfosByViewId(config.listViewId)
        val row = rows.find {
            it.findChildText(1) == message.name &&
                    it.findChildText(3) == message.message
        } ?: return

        // ✅ Klik chat (mark as read)
        if (!row.clickSafely()) return
        delay(1000L)

        // ✅ Jika shouldSkip = true, langsung back (read only)
        if (shouldSkip) {
            Log.d(TAG, "⏭️ Skipped message from ${message.name} - read only, no reply")
            delay(500L)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        // ✅ Verify page change (untuk reply normal)
        repeat(10) {
            val newRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            val inputBox = newRoot?.findAccessibilityNodeInfosByViewId(config.inputViewId)?.firstOrNull()
            if (inputBox != null) return@repeat
            delay(300L)
        }

        val input = NodeFinder.waitForInput(service, config) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        val persona = PersonaManager.getPersona(service) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        try {
            val reply = ReplyGenerator.generate(service, message, persona)
            MessageSender.send(service, input, reply, config)
            Log.d(TAG, "⏳ Waiting for message to send...")
            delay(500L)
            val safeName = message.name.sanitize()
            val userId = safeName.ifBlank { "user_${message.name.hashCode()}" }
            ChatHistoryManager.addMessage(
                service, userId, message.name, persona.botName,
                message.message, reply
            )

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
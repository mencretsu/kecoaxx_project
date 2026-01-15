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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object S6Service : BaseAppService(AppConfigs.TOKI) {
    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S6Service started")
        onAccessibilityEvent(service, scope, isRunning)
    }

    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        // ✅ Find nodes di background thread
        val (root, rows) = withContext(Dispatchers.Default) {
            val r = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            val rs = r?.findAccessibilityNodeInfosByViewId(config.listViewId) ?: emptyList()
            Pair(r, rs)
        }

        if (root == null) return

        val row = rows.find {
            it.findChildText(1) == message.name &&
                    it.findChildText(3) == message.message
        } ?: return

        // ✅ Click di Main thread
        withContext(Dispatchers.Main) {
            if (!row.clickSafely()) return@withContext
        }
        delay(1000L)

        if (shouldSkip) {
            Log.d(TAG, "⏭️ Skipped message from ${message.name} - read only, no reply")
            withContext(Dispatchers.Main) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
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
            withContext(Dispatchers.Main) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
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
            delay(1000)
            val rootAfterSend = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (rootAfterSend != null) {
                handleCancelButtons(service, rootAfterSend)
                val rewardView = rootAfterSend.findAccessibilityNodeInfosByViewId(config.rechargeId)
                    ?.firstOrNull()
                if (rewardView != null) {
                    Log.d(TAG, "🎁 Reward popup detected, back...")
                    withContext(Dispatchers.Main) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    delay(1000L)
                }
            }
            withContext(Dispatchers.Main) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}")
            try {
                withContext(Dispatchers.Main) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            } catch (_: Exception) {}
        }


    }

}
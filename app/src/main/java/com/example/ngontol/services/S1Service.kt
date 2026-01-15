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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object S1Service : BaseAppService(AppConfigs.SUGO) {

    // ✅ TAMBAHKAN MUTEX untuk prevent double execution
    private val chatMutex = Mutex()
    private var isProcessing = false

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S1Service started")
        onAccessibilityEvent(service, scope, isRunning)
    }

    // ✅ OVERRIDE shouldSkipLaunch untuk mencegah auto-launch ganda
    override fun shouldSkipLaunch(): Boolean {
        return isProcessing || state.isHandlingChat
    }

    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        // ✅ GUARD: Cegah concurrent execution
        chatMutex.withLock {
            if (isProcessing) {
                Log.d(TAG, "⏸️ Already processing chat, skip...")
                return
            }
            isProcessing = true
        }

        try {
            processChat(service, message, scope, shouldSkip)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in handleChat: ${e.message}", e)
        } finally {
            isProcessing = false
        }
    }

    private suspend fun processChat(
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
            Log.d(TAG, "⭐️ Skipped message from ${message.name} - read only, no reply")
            performBackAsync(service)
            return
        }

        // ✅ Verify page change (untuk reply normal)
        val pageChanged = withTimeoutOrNull(3000L) {
            repeat(10) {
                val newRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                val inputBox = newRoot?.findAccessibilityNodeInfosByViewId(config.inputViewId)?.firstOrNull()
                if (inputBox != null) return@withTimeoutOrNull true
                delay(300L)
            }
            false
        } ?: false

        if (!pageChanged) {
            Log.w(TAG, "⚠️ Page change verification failed")
            performBackAsync(service)
            return
        }

        val input = NodeFinder.waitForInput(service, config) ?: run {
            performBackAsync(service)
            return
        }

        val persona = PersonaManager.getPersona(service) ?: run {
            performBackAsync(service)
            return
        }

        try {
            val reply = ReplyGenerator.generate(service, message, persona)
            MessageSender.send(service, input, reply, config)
            Log.d(TAG, "⏳ Waiting for message to send...")

            // ✅ Reduced delay
            delay(400L)

            val safeName = message.name.sanitize()
            val userId = safeName.ifBlank { "user_${message.name.hashCode()}" }
            ChatHistoryManager.addMessage(
                service, userId, message.name, persona.botName,
                message.message, reply
            )

            // ✅ Handle popups BEFORE back action
            handlePostSendCleanup(service, scope)

            // ✅ Back action tanpa blocking
            performBackAsync(service)

        } catch (e: Exception) {
            Log.e(TAG, "Failed: ${e.message}")
            performBackAsync(service)
        }
    }

    /**
     * ✅ Perform back action asynchronously tanpa blocking
     */
    private fun performBackAsync(service: AccessibilityService) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Back action failed: ${e.message}")
            }
        }
    }

    /**
     * ✅ Handle popups dan cleanup SEBELUM back action
     */
    private suspend fun handlePostSendCleanup(
        service: AccessibilityService,
        scope: CoroutineScope
    ) {
        withTimeoutOrNull(3000L) {
            withContext(Dispatchers.Default) {
                delay(300L) // Minimal delay untuk UI settle

                val rootAfterSend = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?: return@withContext

                // ✅ Handle cancel buttons (non-blocking)
                scope.launch(Dispatchers.Default) {
                    handleCancelButtons(service, rootAfterSend)
                }

                delay(200L) // Short delay untuk cancel button processing

                // ✅ Check reward popup
                val rewardView = rootAfterSend
                    .findAccessibilityNodeInfosByViewId(config.rechargeId)
                    ?.firstOrNull()

                if (rewardView != null) {
                    Log.d(TAG, "🎁 Reward popup detected, closing...")
                    withContext(Dispatchers.Main) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    delay(500L) // Wait untuk popup close
                }
            }
        } ?: Log.w(TAG, "⚠️ Post-send cleanup timeout")
    }
}
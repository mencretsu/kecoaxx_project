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
import com.example.ngontol.utils.UiActionQueue
import com.example.ngontol.utils.clickSafely
import com.example.ngontol.utils.findChildText
import com.example.ngontol.utils.sanitize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object S3Service : BaseAppService(AppConfigs.FIYA) {

    // ✅ TAMBAHKAN MUTEX untuk prevent double execution
    private val chatMutex = Mutex()
    private var isProcessing = false

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S3Service started")
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
        // Find root and rows in background
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

        // Click row via UiActionQueue and await completion
        UiActionQueue.postAndAwait {
            if (!row.clickSafely()) {
                Log.w(TAG, "⚠️ row click failed (queued)")
            } else {
                Log.d(TAG, "✅ row clicked (queued)")
            }
        }

        // Safe wait for page change
        delay(900L)

        if (shouldSkip) {
            Log.d(TAG, "⭐️ Skipped message from ${message.name} - read only, no reply")
            UiActionQueue.post {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            return
        }

        // wait for input field (background polling)
        val input = withContext(Dispatchers.Default) {
            NodeFinder.waitForInput(service, config)
        } ?: run {
            UiActionQueue.post { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            return
        }

        // verify page change (small polling)
        var verified = false
        repeat(10) {
            val newRoot = withContext(Dispatchers.Default) {
                WindowFilterHelper.getTargetRootNode(service, config.packageName)
            }
            val inputBox = newRoot?.findAccessibilityNodeInfosByViewId(config.inputViewId)?.firstOrNull()
            if (inputBox != null) {
                verified = true
                return@repeat
            }
            delay(300L)
        }
        if (!verified) {
            UiActionQueue.post { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            return
        }

        val persona = PersonaManager.getPersona(service) ?: run {
            UiActionQueue.post { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            return
        }


        try {
            val reply = ReplyGenerator.generate(service, message, persona)
            MessageSender.send(service, input, reply, config)
            Log.d(TAG, "⏳ Waiting for message to send...")
            delay(600L)

            val safeName = message.name.sanitize()
            val userId = safeName.ifBlank { "user_${message.name.hashCode()}" }
            ChatHistoryManager.addMessage(
                service, userId, message.name, persona.botName,
                message.message, reply
            )

            // Handle popups BEFORE back
            handlePostSendCleanup(service, scope)

            // ✅ CRITICAL: Tunggu cleanup selesai
            delay(800L)

            // queued back
            UiActionQueue.postAndAwait { // ✅ UBAH jadi postAndAwait
                try {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    Log.d(TAG, "⬅️ Back executed (queued)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Back failed (queued): ${e.message}")
                }
            }

            // ✅ CRITICAL: Tunggu back selesai sebelum return
            delay(1000L)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to handleChat: ${e.message}")
            delay(500L) // Safety delay sebelum back
            UiActionQueue.postAndAwait {
                try {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } catch (ignored: Exception) {}
            }
            delay(1000L) // Safety delay setelah back
        }
    }

    private suspend fun handlePostSendCleanup(
        service: AccessibilityService,
        scope: CoroutineScope
    ) {
        withTimeoutOrNull(5000L) { // ✅ Naikin timeout jadi 5 detik
            withContext(Dispatchers.Default) {
                delay(400L) // ✅ Naikin delay awal

                val rootAfterSend = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?: return@withContext

                // schedule cancel buttons click via queue
                UiActionQueue.postAndAwait { // ✅ UBAH jadi postAndAwait
                    try {
                        handleCancelButtons(service, rootAfterSend)
                        Log.d(TAG, "✅ Cancel buttons handled")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "❌ handleCancelButtons in cleanup failed: ${e.message}")
                    }
                }

                delay(400L) // ✅ Naikin delay

                val rewardView = rootAfterSend.findAccessibilityNodeInfosByViewId(config.rechargeId)?.firstOrNull()
                if (rewardView != null) {
                    Log.d(TAG, "🎁 Reward popup detected, queuing back")
                    UiActionQueue.postAndAwait { // ✅ UBAH jadi postAndAwait
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        Log.d(TAG, "⬅️ Reward popup closed")
                    }
                    delay(800L) // ✅ Naikin delay
                }
            }
        } ?: Log.w(TAG, "⚠️ Post-send cleanup timeout")
    }
}
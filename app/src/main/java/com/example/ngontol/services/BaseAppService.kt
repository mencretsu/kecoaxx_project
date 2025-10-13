package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ngontol.PersonaManager
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.helpers.NodeFinder
import com.example.ngontol.helpers.TelegramLogger
import com.example.ngontol.managers.ServiceStateManager
import com.example.ngontol.models.AppConfig
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.utils.BotConstants
import com.example.ngontol.utils.findChildText
import com.example.ngontol.utils.isAllDigits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class BaseAppService(protected val config: AppConfig) {
    protected val TAG: String = this::class.simpleName ?: "BaseAppService"
    protected val state = ServiceStateManager()

    abstract suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean = false
    )

    protected open fun extractChatMessage(
        row: AccessibilityNodeInfo,
        service: AccessibilityService
    ): ChatMessage? {
        val name = row.findChildText(1).orEmpty()
        val msg = row.findChildText(3).orEmpty()

        if (name.isBlank() || msg.isBlank()) return null

        val key = "$name|$msg"
        if (key in state.processed) return null

        val unreadCount = NodeFinder.findUnreadCount(row, config.unreadViewId)

        return if (unreadCount > 0) {
            ChatMessage(name, msg, unreadCount)
        } else {
            state.addProcessed(key)
            null
        }
    }

    protected fun shouldSkipMessage(
        message: ChatMessage,
        service: AccessibilityService
    ): Boolean {
        val persona = PersonaManager.getPersona(service) ?: return false
        val blacklist = (listOf("agen", "agency", "partner", "mengikutimu.") + persona.blacklist).distinct()

        val msgLow = message.message.lowercase()
        val nameLow = message.name.lowercase()

        return blacklist.any { msgLow.contains(it) || nameLow.contains(it) } ||
                message.name.isAllDigits()
    }

    // ✅ RESTART APP (dengan reset state)
    protected suspend fun restartApp(
        service: AccessibilityService,
        relogOption: Int = 1,
        reason: String = "unknown"
    ) {
        Log.d(TAG, "🔄 Restart process start (reason: $reason)")

        if (relogOption == 3) {
            Log.d(TAG, "🚫 No Relog - Skip restart, reset cache only")
            state.lastCacheClear = System.currentTimeMillis()
            state.needRestart = false
            return
        }

        // Reset state
        state.reset(service)

        // Launch app
        service.packageManager.getLaunchIntentForPackage(config.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            service.startActivity(this)
        }

        state.lastAppLaunchTime = System.currentTimeMillis()
        state.lastRestartTime = System.currentTimeMillis()

        Log.d(TAG, "✅ App launched, waiting max 30s...")

        // ✅ WAIT APP READY - KALO FALSE (timeout 30s) → RESTART LAGI
        val isReady = waitUntilAppReady(service, maxWaitMs = 60000L)

        if (!isReady) {
            Log.e(TAG, "⏰ App not ready after 30s - FORCING RESTART AGAIN!")
            // ✅ RESTART LAGI SEKALI
            service.packageManager.getLaunchIntentForPackage(config.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                service.startActivity(this)
            }
            delay(10000L) // Tunggu 10 detik

            // ✅ COBA LAGI, KALO MASIH GAK READY → RETURN SAJA
            val retryReady = waitUntilAppReady(service, maxWaitMs = 20000L)
            if (!retryReady) {
                Log.e(TAG, "❌ App still not ready after retry - GIVING UP")
                state.lastRestartComplete = System.currentTimeMillis()
                return
            }
        }

        navigateToConversation(service, relogOption)
        logDiamond(service)

        state.lastRestartComplete = System.currentTimeMillis()
        Log.d(TAG, "✅ Restart process complete")
    }

    // ✅ WAIT UNTIL APP READY (Polling)
    private suspend fun waitUntilAppReady(
        service: AccessibilityService,
        maxWaitMs: Long = 60000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            attempts++

            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)

            if (root != null) {
                val hasConversationTab = root.findAccessibilityNodeInfosByViewId(config.conversationTabId)
                    ?.firstOrNull() != null

                if (hasConversationTab) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ App ready after ${elapsed}ms (attempt: $attempts)")
                    return true
                }
            }

            Log.v(TAG, "⏳ Waiting for app ready... (attempt $attempts)")
            delay(1000L)
        }

        Log.e(TAG, "❌ App not ready after ${maxWaitMs}ms - NEED RESTART")
        return false
    }

    // ✅ LAUNCH + NAVIGATE (tanpa reset state)
    private suspend fun launchAndNavigate(service: AccessibilityService) {
        val persona = PersonaManager.getPersona(service)
        val relogOption = persona?.relogOption ?: 1

        Log.d(TAG, "🚀 Launch + Navigate (relogOption: $relogOption)")

        // 1. Launch app
        val intent = service.packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent == null) {
            Log.e(TAG, "❌ Cannot launch app - intent null")
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        service.startActivity(intent)
        state.lastAppLaunchTime = System.currentTimeMillis()

        Log.d(TAG, "✅ App launched, initial wait 10s...")

        // Initial wait (give app time to initialize)
        delay(10000L)

        // Then poll until ready
        Log.d(TAG, "🔍 Checking if app is ready...")
        val isReady = waitUntilAppReady(service, maxWaitMs = 20000L)

        if (!isReady) {
            Log.e(TAG, "⏰ App not ready, trying navigation anyway...")
        }

        // 2. Navigate
        when (relogOption) {
            1, 2 -> {
                navigateToConversation(service, relogOption)
                Log.d(TAG, "✅ Navigated to conversation (option: $relogOption)")
            }
            3 -> {
                Log.d(TAG, "🚫 No Relog - Skip navigation")
            }
        }

        // 3. Log diamond
        delay(2000L)
        logDiamond(service)
    }

    protected open suspend fun navigateToConversation(
        service: AccessibilityService,
        option: Int,
        retryCount: Int = 0,
        maxRetries: Int = 3
    ) {
        if (option == 3) {
            Log.d(TAG, "🚫 No Relog - Skip navigation")
            return
        }

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        if (root == null) {
            if (retryCount < maxRetries) {
                Log.w(TAG, "⚠️ Root null, retry ${retryCount + 1}/$maxRetries in 3s...")
                delay(3000L)
                navigateToConversation(service, option, retryCount + 1, maxRetries)
            } else {
                Log.e(TAG, "❌ Navigation failed after $maxRetries retries")
            }
            return
        }

        // Klik tab conversation
        val conversationTab = root.findAccessibilityNodeInfosByViewId(config.conversationTabId)
            ?.firstOrNull()

        if (conversationTab == null) {
            if (retryCount < maxRetries) {
                Log.w(TAG, "⚠️ Conversation tab not found, retry ${retryCount + 1}/$maxRetries in 3s...")
                delay(3000L)
                navigateToConversation(service, option, retryCount + 1, maxRetries)
            } else {
                Log.e(TAG, "❌ Conversation tab not found after $maxRetries retries")
            }
            return
        }

        conversationTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "✅ Conversation tab clicked")
        delay(1000L)

        // Klik sub-tab
        val subTabId = if (option == 2) config.unreadTabId else config.allTabId
        val subTab = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            ?.findAccessibilityNodeInfosByViewId(subTabId)
            ?.firstOrNull()

        if (subTab != null) {
            subTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✅ Sub-tab clicked")
            delay(2500L)
        } else {
            Log.w(TAG, "⚠️ Sub-tab not found but conversation tab clicked")
            delay(2500L)
        }
    }

    protected open fun logDiamond(service: AccessibilityService) {
        if (config.diamondViewId.isEmpty()) return

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            ?: service.rootInActiveWindow
            ?: return

        val diamondText = root.findAccessibilityNodeInfosByViewId(config.diamondViewId)
            ?.firstOrNull()
            ?.text?.toString()

        val persona = PersonaManager.getPersona(service)
        val appName = config.packageName.substringAfterLast(".")

        TelegramLogger.sendDiamondUpdate(
            persona?.botName ?: "unknown",
            appName.uppercase(),
            diamondText
        )
    }

    // ✅ UBAH JADI OPEN supaya bisa di-override
    protected open fun handleCancelButtons(service: AccessibilityService, root: AccessibilityNodeInfo) {
        val cancelBtn = NodeFinder.findCancelButton(root, config, service.packageName)
        cancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ✅ MAIN ENTRY POINT
    fun onAccessibilityEvent(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        // ✅ Early exit if not running
        if (!isRunning()) {
            Log.v(TAG, "⏸️ Service not running, skip event")
            return
        }

        val now = System.currentTimeMillis()

        // Check restart cooldown
        if (state.shouldSkipRestart(now)) return

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)

        // ✅ Handle inactive app (auto launch)
        if (root == null) {
            handleInactiveApp(service, scope, isRunning)
            return
        }

        // Check handling chat & post restart cooldown
        if (state.isHandlingChat || state.shouldSkipPostRestart(now)) return

        // Handle pending restart
        if (handlePendingRestart(service, scope, isRunning)) return

        handleCancelButtons(service, root)

        if (!state.shouldProcess(now)) return
        state.updateLastRun(now)

        val rows = root.findAccessibilityNodeInfosByViewId(config.listViewId)
        if (rows.isEmpty()) return

        processChats(service, rows, scope, isRunning)
    }

    // ✅ HANDLE INACTIVE APP (Auto Launch + Navigate)
    private fun handleInactiveApp(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        // ✅ Check if still running
        if (!isRunning()) return

        // Prevent double launch
        if (state.isLaunching) {
            return
        }

        // Check cooldown
        val timeSinceLaunch = System.currentTimeMillis() - state.lastAppLaunchTime
        if (timeSinceLaunch < BotConstants.LAUNCH_COOLDOWN) {
            return
        }

        Log.d(TAG, "📱 App tidak aktif, auto launch + navigate dalam ${BotConstants.AUTO_LAUNCH_DELAY / 1000}s...")

        scope.launch {
            // ✅ Check again before proceeding
            if (!isRunning()) return@launch

            state.isLaunching = true

            try {
                delay(BotConstants.AUTO_LAUNCH_DELAY)

                // ✅ Check if still running after delay
                if (!isRunning()) {
                    Log.d(TAG, "⏸️ Service stopped during launch delay")
                    return@launch
                }

                // Double check
                val rootCheck = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                if (rootCheck != null) {
                    Log.d(TAG, "✅ App sudah aktif, skip launch")
                    return@launch
                }

                // Launch + Navigate
                launchAndNavigate(service)

            } finally {
                state.isLaunching = false
            }
        }
    }

    private fun handlePendingRestart(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ): Boolean {
        if (!state.needRestart) return false

        state.needRestart = false
        scope.launch {
            // ✅ Check if still running
            if (!isRunning()) return@launch

            delay(2000L)

            // ✅ Check again after delay
            if (!isRunning()) {
                Log.d(TAG, "⏸️ Service stopped before restart")
                return@launch
            }

            val persona = PersonaManager.getPersona(service)
            val relogOption = persona?.relogOption ?: 1
            restartApp(service, relogOption)
        }
        return true
    }
    protected open fun processChatsForS2(
        service: AccessibilityService,
        rows: List<AccessibilityNodeInfo>,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        // Default: tidak ada implementasi (gunakan processChats biasa)
    }
    private fun processChats(
        service: AccessibilityService,
        rows: List<AccessibilityNodeInfo>,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        // ✅ Check if still running
        if (!isRunning()) return

        scope.launch(Dispatchers.Default) {
            // ✅ Check again in coroutine
            if (!isRunning()) return@launch

            val persona = PersonaManager.getPersona(service)
            val relogOption = persona?.relogOption ?: 1

            val unreadList = mutableListOf<ChatMessage>()

            for (row in rows) {
                val message = extractChatMessage(row, service) ?: continue

                // ✅ OPTION 2: Ambil 1 teratas aja (skip atau tidak tetap masuk list)
                if (relogOption == 2) {
                    unreadList.add(message)
                    break // Stop setelah 1 chat
                }

                // ✅ OPTION 1 & 3: Filter shouldSkip
                if (shouldSkipMessage(message, service)) {
                    state.addProcessed(message.cacheKey)
                    continue
                }
                unreadList.add(message)
            }
            // ✅ AUTO CLEAR CACHE setiap 30 detik (tanpa restart)
            val timeSinceLastClear = System.currentTimeMillis() - state.lastCacheClear
            if (timeSinceLastClear >= 30000L) {
                Log.d(TAG, "🧹 30s passed, clearing cache...")
                state.processed.clear() // ✅ Langsung clear
                state.lastCacheClear = System.currentTimeMillis()
                Log.d(TAG, "✅ Cache cleared")
            }

            if (unreadList.isNotEmpty()) {
                handleUnreadChats(service, unreadList, this, isRunning)
            } else if (state.shouldClearCache()) {
                // ✅ Check if still running
                if (!isRunning()) return@launch

                // Guard: jangan restart kalau baru aja restart
                val timeSinceRestart = System.currentTimeMillis() - state.lastRestartTime
                if (timeSinceRestart < 60000L) {
                    Log.d(TAG, "⏸️ Skip cache clear restart - baru restart ${timeSinceRestart / 1000}s lalu")
                    return@launch
                }

                launch(Dispatchers.Main) {
                    // ✅ Check before restart
                    if (!isRunning()) {
                        Log.d(TAG, "⏸️ Service stopped before restart")
                        return@launch
                    }

                    Log.d(TAG, "🔄 Cache full, triggering restart (relogOption: $relogOption)")
                    restartApp(service, relogOption)
                }
            }
        }
    }

    private suspend fun handleUnreadChats(
        service: AccessibilityService,
        messages: List<ChatMessage>,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        withContext(Dispatchers.Main) {
            // ✅ Check if still running
            if (!isRunning()) {
                Log.d(TAG, "⏸️ Service stopped, skip handling chats")
                return@withContext
            }

            state.isHandlingChat = true
            Log.d(TAG, "🔥 Handling ${messages.size} unread chats...")

            // ✅ Handle 1 by 1 - SEQUENTIAL (tunggu handleChat selesai dulu)
            for ((index, message) in messages.withIndex()) {
                // ✅ Check before each chat
                if (!isRunning()) {
                    Log.d(TAG, "⏸️ Service stopped during chat handling")
                    break
                }

                try {
                    val startTime = System.currentTimeMillis()

                    // ✅ Check apakah pesan ini shouldSkip
                    val shouldSkip = shouldSkipMessage(message, service)

                    if (shouldSkip) {
                        Log.d(TAG, "⭐️ Processing chat ${index + 1}/${messages.size}: ${message.name} (SKIP - read only)")
                    } else {
                        Log.d(TAG, "💬 Processing chat ${index + 1}/${messages.size}: ${message.name}")
                    }

                    // ✅ AWAIT handleChat completion (pass shouldSkip flag)
                    handleChat(service, message, scope, shouldSkip)

                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ Chat ${index + 1} done in ${elapsed}ms")

                    state.addProcessed(message.cacheKey)

                    // ✅ Delay AFTER chat selesai (biar gak terlalu cepat next)
                    if (index < messages.size - 1) {
                        Log.d(TAG, "⏳ Waiting 0.5s before next chat...")
                        delay(500L)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error handling chat ${index + 1}: ${e.message}", e)
                    // ✅ Tetap delay meskipun error (biar gak spam retry)
                    if (index < messages.size - 1) {
                        delay(500L)
                    }
                }
            }

            state.isHandlingChat = false
            state.saveProcessed(service)
            Log.d(TAG, "✅ All ${messages.size} chats handled")

            // ✅ Check before scheduling restart
            if (isRunning() && state.shouldClearCache()) {
                Log.d(TAG, "⏰ Cache clear scheduled")
                state.needRestart = true
            }
        }
    }
}
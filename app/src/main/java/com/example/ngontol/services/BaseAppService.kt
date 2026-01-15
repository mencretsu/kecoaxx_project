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
import com.example.ngontol.utils.UiActionQueue
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
        val blacklist = (listOf("agen", "agency", "partner", "mengikutimu.","bernadus") + persona.blacklist).distinct()

        val msgLow = message.message.lowercase()
        val nameLow = message.name.lowercase()

        return blacklist.any { msgLow.contains(it) || nameLow.contains(it) } ||
                message.name.isAllDigits()
    }

    /**
     * Get current effective relog option (resolves hybrid mode)
     */
    protected fun getEffectiveRelogOption(service: AccessibilityService): Int {
        val persona = PersonaManager.getPersona(service) ?: return 1

        return when (persona.relogOption ?: 1) {
            4 -> {
                // Hybrid mode: return current state (1 or 2)
                val state = persona.hybridState ?: 1
                Log.d(TAG, "🔀 Hybrid mode active - current state: $state")
                state
            }
            else -> persona.relogOption ?: 1
        }
    }

    /**
     * Toggle hybrid state after restart (1 -> 2 -> 1 -> ...)
     */
    protected fun toggleHybridState(service: AccessibilityService) {
        val persona = PersonaManager.getPersona(service) ?: return

        if (persona.relogOption != 4) return // Only toggle if hybrid mode

        val newState = if (persona.hybridState == 1) 2 else 1
        val updatedPersona = persona.copy(hybridState = newState)

        PersonaManager.savePersona(service, updatedPersona)
        Log.d(TAG, "🔀 Hybrid state toggled: ${persona.hybridState} -> $newState")
    }

    // ✅ RESTART APP dengan Hybrid Support
    protected open suspend fun restartApp(
        service: AccessibilityService,
        relogOption: Int = 3,
        reason: String = "unknown"
    ) {
        Log.d(TAG, "🔄 Restart process start (reason: $reason, raw option: $relogOption)")

        val effectiveOption = if (relogOption == 4) {
            getEffectiveRelogOption(service)
        } else {
            relogOption
        }

        Log.d(TAG, "🔄 Effective relog option: $effectiveOption")

        if (effectiveOption == 3) {
            state.lastCacheClear = System.currentTimeMillis()
            state.needRestart = false
            Log.d(TAG, "🚫 No Relog - Skip restart, reset cache only")

            UiActionQueue.post {
                val scrollRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                scrollRoot?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Log.d(TAG, "📜 Scrolled down (queued)")
            }
            delay(500L)
            return
        }

        // Reset state + launch app
        state.reset(service)
        service.packageManager.getLaunchIntentForPackage(config.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            service.startActivity(this)
        }
        state.lastAppLaunchTime = System.currentTimeMillis()
        state.lastRestartTime = System.currentTimeMillis()

        Log.d(TAG, "✅ App launched, waiting max 60s...")
        val isReady = waitUntilAppReady(service, maxWaitMs = 60000L)

        if (!isReady) {
            Log.e(TAG, "⏰ App not ready after 60s - FORCING RESTART AGAIN!")
            service.packageManager.getLaunchIntentForPackage(config.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                service.startActivity(this)
            }
            delay(10000L)
            val retryReady = waitUntilAppReady(service, maxWaitMs = 20000L)
            if (!retryReady) {
                Log.e(TAG, "❌ App still not ready after retry - GIVING UP")
                state.lastRestartComplete = System.currentTimeMillis()
                return
            }
        }

        val persona = PersonaManager.getPersona(service)
        val autoHiValue = persona?.autoHi ?: 1

        navigateToConversation(service, effectiveOption, autoHiValue)
        logDiamond(service)

        // 🔀 Toggle hybrid state AFTER successful restart
        if (relogOption == 4) {
            toggleHybridState(service)
        }

        state.lastRestartComplete = System.currentTimeMillis()
        Log.d(TAG, "✅ Restart process complete")
    }

    private suspend fun waitUntilAppReady(
        service: AccessibilityService,
        maxWaitMs: Long = 150000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            attempts++

            val root = withContext(Dispatchers.Default) {
                WindowFilterHelper.getTargetRootNode(service, config.packageName)
            }

            if (root != null) {
                if (config.conversationTabId.isEmpty()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ App ready (no conversationTab check) after ${elapsed}ms")
                    return true
                }

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

        Log.e(TAG, "❌ App not ready after ${maxWaitMs}ms")
        return false
    }

    private suspend fun launchAndNavigate(service: AccessibilityService) {
        val persona = PersonaManager.getPersona(service)
        val rawRelogOption = persona?.relogOption ?: 3
        val relogOption = getEffectiveRelogOption(service)
        val autoHiValue = persona?.autoHi ?: 1

        Log.d(TAG, "🚀 Launch + Navigate (raw: $rawRelogOption, effective: $relogOption)")

        val intent = service.packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent == null) {
            Log.e(TAG, "❌ Cannot launch app - intent null")
            return
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        service.startActivity(intent)
        state.lastAppLaunchTime = System.currentTimeMillis()

        Log.d(TAG, "✅ App launched, initial wait 10s...")
        delay(10000L)

        Log.d(TAG, "🔍 Checking if app is ready...")
        val isReady = waitUntilAppReady(service, maxWaitMs = 20000L)

        if (!isReady) {
            Log.e(TAG, "⏰ App not ready, trying navigation anyway...")
        }

        when (relogOption) {
            1, 2 -> {
                navigateToConversation(service, relogOption, autoHiValue)
            }
            3 -> {
                Log.d(TAG, "🚫 No Relog - Skip navigation")
            }
        }

        delay(2000L)
        logDiamond(service)
    }

    protected open suspend fun navigateToConversation(
        service: AccessibilityService,
        option: Int,
        autoHi: Int = 1,
        retryCount: Int = 0,
        maxRetries: Int = 3
    ) {
        if (config.conversationTabId.isEmpty()) {
            Log.d(TAG, "⏭️ No conversationTab defined, skip navigation")
            return
        }

        if (option == 3) {
            Log.d(TAG, "🚫 No Relog - Skip navigation")
            return
        }
        delay(5500L)

        val root = withContext(Dispatchers.Default) {
            WindowFilterHelper.getTargetRootNode(service, config.packageName)
        }

        if (root == null) {
            if (retryCount < maxRetries) {
                Log.w(TAG, "⚠️ Root null, retry ${retryCount + 1}/$maxRetries in 3s...")
                delay(3000L)
                navigateToConversation(service, option, autoHi, retryCount + 1, maxRetries)
            } else {
                Log.e(TAG, "❌ Navigation failed after $maxRetries retries")
            }
            return
        }

        handleCancelButtons(service, root)

        if (autoHi == 2) {
            Log.d(TAG, "🟢 Auto Hi: ON - Processing 'Orang Baru'...")

            // Click "Orang Baru"
            UiActionQueue.postAndAwait {
                val newUserTab = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?.findAccessibilityNodeInfosByViewId(config.homeNew)
                    ?.firstOrNull()
                newUserTab?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "✅ 'Orang Baru' tab clicked (queued)")
            }
            delay(1200L)

            // Scroll down
            UiActionQueue.post {
                val scrollRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                scrollRoot?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Log.d(TAG, "📜 Scrolled down (queued)")
            }
            delay(500L)

            // Wait for hi button
            var hiButtonFound = false
            val startTime = System.currentTimeMillis()
            val timeoutMs = 10000L
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val checkRoot = withContext(Dispatchers.Default) {
                    WindowFilterHelper.getTargetRootNode(service, config.packageName)
                }
                val hiButton = checkRoot?.findAccessibilityNodeInfosByViewId(config.hiButton)?.firstOrNull()
                if (hiButton != null) {
                    hiButtonFound = true
                    Log.d(TAG, "✅ Hi button found (background)")
                    break
                }
                delay(600L)
            }

            if (!hiButtonFound) {
                Log.w(TAG, "⚠️ Hi button not found after timeout, skipping...")
                delay(1000L)
            } else {
                delay(900L)
            }

            // Click one item
            UiActionQueue.postAndAwait {
                val imageViews = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?.findAccessibilityNodeInfosByViewId(config.hiButton)

                if (imageViews != null) {
                    for (imageView in imageViews) {
                        if (!imageView.isSelected) {
                            imageView.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "✅ Item clicked (queued)")
                            break
                        }
                    }
                }
            }
            delay(1800L)

            // Handle popups
            val cleanupStart = System.currentTimeMillis()
            val cleanupTimeout = 8000L
            while (System.currentTimeMillis() - cleanupStart < cleanupTimeout) {
                val currentRoot = withContext(Dispatchers.Default) {
                    WindowFilterHelper.getTargetRootNode(service, config.packageName)
                }

                if (currentRoot == null) {
                    delay(500L)
                    continue
                }

                val rewardView = currentRoot.findAccessibilityNodeInfosByViewId(config.rechargeId)?.firstOrNull()
                if (rewardView != null) {
                    Log.d(TAG, "🎁 Reward popup detected - queuing back")
                    UiActionQueue.post {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    delay(900L)
                    continue
                }

                val rootForCancel = currentRoot
                UiActionQueue.post {
                    try {
                        val cancelBtn = NodeFinder.findCancelButton(rootForCancel, config, service.packageName)
                        cancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Cancel click failed: ${e.message}")
                    }
                }
                delay(700L)

                val checkRoot = withContext(Dispatchers.Default) {
                    WindowFilterHelper.getTargetRootNode(service, config.packageName)
                }
                val stillHasReward = checkRoot?.findAccessibilityNodeInfosByViewId(config.rechargeId)?.firstOrNull() != null
                if (!stillHasReward) {
                    Log.d(TAG, "✅ All popups cleared")
                    delay(400L)
                    break
                }
                delay(500L)
            }

            delay(900L)
            handleCancelButtons(service, root)
            delay(1200L)
        } else {
            Log.d(TAG, "🔴 Auto Hi: OFF - Skip 'Orang Baru', go to conversation directly")
        }
        delay(500L)

        // Click conversation tab
        val conversationTab = withContext(Dispatchers.Default) {
            var tab: AccessibilityNodeInfo? = null
            repeat(5) { attempt ->
                val checkRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                tab = checkRoot?.findAccessibilityNodeInfosByViewId(config.conversationTabId)?.firstOrNull()
                if (tab != null) {
                    Log.d(TAG, "✅ Conversation tab found on attempt ${attempt + 1}")
                    return@withContext tab
                }
                if (attempt < 4) {
                    Log.w(TAG, "⚠️ Conversation tab not found, retry ${attempt + 1}/5 in 1s...")
                    delay(1000L)
                }
            }
            tab
        }

        if (conversationTab == null) {
            if (retryCount < maxRetries) {
                Log.w(TAG, "⚠️ Conversation tab not found after 5 attempts, major retry ${retryCount + 1}/$maxRetries in 3s...")
                handleCancelButtons(service, root)
                delay(3000L)
                navigateToConversation(service, option, autoHi, retryCount + 1, maxRetries)
            } else {
                Log.e(TAG, "❌ Conversation tab not found after $maxRetries major retries")
            }
            return
        }

        UiActionQueue.postAndAwait {
            conversationTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "✅ Conversation tab clicked (queued)")
        }

        delay(2200L)

        // KHUSUS SIYA: Klik sub-tab dulu sebelum wait
        var siyaSubTabClicked = false
        if (config.packageName == "com.zr.siya" && option == 2) {
            val subTabNodes = withContext(Dispatchers.Default) {
                WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?.findAccessibilityNodeInfosByViewId(config.unreadTabId)
            }
            val subTab = subTabNodes?.firstOrNull { it.text?.toString() == "Belum Dibalas" }?.parent

            if (subTab != null) {
                UiActionQueue.postAndAwait {
                    subTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "✅ SIYA sub-tab 'Belum Dibalas' clicked early (queued)")
                }
                siyaSubTabClicked = true
                delay(2000L)
            } else {
                Log.w(TAG, "⚠️ SIYA sub-tab 'Belum Dibalas' not found")
            }
        }

        // Wait until lists appear
        val tabSwitched = withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            val maxWaitMs = 15000L
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                val checkRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                val hasListView = checkRoot?.findAccessibilityNodeInfosByViewId(config.listViewId)?.isNotEmpty() ?: false
                val hasUnreadTab = if (config.unreadTabId.isNotEmpty()) {
                    checkRoot?.findAccessibilityNodeInfosByViewId(config.unreadTabId)?.isNotEmpty() ?: false
                } else true
                if (hasListView && hasUnreadTab) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ Tab fully loaded after ${elapsed}ms")
                    return@withContext true
                }
                delay(500L)
            }
            Log.e(TAG, "❌ Tab failed to load after 15s")
            false
        }

        if (!tabSwitched) {
            Log.e(TAG, "❌ Conversation tab not loaded, ABORTING navigation")
            if (retryCount < maxRetries) {
                delay(3000L)
                Log.w(TAG, "🔄 Retrying navigation ${retryCount + 1}/$maxRetries...")
                navigateToConversation(service, option, autoHi, retryCount + 1, maxRetries)
            }
            return
        }
        delay(5500L)

        // Click sub-tab (skip kalau SIYA udah diklik di atas)
        if (!(config.packageName == "com.zr.siya" && siyaSubTabClicked)) {
            val subTabId = if (option == 2) config.unreadTabId else config.allTabId
            val allSubTabs = withContext(Dispatchers.Default) {
                WindowFilterHelper.getTargetRootNode(service, config.packageName)?.findAccessibilityNodeInfosByViewId(subTabId)
            }

            val subTab = allSubTabs?.firstOrNull()
            if (subTab != null) {
                UiActionQueue.postAndAwait {
                    subTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "✅ Sub-tab clicked: ${subTab.text} (queued)")
                }
                delay(2000L)
            } else {
                Log.w(TAG, "⚠️ Sub-tab not found but conversation tab clicked")
                delay(2000L)
            }
        } else {
            Log.d(TAG, "⏭️ Skip sub-tab click (SIYA already clicked)")
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

    protected open fun shouldSkipLaunch(): Boolean {
        return false
    }

    private fun handleInactiveApp(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        if (!isRunning()) return
        if (shouldSkipLaunch()) return
        if (state.isLaunching) return
        val timeSinceLaunch = System.currentTimeMillis() - state.lastAppLaunchTime
        if (timeSinceLaunch < BotConstants.LAUNCH_COOLDOWN) return

        scope.launch {
            if (!isRunning()) return@launch
            if (shouldSkipLaunch()) return@launch

            state.isLaunching = true
            try {
                delay(BotConstants.AUTO_LAUNCH_DELAY)
                if (!isRunning()) return@launch
                if (shouldSkipLaunch()) return@launch

                val rootCheck = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                if (rootCheck != null) return@launch

                Log.d(TAG, "🚀 Launching app...")
                val intent = service.packageManager.getLaunchIntentForPackage(config.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    service.startActivity(intent)
                    state.lastAppLaunchTime = System.currentTimeMillis()
                    delay(5000L)

                    if (config.conversationTabId.isNotEmpty()) {
                        val persona = PersonaManager.getPersona(service)
                        val rawRelogOption = persona?.relogOption ?: 3
                        val relogOption = getEffectiveRelogOption(service)
                        val autoHiValue = persona?.autoHi ?: 1
                        navigateToConversation(service, relogOption, autoHiValue)
                    }
                }
            } finally {
                state.isLaunching = false
            }
        }
    }

    fun onAccessibilityEvent(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        if (!isRunning()) return

        val now = System.currentTimeMillis()
        if (state.shouldSkipRestart(now)) return

        if (now - state.lastEventProcessTime < 500L) {
            return
        }
        state.lastEventProcessTime = now

        scope.launch(Dispatchers.Default) {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (root == null) {
                handleInactiveApp(service, scope, isRunning)
                return@launch
            }

            if (state.isHandlingChat || state.shouldSkipPostRestart(now)) return@launch
            if (handlePendingRestart(service, scope, isRunning)) return@launch

            UiActionQueue.post {
                handleCancelButtons(service, root)
            }

            if (!state.shouldProcess(now)) return@launch
            state.updateLastRun(now)

            val rows = root.findAccessibilityNodeInfosByViewId(config.listViewId)
            if (rows.isEmpty()) return@launch

            processChats(service, rows, scope, isRunning)
        }
    }

    private fun handlePendingRestart(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ): Boolean {
        if (!state.needRestart) return false

        if (state.isHandlingChat) {
            Log.d(TAG, "⏸️ Skip restart - masih handle chat")
            return true
        }

        val timeSinceRestart = System.currentTimeMillis() - state.lastRestartTime
        if (timeSinceRestart < 60000L) {
            Log.d(TAG, "⏸️ Skip restart - baru restart ${timeSinceRestart / 1000}s lalu")
            state.needRestart = false
            return true
        }

        state.needRestart = false

        scope.launch {
            if (!isRunning()) return@launch

            delay(5000L)

            if (!isRunning()) return@launch

            if (state.isHandlingChat) {
                Log.d(TAG, "⏸️ Skip restart - chat handling started during delay")
                state.needRestart = true
                return@launch
            }

            val rootCheck = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (rootCheck == null) {
                Log.d(TAG, "⏸️ Skip restart - app not visible")
                return@launch
            }

            val persona = PersonaManager.getPersona(service)
            val rawRelogOption = persona?.relogOption ?: 1

            Log.d(TAG, "🔄 Starting restart process...")

            try {
                restartApp(service, rawRelogOption)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Restart failed: ${e.message}", e)
            }
        }
        return true
    }

    private fun processChats(
        service: AccessibilityService,
        rows: List<AccessibilityNodeInfo>,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        if (!isRunning()) return

        Log.d(TAG, "🔍 processChats started")

        scope.launch(Dispatchers.Default) {
            try {
                if (!isRunning()) return@launch

                val persona = PersonaManager.getPersona(service)
                val rawRelogOption = persona?.relogOption ?: 3
                val relogOption = getEffectiveRelogOption(service)

                Log.d(TAG, "🔄 Raw relogOption: $rawRelogOption, Effective: $relogOption")

                val unreadList = mutableListOf<ChatMessage>()
                for (row in rows) {
                    val message = extractChatMessage(row, service) ?: continue
                    if (relogOption == 2) {
                        unreadList.add(message)
                        break
                    }
                    if (shouldSkipMessage(message, service)) {
                        state.addProcessed(message.cacheKey)
                        continue
                    }
                    unreadList.add(message)
                }

                if (state.shouldClearCache()) {
                    state.clearCache()
                }

                if (unreadList.isNotEmpty()) {
                    Log.d(TAG, "📨 Found ${unreadList.size} unread messages")
                    handleUnreadChats(service, unreadList, this, isRunning)
                    Log.d(TAG, "✅ handleUnreadChats returned")
                } else if (state.shouldRelog()) {
                    if (!isRunning()) return@launch
                    val timeSinceRestart = System.currentTimeMillis() - state.lastRestartTime
                    if (timeSinceRestart < 60000L) {
                        Log.d(TAG, "⏸️ Skip restart - baru restart ${timeSinceRestart / 1000}s lalu")
                        return@launch
                    }
                    Log.d(TAG, "🔄 Scheduling restart (no unread)")
                    UiActionQueue.post {
                        scope.launch {
                            delay(500)
                            restartApp(service, rawRelogOption)
                        }
                    }
                }

                Log.d(TAG, "✅ processChats COMPLETE")
            } catch (e: Exception) {
                Log.e(TAG, "❌ FATAL ERROR in processChats: ${e.message}", e)
                e.printStackTrace()
            }
        }
    }

    protected suspend fun backOnce(service: AccessibilityService) {
        UiActionQueue.postAndAwait {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG,"back 1 (queued)")
        }
    }

    protected suspend fun backTwice(service: AccessibilityService) {
        UiActionQueue.postAndAwait {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG,"back 1 (queued for twice)")
        }
        delay(300)
        UiActionQueue.postAndAwait {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            Log.d(TAG,"back 2 (queued)")
        }
    }

    protected suspend fun backWithRetry(
        service: AccessibilityService,
        retries: Int = 2,
        delayMs: Long = 1000
    ) {
        for (attempt in 0 until retries) {
            try {
                val success = withContext(Dispatchers.Main) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }

                if (success) {
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Back action attempt $attempt exception: ${e.message}")
            }

            if (attempt < retries - 1) {
                delay(delayMs)
            }
        }
    }

    protected open fun handleCancelButtons(service: AccessibilityService, root: AccessibilityNodeInfo) {
        try {
            val cancelBtn = NodeFinder.findCancelButton(root, config, service.packageName)
            if (cancelBtn != null) {
                cancelBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ handleCancelButtons failed: ${e.message}")
        }
    }

    private suspend fun handleUnreadChats(
        service: AccessibilityService,
        messages: List<ChatMessage>,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        try {
            withContext(Dispatchers.Default) {
                if (!isRunning()) return@withContext

                state.isHandlingChat = true
                Log.d(TAG, "🔥 Handling ${messages.size} unread chats...")

                for ((index, message) in messages.withIndex()) {
                    if (!isRunning()) {
                        Log.d(TAG, "⏸️ Service stopped during chat handling")
                        break
                    }

                    try {
                        val shouldSkip = shouldSkipMessage(message, service)
                        handleChat(service, message, scope, shouldSkip)
                        state.addProcessed(message.cacheKey)

                        if (index < messages.size - 1) {
                            delay(700L)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error handling chat ${index + 1}: ${e.message}", e)
                        if (index < messages.size - 1) {
                            delay(700L)
                        }
                    }
                }

                try {
                    state.saveProcessed(service)
                    Log.d(TAG, "✅ Saved processed chats")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to save processed: ${e.message}", e)
                }

                Log.d(TAG, "✅ All ${messages.size} chats handled")

                try {
                    delay(2000L)
                    Log.d(TAG, "✅ Delay 2000ms complete")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Delay failed: ${e.message}", e)
                }

                try {
                    UiActionQueue.postAndAwait {
                        Log.d(TAG, "🔄 UiActionQueue flushed")
                    }
                    Log.d(TAG, "✅ UiActionQueue flush complete")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to flush queue: ${e.message}", e)
                }

                try {
                    delay(1500L)
                    Log.d(TAG, "✅ Final delay 1500ms complete")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Final delay failed: ${e.message}", e)
                }

                Log.d(TAG, "✅ About to check isRunning...")

                if (!isRunning()) {
                    Log.d(TAG, "⏸️ Service stopped after chat handling")
                    state.isHandlingChat = false
                    return@withContext
                }

                Log.d(TAG, "✅ isRunning check passed")

                val timeSinceRestart = System.currentTimeMillis() - state.lastRestartTime
                if (timeSinceRestart < 60000L) {
                    Log.d(TAG, "⏸️ Skip restart - baru restart ${timeSinceRestart / 1000}s lalu")
                    state.isHandlingChat = false
                    return@withContext
                }

                Log.d(TAG, "✅ Time check passed")

                val shouldScheduleRestart = isRunning() && state.shouldRelog()

                Log.d(TAG, "✅ About to reset isHandlingChat flag")
                state.isHandlingChat = false
                Log.d(TAG, "✅ isHandlingChat flag reset")

                if (shouldScheduleRestart) {
                    Log.d(TAG, "🔄 Scheduling restart after chat handling...")

                    try {
                        val persona = PersonaManager.getPersona(service)
                        val rawRelogOption = persona?.relogOption ?: 1

                        scope.launch {
                            Log.d(TAG, "🔄 Restart coroutine started")
                            delay(3000L)

                            if (!isRunning()) {
                                Log.d(TAG, "⏸️ Service stopped before restart")
                                return@launch
                            }

                            if (state.isHandlingChat) {
                                Log.d(TAG, "⏸️ Chat handling started, cancel restart")
                                return@launch
                            }

                            state.needRestart = true
                            Log.d(TAG, "✅ Restart flag set")
                        }
                        Log.d(TAG, "✅ Restart coroutine launched")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to launch restart coroutine: ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "ℹ️ No restart needed")
                }

                Log.d(TAG, "✅ handleUnreadChats COMPLETE")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ FATAL ERROR in handleUnreadChats: ${e.message}", e)
            e.printStackTrace()
            state.isHandlingChat = false
        } finally {
            Log.d(TAG, "✅ handleUnreadChats FINALLY block")
        }
    }
}
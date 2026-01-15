package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.ngontol.PersonaManager
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.config.AppConfigs
import com.example.ngontol.managers.ChatHistoryManager
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.processors.OpenerSelector
import com.example.ngontol.processors.ReplyGenerator
import com.example.ngontol.utils.sanitize
import com.example.ngontol.helpers.MessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object S4Service : BaseAppService(AppConfigs.ICHAT) {
    // ✅ NODE CACHE for performance
    private var cachedNodes: List<AccessibilityNodeInfo>? = null
    private var cacheTimestamp = 0L
    private const val CACHE_DURATION = 800L // 800ms cache

    // ✅ Scan throttling
    private var lastFullScanTime = 0L

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "s4 started")

        // ✅ FORCE LAUNCH + SCAN
        scope.launch {
            delay(2000L) // Tunggu service ready

            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (root == null) {
                Log.d(TAG, "🚀 App not active, launching...")
                launchApp(service)
                delay(5000L) // Tunggu app load
            } else {
                Log.d(TAG, "✅ App already active")
            }

            // ✅ START PERIODIC SCAN
            startPeriodicScan(service, scope, isRunning)
        }

        // ✅ Still listen to normal accessibility events
        onAccessibilityEvent(service, scope, isRunning)
    }

    // ✅ CACHED getAllNodes for performance
    private fun getAllNodesCached(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val now = System.currentTimeMillis()

        // ✅ Return cache if fresh
        if (cachedNodes != null && now - cacheTimestamp < CACHE_DURATION) {
//            Log.v(TAG, "📦 Using cached nodes (${cachedNodes!!.size} nodes)")
            return cachedNodes!!
        }

        // ✅ Rebuild cache
        val nodes = getAllNodes(root)
        cachedNodes = nodes
        cacheTimestamp = now
//        Log.v(TAG, "🔄 Rebuilt node cache (${nodes.size} nodes)")
        return nodes
    }

    // ✅ Clear cache when screen changes
    private fun clearNodeCache() {
        cachedNodes = null
        cacheTimestamp = 0L
        Log.v(TAG, "🗑️ Node cache cleared")
    }

    // ✅ PERIODIC SCAN LOOP
    private suspend fun startPeriodicScan(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        Log.d(TAG, "🔄 Starting periodic scan loop...")

        while (isRunning()) {
            try {
                val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)

                if (root == null) {
                    Log.v(TAG, "⏸️ App not active, skip scan")
                    delay(5000L)
                    continue
                }

                val now = System.currentTimeMillis()
                if (state.isHandlingChat) {
                    Log.v(TAG, "⏸️ Still handling chat, skip scan")
                    delay(2000L)
                    continue
                }

                if (state.shouldSkipRestart(now) || state.shouldSkipPostRestart(now)) {
                    delay(2000L)
                    continue
                }

                if (!state.shouldProcess(now)) {
                    delay(2000L)
                    continue
                }

                // ✅ THROTTLE: Skip if scanned recently
                if (now - lastFullScanTime < 2000L) {
                    delay(2000L)
                    continue
                }
                lastFullScanTime = now

                Log.d(TAG, "🔍 Scanning for chats...")
                state.updateLastRun(now)

                handleCancelButtons(service, root)

                // ✅ GET ALL NODES (with cache)
                val allNodes = getAllNodesCached(root)

                // ✅ FILTER CHAT ITEMS (yang punya separator "·")
                val chatNodes = allNodes.filter { node ->
                    !WindowFilterHelper.isOwnOverlay(node) &&
                            node.isClickable &&
                            node.contentDescription != null &&
                            node.contentDescription.contains("·") &&
                            Rect().apply { node.getBoundsInScreen(this) }.let { rect ->
                                rect.width() > 0 &&
                                        rect.height() > 0 &&
                                        rect.top > 300 && // ✅ FILTER: Skip tabs, focus area chat
                                        rect.bottom < 1400
                            }
                }

                Log.d(TAG, "💬 Found ${chatNodes.size} chat items")

                if (chatNodes.isEmpty()) {
                    Log.v(TAG, "📭 No chat items found")
                    delay(5000L)
                    continue
                }

                // ✅ DEBUG: Log top 3
                chatNodes.take(3).forEachIndexed { index, node ->
                    val rect = Rect().apply { node.getBoundsInScreen(this) }
                    val shortDesc = node.contentDescription.toString().take(50).replace("\n", " ")
                    Log.d(TAG, "  [$index] $shortDesc... | Y: ${rect.top}")
                }

                // ✅ GET TOP ITEM (paling atas = Y terkecil)
                val topItem = chatNodes.minByOrNull { node ->
                    Rect().apply { node.getBoundsInScreen(this) }.top
                }

                if (topItem != null) {
                    Log.d(TAG, "🎯 Processing top chat...")

                    // ✅ LANGSUNG HANDLE (extract nama nanti di dalam)
                    handleChatDirect(service, topItem, scope)

                    // ✅ Update last run agar tidak spam click
                    state.updateLastRun(System.currentTimeMillis())
                }

                // ✅ Auto clear cache
                if (state.shouldClearCache()) {
                    state.clearCache()
                }

                delay(5000L)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Scan error: ${e.message}", e)
                clearNodeCache() // Clear cache on error
                delay(5000L)
            }
        }

        Log.d(TAG, "🛑 Periodic scan stopped")
    }

    // ✅ NEW: Direct handle chat (no message extraction before click)
    private suspend fun handleChatDirect(
        service: AccessibilityService,
        chatNode: AccessibilityNodeInfo,
        scope: CoroutineScope
    ) {
        state.isHandlingChat = true
        clearNodeCache() // ✅ Clear cache before processing

        try {
            val rect = Rect().apply { chatNode.getBoundsInScreen(this) }
            val shortDesc = chatNode.contentDescription.toString().take(30).replace("\n", " ")
            Log.d(TAG, "🎯 Clicking: $shortDesc... at Y: ${rect.top}")

            // ✅ KLIK CHAT NODE
            if (!clickWithGesture(service, chatNode)) {
                Log.e(TAG, "❌ Failed to click chat item")
                return
            }

            Log.d(TAG, "✅ Clicked chat, waiting for chat to open...")
            delay(1000L)

            clearNodeCache() // ✅ Clear cache after screen transition

            // ✅ Handle cancel button setelah klik
            val rootAfterClick = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (rootAfterClick != null) {
                handleCancelButtons(service, rootAfterClick)
                delay(200L)
            }

            // ✅ EXTRACT NAMA dari chat yang sudah terbuka
            val actualName = extractOpenedChatName(service)
            if (actualName.isNullOrBlank()) {
                Log.e(TAG, "❌ Cannot extract name from opened chat")
                backFourTimes(service)
                return
            }

            Log.d(TAG, "📛 Opened chat: $actualName")

            // ✅ CHECK BLACKLIST
            val persona = PersonaManager.getPersona(service)
            if (persona != null) {
                val blacklist = (listOf("agen", "agency", "partner", "mengikutimu.", "bernadus") + persona.blacklist).distinct()
                val nameLow = actualName.lowercase()

                if (blacklist.any { nameLow.contains(it) }) {
                    Log.d(TAG, "⏭️ Skipped - blacklisted user")
                    delay(300L)
                    backFourTimes(service)
                    return
                }
            }

            // ✅ WAIT FOR INPUT FIELD
            val input = waitForInput(service) ?: run {
                Log.e(TAG, "❌ Input not found")
                backFourTimes(service)
                return
            }

            // ✅ GET LAST MESSAGE (dari dalam chat)
            val lastMessage = waitForLastMessage(service) ?: OpenerSelector.getOpener()
            Log.d(TAG, "📨 Last message: $lastMessage")

            val actualMessage = ChatMessage(actualName, lastMessage, 1)

            val personaFinal = PersonaManager.getPersona(service) ?: run {
                backFourTimes(service)
                return
            }

            val safeName = actualName.sanitize()
            val userId = safeName.ifBlank { "user_${actualName.hashCode()}" }

            // ✅ GENERATE & SEND REPLY
            // ✅ GENERATE & SEND REPLY
            scope.launch(Dispatchers.IO) {  // ← PASTIKAN mulai dari IO dispatcher
                try {
                    Log.d(TAG, "⏳ Generating reply (async)...")

                    // ✅ Generate reply di background thread
                    val reply = ReplyGenerator.generate(service, actualMessage, personaFinal, personaFinal.address)

                    Log.d(TAG, "💬 Reply: ${reply.take(50)}...")

                    // ✅ SEND MESSAGE - pindah ke background dengan withContext
                    withContext(Dispatchers.IO) {
                        MessageSender.sendWithEnter(service, input, reply)
                    }

                    // Wait message sent
                    delay(800L)

                    // ✅ Save history
                    ChatHistoryManager.addMessage(
                        service, userId, actualName, personaFinal.botName,
                        actualMessage.message, reply
                    )

                    // Exit chat
                    delay(400L)

                    // ✅ Back juga di background
                    withContext(Dispatchers.IO) {
                        backFourTimes(service)
                    }

                    Log.d(TAG, "✅ Done: $actualName")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to process chat: ${e.message}", e)
                    try {
                        withContext(Dispatchers.IO) {
                            backFourTimes(service)
                        }
                    } catch (_: Exception) {}
                }
            }

        } finally {
            clearNodeCache() // ✅ Clear cache on exit
            state.isHandlingChat = false
        }
    }

    // ✅ HANDLE SINGLE CHAT
    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        clearNodeCache() // ✅ Clear cache at start

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return

        handleCancelButtons(service, root)
        delay(300L)

        // ✅ GET ALL NODES RECURSIVELY (with cache)
        val allNodes = getAllNodesCached(root)

        // ✅ FILTER CHAT ITEMS
        val targetNodes = allNodes.filter { node ->
            !WindowFilterHelper.isOwnOverlay(node) &&
                    node.isClickable &&
                    node.contentDescription != null &&
                    node.contentDescription.contains("·") &&
                    Rect().apply { node.getBoundsInScreen(this) }.let { rect ->
                        rect.width() > 0 && rect.height() > 0 &&
                                rect.top > 150 && rect.bottom < 1400
                    }
        }

        if (targetNodes.isEmpty()) {
            Log.w(TAG, "⚠️ No chat items found")
            return
        }

        // ✅ GET TOP ITEM
        val topItem = targetNodes.minByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        } ?: return

        val topRect = Rect()
        topItem.getBoundsInScreen(topRect)
        Log.d(TAG, "🎯 Clicking: ${topItem.contentDescription} | Position: (${topRect.left}, ${topRect.top})")

        if (!clickWithGesture(service, topItem)) {
            Log.e(TAG, "❌ Failed to click chat item")
            return
        }

        Log.d(TAG, "✅ Clicked top chat")
        delay(1000L)

        clearNodeCache() // ✅ Clear cache after screen transition

        val rootAfterClick = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        if (rootAfterClick != null) {
            handleCancelButtons(service, rootAfterClick)
            delay(200L)
        }

        val actualName = extractOpenedChatName(service)
        if (actualName.isNullOrBlank()) {
            Log.e(TAG, "❌ Cannot extract name")
            backFourTimes(service)
            return
        }

        Log.d(TAG, "📛 Opened chat: $actualName")

        if (shouldSkip) {
            Log.d(TAG, "⏭️ Skipped - read only")
            delay(300L)
            backFourTimes(service)
            return
        }

        val input = waitForInput(service) ?: run {
            Log.e(TAG, "❌ Input not found")
            backFourTimes(service)
            return
        }

        val lastMessage = waitForLastMessage(service) ?: OpenerSelector.getOpener()
        val actualMessage = ChatMessage(actualName, lastMessage, 1)

        val persona = PersonaManager.getPersona(service) ?: run {
            backFourTimes(service)
            return
        }

        val safeName = actualName.sanitize()
        val userId = safeName.ifBlank { "user_${actualName.hashCode()}" }

        scope.launch(Dispatchers.IO) {  // ← TAMBAHIN Dispatchers.IO di sini!
            try {
                // Log.d(TAG, "⏳ Generating reply (async)...") ← HAPUS atau pindah ke background

                // ✅ Generate reply di background thread
                val reply = ReplyGenerator.generate(service, actualMessage, persona, persona.address)

                // Log.d(TAG, "💬 Reply: ${reply.take(50)}...") ← HAPUS atau pindah ke background

                // ✅ PASTIKAN sendWithEnter jalan di IO
                MessageSender.sendWithEnter(service, input, reply)
                delay(800L)

                // ✅ Save history
                ChatHistoryManager.addMessage(
                    service, userId, actualName, persona.botName,
                    actualMessage.message, reply
                )

                delay(400L)

                // ✅ Back juga di background
                backFourTimes(service)

            } catch (e: Exception) {
                // Log.e(TAG, "❌ Failed: ${e.message}", e) ← HAPUS logging error yang berat
                try {
                    backFourTimes(service)
                } catch (_: Exception) {}
            } finally {
                clearNodeCache()
            }
        }    }

    // ✅ BACK FUNCTIONS (optimized timing)
    private suspend fun backFourTimes(service: AccessibilityService) {
        try {
            Log.d(TAG, "🔙 Exiting...")

            // Back 1: Close keyboard
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(250L)

            // Back 2: Exit chat
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(250L)

            Log.d(TAG, "✅ Exited (2x back)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exit error: ${e.message}")
        } finally {
            clearNodeCache()
        }
    }

    // ✅ EXTRACT NAME from opened chat
    private fun extractOpenedChatName(service: AccessibilityService): String? {
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return null
        val allNodes = getAllNodesCached(root) // ✅ Use cached

        val headerNode = allNodes.firstOrNull { node ->
            !WindowFilterHelper.isOwnOverlay(node) &&
                    node.isClickable &&
                    node.contentDescription != null &&
                    node.contentDescription.isNotEmpty() &&
                    !node.contentDescription.contains("·") &&
                    Rect().apply { node.getBoundsInScreen(this) }.let { it.top < 300 }
        }

        val name = headerNode?.contentDescription?.toString()?.trim()
        if (!name.isNullOrBlank()) return name

        val textNode = allNodes.firstOrNull { node ->
            !WindowFilterHelper.isOwnOverlay(node) &&
                    node.text != null &&
                    node.text.isNotEmpty() &&
                    !node.text.contains("·") &&
                    Rect().apply { node.getBoundsInScreen(this) }.let { it.top < 300 }
        }

        return textNode?.text?.toString()?.trim()
    }

    // ✅ WAIT FOR INPUT (optimized with delay instead of Thread.sleep)
    private suspend fun waitForInput(
        service: AccessibilityService,
        timeoutMs: Long = 3000
    ): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: continue
            val allNodes = getAllNodesCached(root) // ✅ Use cached

            val input = allNodes.firstOrNull { node ->
                !WindowFilterHelper.isOwnOverlay(node) &&
                        node.className?.contains("EditText") == true &&
                        node.isClickable &&
                        node.isFocusable
            }

            if (input != null) {
                Log.d(TAG, "✅ Input found")
                return input
            }

            delay(100)
        }

        Log.e(TAG, "❌ Input not found after ${timeoutMs}ms")
        return null
    }

    // ✅ WAIT FOR LAST MESSAGE (optimized with delay instead of Thread.sleep)
    private suspend fun waitForLastMessage(
        service: AccessibilityService,
        timeoutMs: Long = 2000
    ): String? {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: continue
            val allNodes = getAllNodesCached(root) // ✅ Use cached

            val messages = allNodes
                .filter { node ->
                    !WindowFilterHelper.isOwnOverlay(node) &&
                            node.className == "android.view.View" &&
                            node.isClickable &&
                            node.contentDescription?.isNotEmpty() == true &&
                            node.contentDescription.toString().trim().length <= 100 &&
                            Rect().apply { node.getBoundsInScreen(this) }.let {
                                it.top in 800..1100 && it.left < 300
                            }
                }
                .map { node -> node.contentDescription.toString().trim() }
                .filter { msg -> msg.isNotBlank() }
                .sortedByDescending { msg -> -msg.length }

            if (messages.isNotEmpty()) {
                val lastMsg = messages.first()
                Log.d(TAG, "✅ Last message: $lastMsg")
                return lastMsg
            }

            delay(100)
        }

        Log.w(TAG, "⚠️ Last message not found, using opener")
        return null
    }

    // ✅ CLICK WITH GESTURE
    private fun clickWithGesture(
        service: AccessibilityService,
        node: AccessibilityNodeInfo
    ): Boolean {
        if (WindowFilterHelper.isOwnOverlay(node)) {
            Log.w(TAG, "⚠️ Node is overlay, skip click")
            return false
        }

        val rect = Rect().apply { node.getBoundsInScreen(this) }

        if (rect.width() <= 0 || rect.height() <= 0) {
            Log.w(TAG, "⚠️ Invalid rect: $rect")
            return false
        }

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        Log.d(TAG, "🔘 Clicking at: ($centerX, $centerY)")

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

    // ✅ OPTIMIZED: Iterative traversal instead of recursive
    private fun getAllNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()

        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            nodes.add(node)

            // Add children in reverse order for proper traversal
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }

        return nodes
    }

    // ✅ HELPER: Launch app
    private fun launchApp(service: AccessibilityService) {
        val intent = service.packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.startActivity(intent)
            state.lastAppLaunchTime = System.currentTimeMillis()
            Log.d(TAG, "✅ App launched")
        } else {
            Log.e(TAG, "❌ Cannot launch app - intent null")
        }
    }

    // ✅ OVERRIDE: Skip auto-launch (prevent double launch)
    override fun shouldSkipLaunch(): Boolean {
        val timeSinceLaunch = System.currentTimeMillis() - state.lastAppLaunchTime
        return timeSinceLaunch < 10000L
    }

    // ✅ EXTRACT CHAT MESSAGE from node
    override fun extractChatMessage(
        row: AccessibilityNodeInfo,
        service: AccessibilityService
    ): ChatMessage? {
        val contentDesc = row.contentDescription?.toString() ?: return null

        if (!contentDesc.contains("·")) return null

        val parts = contentDesc.split("·")

        if (parts.size < 4) return null

        val name = parts[0].trim()
        val message = parts[3].trim()

        if (name.isBlank() || message.isBlank()) return null

        val key = "$name|$message"
        if (key in state.processed) return null

        val unreadCount = parts.lastOrNull()?.toIntOrNull() ?: 0

        return if (unreadCount > 0) {
            ChatMessage(name, message, unreadCount)
        } else {
            state.addProcessed(key)
            null
        }
    }

    // ✅ HANDLE CANCEL BUTTONS
//    override fun handleCancelButtons(service: AccessibilityService, root: AccessibilityNodeInfo) {
//        val allNodes = getAllNodesCached(root) // ✅ Use cached
//
//        val cancelBtn = allNodes.firstOrNull { node ->
//            !WindowFilterHelper.isOwnOverlay(node) &&
//                    node.className?.contains("Button") == true &&
//                    node.isClickable &&
//                    (node.contentDescription?.contains("batal", ignoreCase = true) == true ||
//                            node.contentDescription?.contains("cancel", ignoreCase = true) == true ||
//                            node.text?.contains("batal", ignoreCase = true) == true ||
//                            node.text?.contains("cancel", ignoreCase = true) == true)
//        }
//
//        if (cancelBtn != null) {
//            Log.d(TAG, "🚫 Cancel button found, clicking...")
//            val clicked = clickWithGesture(service, cancelBtn)
//            if (clicked) {
//                Log.d(TAG, "✅ Cancel button clicked")
//            }
//        }
//    }
}
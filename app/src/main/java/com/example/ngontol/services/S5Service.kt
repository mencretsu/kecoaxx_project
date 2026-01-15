package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.ngontol.PersonaManager
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.config.AppConfigs
import com.example.ngontol.helpers.MessageSender
import com.example.ngontol.helpers.NodeFinder
import com.example.ngontol.managers.ChatHistoryManager
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.processors.OpenerSelector
import com.example.ngontol.processors.ReplyGenerator
import com.example.ngontol.utils.sanitize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

object S5Service : BaseAppService(AppConfigs.SIYA) {

    private var isProcessingChat = false
    private var lastClickTime = 0L

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S5Service started")
        onAccessibilityEvent(service, scope, isRunning)
    }

    override fun extractChatMessage(
        row: AccessibilityNodeInfo,
        service: AccessibilityService
    ): ChatMessage? {
        val rect = Rect().apply { row.getBoundsInScreen(this) }
        if (rect.width() <= 0 || rect.height() <= 0) return null

        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return null

        val hasUserTag = root.findAccessibilityNodeInfosByViewId("com.zr.siya:id/qiv_avatar")
            ?.any { tagNode ->
                if (WindowFilterHelper.isOwnOverlay(tagNode)) return@any false
                val rectTag = Rect().apply { tagNode.getBoundsInScreen(this) }
                rect.contains(rectTag)
            } ?: false

        if (!hasUserTag) return null

        val rowId = row.viewIdResourceName
        if (rowId == "com.zr.siya:id/crvFamily" || rowId == "com.zr.siya:id/crv_enter" || rowId == "com.zr.siya:id/tv_name") {
            return null
        }

        return ChatMessage("", "", 1)
    }

//    // ✅ Keep non-suspend for BaseAppService compatibility
//    override fun handleCancelButtons(service: AccessibilityService, root: AccessibilityNodeInfo) {
//        val cancelBtn = NodeFinder.findCancelButton(root, config, service.packageName)
//        cancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
//    }

    // ✅ NEW: Suspend version for internal use
    private suspend fun handleCancelButtonsAsync(service: AccessibilityService, root: AccessibilityNodeInfo) {
        withContext(Dispatchers.IO) {
            val cancelBtn = NodeFinder.findCancelButton(root, config, service.packageName)
            if (cancelBtn != null) {
                withContext(Dispatchers.Main) {
                    cancelBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
        }
    }

    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        if (isProcessingChat) return
        isProcessingChat = true

        try {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (root == null) {
                Log.w(TAG, "⚠️ Root node is null")
                return
            }

            // ✅ FIX: Retry check dengan timeout
            var isInsideChat = false
            repeat(3) { attempt ->
                val checkRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                isInsideChat = checkRoot?.findAccessibilityNodeInfosByViewId("com.zr.siya:id/tv_chat_title")
                    ?.any { !WindowFilterHelper.isOwnOverlay(it) } ?: false

                if (isInsideChat) {
                    Log.d(TAG, "✅ Chat detected on attempt ${attempt + 1}")
                    return@repeat
                }

                if (attempt < 2) delay(300L)
            }

            if (isInsideChat) {
                Log.d(TAG, "⚠️ Already inside chat, skipping")
                return
            }

            // ✅ Use async version to prevent main thread blocking
            handleCancelButtonsAsync(service, root)
            delay(300L)

            val now = System.currentTimeMillis()
            if (now - lastClickTime < 1500) {
                Log.d(TAG, "⏳ Debouncing click (${now - lastClickTime}ms)")
                return
            }

            // ✅ Move expensive node finding to background
            val targetRow = withContext(Dispatchers.IO) {
                root.findAccessibilityNodeInfosByViewId(config.listViewId)
                    .firstOrNull {
                        !WindowFilterHelper.isOwnOverlay(it) &&
                                it.isClickable &&
                                extractChatMessage(it, service) != null
                    }
            }

            if (targetRow == null) {
                Log.w(TAG, "⚠️ No valid user chat found")
                return
            }

            lastClickTime = now

            // ✅ Click on main thread
            val clicked = withContext(Dispatchers.Main) {
                targetRow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            if (!clicked) {
                Log.e(TAG, "❌ Failed to click row")
                return
            }

            Log.d(TAG, "✅ Clicked chat row, waiting for chat to open...")
            delay(800L)

            // ✅ Extract name dengan coroutine (non-blocking)
            val actualName = extractOpenedChatNameAsync(service)
            if (actualName == null) {
                Log.e(TAG, "❌ Failed to get chat name, backing out...")
                performBackOnce(service)
                return
            }

            Log.d(TAG, "✅ Opened chat with: $actualName")

            if (shouldSkip) {
                Log.d(TAG, "⏭️ Skipping chat (shouldSkip=true)")
                performBackOnce(service)
                return
            }

            val input = NodeFinder.waitForInput(service, config)
            if (input == null) {
                Log.w(TAG, "⚠️ Input field not found after timeout")
                val root2 = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                val isStillInChat = root2?.findAccessibilityNodeInfosByViewId("com.zr.siya:id/tv_back")
                    ?.any { !WindowFilterHelper.isOwnOverlay(it) } ?: false

                if (!isStillInChat) {
                    Log.d(TAG, "📍 Not in chat anymore, just returning")
                } else {
                    Log.d(TAG, "🔙 Still inside chat, forcing back...")
                    performBackOnce(service)
                }
                return
            }

            // ✅ Wait for message dengan coroutine
            val lastMessage = waitForLastMessageAsync(service)
            if (lastMessage == null) {
                Log.d(TAG, "⚠️ No message found, using opener")
                val openerReply = OpenerSelector.getOpener()
                MessageSender.send(service, input, openerReply, config)
                delay(400)

                val rootAfterSend = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                if (rootAfterSend != null) {
                    handleCancelButtonsAsync(service, rootAfterSend)
                }
                performBackOnce(service)
                return
            }

            val actualMessage = ChatMessage(actualName, lastMessage, 1)
            Log.d(TAG, "📩 Message from $actualName: ${lastMessage.take(50)}...")

            val persona = PersonaManager.getPersona(service)
            if (persona == null) {
                Log.e(TAG, "❌ Failed to get persona")
                performBackOnce(service)
                return
            }

            val safeName = actualName.sanitize()
            val userId = safeName.ifBlank { "user_${actualName.hashCode()}" }

            // ✅ Launch reply di background dengan Dispatchers.IO
            scope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "🤖 Generating reply...")
                    val reply = ReplyGenerator.generate(service, actualMessage, persona)
                    Log.d(TAG, "✅ Reply: ${reply.take(50)}...")

                    // Switch ke Main untuk UI operations
                    withContext(Dispatchers.Main) {
                        MessageSender.send(service, input, reply, config)

                        ChatHistoryManager.addMessage(
                            service, userId, actualName, persona.botName,
                            actualMessage.message, reply
                        )

                        delay(400)

                        val rootAfterSend = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                        if (rootAfterSend != null) {
                            handleCancelButtonsAsync(service, rootAfterSend)
                        }
                        performBackOnce(service)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to reply: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        try {
                            performBackOnce(service)
                        } catch (backError: Exception) {
                            Log.e(TAG, "❌ Failed to go back: ${backError.message}")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in handleChat: ${e.message}", e)
        } finally {
            if (!isProcessingChat) {
                // Already reset
            } else {
                delay(400)
                isProcessingChat = false
            }
        }
    }
    private suspend fun performBackOnce(service: AccessibilityService) {
        try {
            // First back
            withContext(Dispatchers.Main) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            delay(700L)

            // ✅ Check apakah sudah di list view (bukan di chat)
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)

            // Chat screen punya tv_chat_title, list screen TIDAK punya
            val stillInChat = root?.findAccessibilityNodeInfosByViewId("com.zr.siya:id/tv_back")
                ?.any { !WindowFilterHelper.isOwnOverlay(it) } ?: false

            if (stillInChat) {
                Log.d(TAG, "🔙 Still in chat screen (tv_chat_title detected), backing again...")
                withContext(Dispatchers.Main) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
                delay(700L)

                // Final check
                val checkRoot = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                val finalCheck = checkRoot?.findAccessibilityNodeInfosByViewId("com.zr.siya:id/tv_back")
                    ?.any { !WindowFilterHelper.isOwnOverlay(it) } ?: false

                if (finalCheck) {
                    Log.w(TAG, "⚠️ Still in chat after 2 back attempts, forcing 3rd back...")
                    withContext(Dispatchers.Main) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                    delay(500L)
                } else {
                    Log.d(TAG, "✅ Successfully returned to chat list (2nd back)")
                }
            } else {
                Log.d(TAG, "✅ Successfully returned to chat list (1st back)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in performBackOnce: ${e.message}")
        } finally {
            isProcessingChat = false
            Log.d(TAG, "🔓 Processing flag reset")
        }
    }
    // ✅ Non-blocking version menggunakan delay() instead of Thread.sleep()
    private suspend fun extractOpenedChatNameAsync(
        service: AccessibilityService,
        timeoutMs: Long = 8000,
        retryDelayMs: Long = 200
    ): String? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            yield()

            // ✅ Move expensive operation to background
            val name = withContext(Dispatchers.IO) {
                val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?: return@withContext null

                val titleNode = root.findAccessibilityNodeInfosByViewId("com.zr.siya:id/tv_chat_title")
                    ?.firstOrNull {
                        !WindowFilterHelper.isOwnOverlay(it) &&
                                it.text != null && it.text.isNotBlank()
                    }

                titleNode?.text?.toString()?.trim()
            }

            if (!name.isNullOrBlank()) {
                return name
            }

            delay(retryDelayMs)
        }

        Log.w(TAG, "⚠️ Timeout: Failed to get chat name after ${timeoutMs}ms")
        return null
    }

    // ✅ Non-blocking version with background processing
    private suspend fun waitForLastMessageAsync(
        service: AccessibilityService,
        timeoutMs: Long = 1500
    ): String? {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            yield()

            // ✅ Move expensive operation to background
            val message = withContext(Dispatchers.IO) {
                val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                    ?: return@withContext null

                val lastBubble = root.findAccessibilityNodeInfosByViewId("com.zr.siya:id/cl_bubble")
                    ?.filter { !WindowFilterHelper.isOwnOverlay(it) }
                    ?.lastOrNull()
                    ?: return@withContext null

                // Priority 1: Reply content
                val replyMsg = lastBubble.findAccessibilityNodeInfosByViewId("com.zr.siya:id/tv_reply_content")
                    ?.firstOrNull()?.text?.toString()?.trim()

                if (!replyMsg.isNullOrBlank()) return@withContext replyMsg

                // Priority 2: Normal TextView (recursive search)
                val allText = mutableListOf<String>()
                fun traverse(node: AccessibilityNodeInfo) {
                    if (node.className?.contains("TextView") == true) {
                        node.text?.toString()?.trim()?.let {
                            if (it.isNotBlank()) allText.add(it)
                        }
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { traverse(it) }
                    }
                }
                traverse(lastBubble)

                allText.maxByOrNull { it.length }
            }

            if (!message.isNullOrBlank()) return message

            delay(100)
        }
        return null
    }
}
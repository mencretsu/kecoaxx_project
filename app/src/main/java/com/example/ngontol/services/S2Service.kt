package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.example.ngontol.PersonaManager
import com.example.ngontol.Persona
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.config.AppConfigs
import com.example.ngontol.helpers.NodeFinder
import com.example.ngontol.helpers.MessageSender
import com.example.ngontol.managers.ChatHistoryManager
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.processors.OpenerSelector
import com.example.ngontol.processors.ReplyGenerator
import com.example.ngontol.utils.sanitize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object S2Service : BaseAppService(AppConfigs.MOMO) {

    private val processingMutex = Mutex()
    private var isProcessingChat = false

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S2Service started")
        onAccessibilityEvent(service, scope, isRunning)
    }

    // ✅ Return dummy message karena nama diambil setelah chat dibuka
    override fun extractChatMessage(
        row: AccessibilityNodeInfo,
        service: AccessibilityService
    ): ChatMessage? {
        return ChatMessage("", "", 1)
    }

    // ✅ Fungsi baru khusus untuk S2Service (bukan override)
    private suspend fun handleCancelButtonsWithGesture(service: AccessibilityService, root: AccessibilityNodeInfo) {
        val cancelBtn = NodeFinder.findCancelButton(root, config, service.packageName)

        if (cancelBtn != null) {
            Log.d(TAG, "🚫 Cancel button found, clicking with gesture...")
            if (clickWithGesture(service, cancelBtn)) {
                Log.d(TAG, "✅ Cancel button clicked")
                delay(800L)
            } else {
                Log.w(TAG, "⚠️ Failed to click cancel button")
            }
        }
    }

    // ✅ Main handleChat dengan Mutex lock
    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        // ✅ Thread-safe check
        processingMutex.withLock {
            if (isProcessingChat) {
                Log.d(TAG, "⏸️ Already processing, skip...")
                return
            }
            isProcessingChat = true
        }

        try {
            processChat(service, shouldSkip)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in handleChat: ${e.message}", e)
        } finally {
            isProcessingChat = false
        }
    }

    // ✅ Main chat processing logic
    private suspend fun processChat(
        service: AccessibilityService,
        shouldSkip: Boolean
    ) {
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: run {
            Log.w(TAG, "⚠️ Root not found")
            return
        }

        // ✅ Handle cancel button dulu
        handleCancelButtonsWithGesture(service, root)

        // ✅ Ambil chat list
        val rows = root.findAccessibilityNodeInfosByViewId(config.listViewId)
            .filter { !WindowFilterHelper.isOwnOverlay(it) && it.isClickable }

        val topRow = rows.firstOrNull { rowNode ->
            val rect = Rect().apply { rowNode.getBoundsInScreen(this) }
            rect.width() > 0 && rect.height() > 0
        } ?: run {
//            Log.w(TAG, "⚠️ No valid rows")
            return
        }

        // ✅ Click chat teratas dengan gesture
        if (!clickWithGesture(service, topRow)) {
            Log.e(TAG, "❌ Failed to click top row")
            return
        }

        Log.d(TAG, "✅ Clicked top chat")
        delay(500L)

        // ✅ Check cancel button lagi setelah buka chat
        val rootAfterClick = WindowFilterHelper.getTargetRootNode(service, config.packageName)
        if (rootAfterClick != null) {
            handleCancelButtonsWithGesture(service, rootAfterClick)
        }

        // ✅ Extract nama dari chat yang udah kebuka
        val actualName = extractOpenedChatName(service)
        if (actualName.isNullOrBlank()) {
//            Log.e(TAG, "❌ Cannot extract name from opened chat")
            backOnce(service)
            return
        }

//        Log.d(TAG, "📛 Opened chat: $actualName")

        // ✅ Skip kalo cuma mau baca doang (shouldSkip = true)
        if (shouldSkip) {
            Log.d(TAG, "⭐️ Skipped: $actualName (read only)")
            delay(400L)
            backOnce(service)
            return
        }

        // ✅ Process reply
        try {
            // Wait input field
            val input = NodeFinder.waitForInput(service, config) ?: run {
                Log.e(TAG, "❌ Input field not found")
                backOnce(service)
                return
            }

//            delay(500L)

            // ✅ Ambil last message atau pake opener
            val lastMessage = waitForLastMessage(service) ?: OpenerSelector.getOpener()
            val actualMessage = ChatMessage(actualName, lastMessage, 1)

//            Log.d(TAG, "💬 Last message: $lastMessage")

            // ✅ Get persona
            val persona = PersonaManager.getPersona(service) ?: run {
                Log.e(TAG, "❌ No persona found")
                backOnce(service)
                return
            }

            // ✅ Get user city
            val userCity = waitForCity(service, persona)
//            Log.d(TAG, "📍 User city: $userCity")

            // ✅ Generate reply
            val reply = ReplyGenerator.generate(service, actualMessage, persona, userCity)
//            Log.d(TAG, "📝 Reply: $reply")

            // ✅ Send reply dengan MessageSender.sendWithPasteGesture
            MessageSender.sendWithGestureFast(service, input, reply, config)

            // ✅ Save chat history
            val safeName = actualName.sanitize()
            val userId = safeName.ifBlank { "user_${actualName.hashCode()}" }

            ChatHistoryManager.addMessage(
                service, userId, actualName, persona.botName,
                actualMessage.message, reply
            )

            delay(300L)
            // ✅ Check cancel button & reward view sebelum back
            val rootAfterSend = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (rootAfterSend != null) {
                handleCancelButtonsWithGesture(service, rootAfterSend)
                delay(300L)
            }

            // ✅ Back pertama
            backOnce(service)
            delay(700L)

            // ✅ Check apakah masih ada ivLoverTree setelah back pertama
            val rootAfterBack = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            if (rootAfterBack != null) {
                handleCancelButtonsWithGesture(service, rootAfterBack)
                delay(800L)  // ← Naikin delay tunggu UI stabil

                // ✅ REFRESH ROOT setelah cancel (karena UI berubah!)
                val rootAfterCancel = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                if (rootAfterCancel != null) {
                    Log.d(TAG, "🔍 Before handleCancelButtons")
                    handleCancelButtonsWithGesture(service, rootAfterCancel)
                    Log.d(TAG, "✅ After handleCancelButtons")
                    delay(500L)
                    Log.d(TAG, "✅ After delay 500ms")

                    val stillHasTree = rootAfterCancel.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/ivLoverTree")
                        ?.firstOrNull()

                    if (stillHasTree != null) {
                        Log.d(TAG, "🌳 Still has ivLoverTree after first back, backing again...")
                        delay(300L)
                        backOnce(service)
                    } else {
                        Log.d(TAG, "✅ No ivLoverTree, already in list")
                    }
                } else {
                    Log.e(TAG, "❌ Root NULL after cancel")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during reply: ${e.message}", e)
            try {
                backOnce(service)
            } catch (ex: Exception) {
                Log.e(TAG, "❌ Error during back: ${ex.message}")
            }
        }
    }

    // ✅ Extract nama dari chat yang SUDAH TERBUKA
    private suspend fun extractOpenedChatName(service: AccessibilityService): String? {
        delay(300)
        val root = WindowFilterHelper.getTargetRootNode(service, config.packageName) ?: return null

        // ✅ Primary: Coba dari title bar
        val titleNode = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/tvTitle")
            ?.firstOrNull {
                !WindowFilterHelper.isOwnOverlay(it) &&
                        it.text != null && it.text.isNotBlank()
            }

        val name = titleNode?.text?.toString()?.trim()
        if (!name.isNullOrBlank()) {
//            Log.d(TAG, "✅ Name from tvTitle: $name")
            return name
        }

        // ✅ Fallback: Coba dari view ID alternatif
        val fallbackIds = listOf(
            "com.hwsj.club:id/title",
            "com.hwsj.club:id/chat_title",
            "com.hwsj.club:id/userName",
            "com.hwsj.club:id/name",
            "com.hwsj.club:id/chatName"
        )

        for (viewId in fallbackIds) {
            val node = root.findAccessibilityNodeInfosByViewId(viewId)
                ?.firstOrNull {
                    !WindowFilterHelper.isOwnOverlay(it) &&
                            it.text != null && it.text.isNotBlank()
                }

            val fallbackName = node?.text?.toString()?.trim()
            if (!fallbackName.isNullOrBlank()) {
//                Log.d(TAG, "✅ Name from $viewId: $fallbackName")
                return fallbackName
            }
        }

        Log.w(TAG, "⚠️ Name not found in any view")
        return null
    }

    // ✅ Click dengan gesture (non-blocking)
    private fun clickWithGesture(
        service: AccessibilityService,
        node: AccessibilityNodeInfo
    ): Boolean {
        if (WindowFilterHelper.isOwnOverlay(node)) {
//            Log.w(TAG, "⚠️ Node is overlay, skip click")
            return false
        }

        val rect = Rect().apply { node.getBoundsInScreen(this) }

        if (rect.width() <= 0 || rect.height() <= 0) {
//            Log.w(TAG, "⚠️ Invalid rect: $rect")
            return false
        }

        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

//        Log.d(TAG, "👆 Clicking at: ($centerX, $centerY)")

        val path = Path().apply {
            moveTo(centerX, centerY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return try {
            service.dispatchGesture(gesture, null, null)
            true
        } catch (_: Throwable) {
//            Log.e(TAG, "❌ Click gesture failed: ${t.message}")
            false
        }
    }

    // ✅ Wait for last message (suspend, ringan)
    private suspend fun waitForLastMessage(
        service: AccessibilityService,
        timeoutMs: Long = 3000L
    ): String? {
        val startTime = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempts++

            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                ?: continue

            val messages = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/content")
                ?.filter { !WindowFilterHelper.isOwnOverlay(it) }

            if (!messages.isNullOrEmpty()) {
                val lastMessage = messages.lastOrNull() ?: continue
                val text = lastMessage.text?.toString()?.trim().orEmpty()

                if (text.isNotBlank()) {
//                    Log.d(TAG, "✅ Last message found after ${attempts} attempts")
                    return text
                }
            }

            delay(150L)
        }

//        Log.w(TAG, "⚠️ No last message found after ${timeoutMs}ms")
        return null
    }

    // ✅ Wait for city (suspend, ringan)
    private suspend fun waitForCity(
        service: AccessibilityService,
        persona: Persona,
        timeoutMs: Long = 2000L
    ): String {
        val startTime = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attempts++

            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
                ?: continue

            val cityNode = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/cityView")
                ?.firstOrNull {
                    it.text != null && it.text.isNotBlank() &&
                            !WindowFilterHelper.isOwnOverlay(it)
                }

            val cityText = cityNode?.text?.toString()?.trim()
            if (!cityText.isNullOrEmpty()) {
//                Log.d(TAG, "✅ City found after ${attempts} attempts: $cityText")
                return cityText
            }

            delay(120L)
        }

//        Log.d(TAG, "⚠️ City not found, using persona address: ${persona.address}")
        return persona.address
    }
}
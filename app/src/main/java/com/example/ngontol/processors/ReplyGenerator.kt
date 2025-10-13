package com.example.ngontol.processors

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.example.ngontol.BotPersona
import com.example.ngontol.GeminiApi
import com.example.ngontol.OpenerData
import com.example.ngontol.Persona
import com.example.ngontol.managers.ChatHistoryManager
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.utils.BotConstants
import com.example.ngontol.utils.sanitize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ReplyGenerator {
    suspend fun generate(
        service: AccessibilityService,
        message: ChatMessage,
        persona: Persona,
        userCity: String? = null
    ): String {
        // Check if should use opener
        if (OpenerSelector.shouldUseOpener(message.message)) {
            return OpenerSelector.getOpener()
        }

        val safeName = message.name.sanitize()
        val userId = safeName.ifBlank { "user_${message.name.hashCode()}" }
        val history = ChatHistoryManager.load(service, userId)

        // Build prompt
        val prompt = buildString {
            if (userCity != null) appendLine("[CITY] $userCity")
            history.forEach { appendLine(it) }
            appendLine("${message.name}: ${message.message.trim()}")
        }

        // Get selected model
        val modelStr = service.getSharedPreferences(BotConstants.PREF_NAME_BOT, Context.MODE_PRIVATE)
            .getString("selected_model", BotPersona.GENZ_CENTIL.name)
            ?: BotPersona.GENZ_CENTIL.name

        // Generate reply
        val ai = withContext(Dispatchers.IO) {
            GeminiApi.generateReply(
                service, prompt, persona,
                BotPersona.valueOf(modelStr),
                userCity = userCity
            )
        } ?: OpenerData.delayMessages.random()

        // Clean response - ✅ PASS PERSONA
        return TextCleaner.clean(ai, message.name, persona.botName, persona)

    }
}
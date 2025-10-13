package com.example.ngontol.managers

import android.content.Context
import java.io.File

object ChatHistoryManager {
    private const val HISTORY_DIR = "chat_history"
    private const val MAX_HISTORY = 20

    fun load(context: Context, userId: String): List<String> {
        val file = File(context.filesDir, "$HISTORY_DIR/$userId.json")
        return if (file.exists()) {
            try { file.readLines() } catch (_: Exception) { emptyList() }
        } else emptyList()
    }

    fun save(context: Context, userId: String, history: List<String>) {
        val dir = File(context.filesDir, HISTORY_DIR)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "$userId.json")
        val trimmed = history.takeLast(MAX_HISTORY)

        try {
            file.writeText(trimmed.joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun addMessage(
        context: Context,
        userId: String,
        userName: String,
        botName: String,
        userMessage: String,
        botReply: String
    ) {
        val history = load(context, userId).toMutableList()
        history.add("$userName: ${userMessage.trim()}")
        history.add("$botName: $botReply")
        save(context, userId, history)
    }
}
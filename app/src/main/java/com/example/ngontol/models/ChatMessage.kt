package com.example.ngontol.models

data class ChatMessage(
    val name: String,
    val message: String,
    val unreadCount: Int = 0
) {
    val cacheKey: String get() = "$name|$message"
}
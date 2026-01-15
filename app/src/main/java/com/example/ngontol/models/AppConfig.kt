package com.example.ngontol.models

data class AppConfig(
    val packageName: String,
    val listViewId: String,
    val unreadViewId: String,
    val inputViewId: String,
    val sendViewId: String,
    val diamondViewId: String,
    val conversationTabId: String,
    val allTabId: String,
    val unreadTabId: String,
    val homeNew: String,
    val hiButton: String,
    val rechargeId: String,
    val unreadTabText: String = "",
    val cancelButtonIds: List<String> = emptyList()
)

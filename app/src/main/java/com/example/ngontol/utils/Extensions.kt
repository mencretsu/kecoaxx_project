package com.example.ngontol.utils

import android.view.accessibility.AccessibilityNodeInfo

fun String.sanitize() = lowercase().replace(Regex("[^a-z0-9_]"), "_")

fun String.isAllDigits() = matches(Regex("^\\d+$"))

fun AccessibilityNodeInfo.clickSafely(): Boolean {
    var current: AccessibilityNodeInfo? = this
    while (current != null && !current.isClickable) {
        current = current.parent
    }
    return current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
}
fun AccessibilityNodeInfo.findChildText(index: Int): String? {
    return try {
        getChild(index)?.text?.toString()
    } catch (e: Exception) {
        null
    }
}
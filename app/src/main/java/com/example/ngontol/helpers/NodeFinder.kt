package com.example.ngontol.helpers

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.models.AppConfig
import java.util.ArrayDeque

object NodeFinder {
    fun findUnreadCount(row: AccessibilityNodeInfo, unreadViewId: String): Int {
        // Try direct view ID first
        row.findAccessibilityNodeInfosByViewId(unreadViewId)
            .firstOrNull()?.text?.toString()?.toIntOrNull()?.let { return it }

        // Fallback: scan children
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until row.childCount) {
            row.getChild(i)?.let { stack.add(it) }
        }

        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            val text = current.text?.toString() ?: ""

            if (current.className == "android.widget.TextView" && text.matches(Regex("\\d+"))) {
                val count = text.toInt()
                current.recycle()
                return count
            }

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.add(it) }
            }
            current.recycle()
        }

        return 0
    }

    fun waitForInput(
        service: AccessibilityService,
        config: AppConfig,
        timeoutMs: Long = 3000
    ): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)
            val input = root?.findAccessibilityNodeInfosByViewId(config.inputViewId)
                ?.firstOrNull {
                    !WindowFilterHelper.isOwnOverlay(it, service.packageName) &&
                            it.isVisibleToUser
                }

            if (input != null) return input
            Thread.sleep(80)
        }

        return null
    }

    fun findCancelButton(
        root: AccessibilityNodeInfo,
        config: AppConfig,
        servicePackage: String
    ): AccessibilityNodeInfo? {
        return config.cancelButtonIds.firstNotNullOfOrNull { buttonId ->
            root.findAccessibilityNodeInfosByViewId(buttonId)
                ?.firstOrNull {
                    !WindowFilterHelper.isOwnOverlay(it, servicePackage) &&
                            it.isVisibleToUser
                }
        }
    }
}
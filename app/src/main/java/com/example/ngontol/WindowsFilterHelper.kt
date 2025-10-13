package com.example.ngontol

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log

object WindowFilterHelper {

    // ✅ Hardcode nama package biar gak tergantung service.packageName
    private const val OWN_PACKAGE = "com.example.ngontol"

    /**
     * Filter windows untuk mengabaikan overlay dari package sendiri
     */
    fun getTargetWindows(
        service: AccessibilityService,
        targetPackage: String? = null
    ): List<AccessibilityWindowInfo> {
        val allWindows = service.windows ?: return emptyList()

        return allWindows.filter { window ->
            val root = window.root

            // Ignore jika null
            if (root == null) return@filter false

            // Ignore jika dari package sendiri (overlay)
            if (root.packageName == OWN_PACKAGE) {
                return@filter false
            }

            // Ignore jika ada content description "OVERLAY_IGNORE"
            if (root.contentDescription == "OVERLAY_IGNORE") {
                return@filter false
            }

            // Jika ada target package spesifik, filter hanya itu
            if (targetPackage != null && root.packageName != targetPackage) {
                return@filter false
            }

            true
        }
    }

    /**
     * Ambil root node dari target app (bukan overlay)
     */
    fun getTargetRootNode(
        service: AccessibilityService,
        targetPackage: String? = null
    ): AccessibilityNodeInfo? {
        val targetWindows = getTargetWindows(service, targetPackage)

        // Prioritas: APPLICATION window type
        val appWindow = targetWindows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }

        return appWindow?.root ?: targetWindows.firstOrNull()?.root
    }

    /**
     * Check apakah node adalah dari overlay sendiri
     */
    fun isOwnOverlay(node: AccessibilityNodeInfo?, ownPackageName: String): Boolean {
        if (node == null) return false

        return node.packageName == OWN_PACKAGE ||
                node.contentDescription == "OVERLAY_IGNORE"
    }

    fun scanNodeSafely(
        node: AccessibilityNodeInfo?,
        ownPackageName: String,
        onNodeFound: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null) return

        // Skip jika dari overlay sendiri
        if (isOwnOverlay(node, ownPackageName)) {
            return
        }

        try {
            // Process node
            onNodeFound(node)

            // Scan children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    scanNodeSafely(child, ownPackageName, onNodeFound)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e("WindowFilterHelper", "Error scanning node: ${e.message}")
        }
    }
}

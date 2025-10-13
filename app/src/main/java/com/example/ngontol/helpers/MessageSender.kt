package com.example.ngontol.helpers

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.models.AppConfig
import kotlinx.coroutines.delay

object MessageSender {
    suspend fun send(
        service: AccessibilityService,
        input: AccessibilityNodeInfo,
        text: String,
        config: AppConfig
    ) {
        val delayTime = if (text.length <= 30) {
            (301..590).random().toLong()
        } else {
            (590..890).random().toLong()
        }

        // Set text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        delay(delayTime)

        // Click send button
        WindowFilterHelper.getTargetRootNode(service, config.packageName)
            ?.findAccessibilityNodeInfosByViewId(config.sendViewId)
            ?.firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

}

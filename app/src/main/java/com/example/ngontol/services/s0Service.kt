package com.example.ngontol.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.example.ngontol.WindowFilterHelper
import com.example.ngontol.config.AppConfigs
import com.example.ngontol.models.ChatMessage
import com.example.ngontol.utils.clickSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object S0Service : BaseAppService(AppConfigs.S0) {

    // Resource IDs untuk login flow
    private const val ID_CONFIRM_UNDERSTAND = "com.zr.siya:id/tv_confirm" // "Saya Mengerti" & "Setuju"
    private const val ID_PRIVACY_CHECKBOX = "com.zr.siya:id/iv_privacy" // Checkbox privacy policy
    private const val ID_LOGIN_GOOGLE = "com.zr.siya:id/tv_login_google" // (Not used anymore)
    private const val ID_LOGIN_FACEBOOK = "com.zr.siya:id/tv_login_facebook" // ✅ NEW: Facebook login
    private const val ID_GOOGLE_ACCOUNT = "com.google.android.gms:id/account_display_name" // (Not used anymore)

    // ✅ Track semua states untuk mencegah double click
    private var privacyCheckboxClicked = false
    private var facebookLoginClicked = false // ✅ Changed from googleLoginClicked
    private var understandClicked = false

    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "S0Service started - Auto Login Loop (Facebook)")

        // Reset semua states
        resetStates()

        // Start login loop in background
        scope.launch {
            runLoginLoop(service, scope, isRunning)
        }
    }

    private fun resetStates() {
        privacyCheckboxClicked = false
        facebookLoginClicked = false // ✅ Changed
        understandClicked = false
    }

    private suspend fun runLoginLoop(
        service: AccessibilityService,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        while (scope.isActive && isRunning()) {
            try {
                // ✅ Get target app root
                val root = WindowFilterHelper.getTargetRootNode(service, config.packageName)

                if (root == null) {
                    Log.v(TAG, "⏳ Waiting for target app...")
                    delay(2000L)
                    continue
                }

                // Step 1: Click "Saya Mengerti" button (hanya sekali)
                val btnUnderstand = root.findAccessibilityNodeInfosByViewId(ID_CONFIRM_UNDERSTAND)
                    ?.firstOrNull { it.text?.toString() == "Saya Mengerti" }

                if (btnUnderstand != null && !understandClicked) {
                    Log.d(TAG, "🔘 Clicking 'Saya Mengerti'...")
                    if (btnUnderstand.clickSafely()) {
                        understandClicked = true
                        delay(2000L)
                        continue
                    }
                }

                // Step 2: Check & Click Privacy Checkbox (hanya sekali)
                // ⚠️ SKIPPED: Checkbox gak kepake, "Setuju" langsung muncul setelah klik Facebook

                val privacyCheckbox = root.findAccessibilityNodeInfosByViewId(ID_PRIVACY_CHECKBOX)
                    ?.firstOrNull()

                if (privacyCheckbox != null && !privacyCheckboxClicked) {
                    Log.d(TAG, "☑️ Clicking privacy checkbox...")
                    if (privacyCheckbox.clickSafely()) {
                        privacyCheckboxClicked = true
                        delay(2230L)
                        continue
                    }
                }

                // ✅ NEW: Step 3: Click "Login dengan Facebook" button (hanya sekali)
                val btnLoginFacebook = root.findAccessibilityNodeInfosByViewId(ID_LOGIN_FACEBOOK)
                    ?.firstOrNull()

                if (btnLoginFacebook != null && !facebookLoginClicked) {
                    Log.d(TAG, "🔘 Clicking 'Login dengan Facebook'...")
                    if (btnLoginFacebook.clickSafely()) {
                        facebookLoginClicked = true
                        Log.d(TAG, "⏳ Waiting for Facebook login...")
                        delay(3000L)
                        continue
                    }
                }

                // Step 4: Click "Setuju" button (Terms & Conditions)
                val btnAgree = root.findAccessibilityNodeInfosByViewId(ID_CONFIRM_UNDERSTAND)
                    ?.firstOrNull { it.text?.toString() == "Setuju" }

                if (btnAgree != null) {
                    Log.d(TAG, "🔘 Clicking 'Setuju'...")
                    if (btnAgree.clickSafely()) {
                        delay(4000L)
                        continue
                    }
                }

                // ✅ Reset states jika kembali ke halaman awal
                if (btnUnderstand != null && understandClicked) {
                    understandClicked = false
                    privacyCheckboxClicked = false
                    facebookLoginClicked = false
                    Log.d(TAG, "🔄 Reset states - back to initial screen")
                }

                // Step 5: Check if login completed (no more login buttons)
                val hasLoginButton = root.findAccessibilityNodeInfosByViewId(ID_LOGIN_FACEBOOK)
                    ?.isNotEmpty() == true

                if (!hasLoginButton) {
                    Log.d(TAG, "✅ Login flow completed, monitoring...")
                    resetStates()
                    delay(3000L)
                } else {
                    delay(2000L)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in login loop: ${e.message}", e)
                delay(3000L)
            }
        }

        Log.d(TAG, "S0Service login loop stopped")
    }

    /* ========================================
     * ✅ OLD METHOD: Google Login (COMMENTED)
     * ========================================
     *
     * Flow sebelumnya:
     * 1. Click "Saya Mengerti"
     * 2. Check privacy checkbox
     * 3. Click "Login dengan Google" button
     * 4. Wait for Google popup (com.google.android.gms package)
     * 5. Select Google account from picker
     * 6. Click "Setuju" for Terms & Conditions
     *
     * Code:
     *
     * // PRIORITY 1: Handle Google Account Picker (Google package)
     * if (currentPackage == "com.google.android.gms" || currentPackage?.contains("google") == true) {
     *     val googleAccount = anyRoot.findAccessibilityNodeInfosByViewId(ID_GOOGLE_ACCOUNT)
     *         ?.firstOrNull()
     *
     *     if (googleAccount != null) {
     *         val accountName = googleAccount.text?.toString() ?: "Unknown"
     *         Log.d(TAG, "🔘 [GOOGLE POPUP] Selecting account: $accountName")
     *
     *         val accountRow = googleAccount.parent
     *         if (accountRow != null && accountRow.clickSafely()) {
     *             Log.d(TAG, "✅ Google account selected, waiting for login completion...")
     *             googleLoginClicked = false
     *             delay(6000L)
     *             continue
     *         }
     *     }
     *
     *     delay(3000L)
     *     continue
     * }
     *
     * // Step 3: Click "Login dengan Google" button
     * val btnLoginGoogle = root.findAccessibilityNodeInfosByViewId(ID_LOGIN_GOOGLE)
     *     ?.firstOrNull()
     *
     * if (btnLoginGoogle != null && !googleLoginClicked) {
     *     Log.d(TAG, "🔘 Clicking 'Login dengan Google'...")
     *     if (btnLoginGoogle.clickSafely()) {
     *         googleLoginClicked = true
     *         Log.d(TAG, "⏳ Waiting for Google popup...")
     *         delay(3000L)
     *         continue
     *     }
     * }
     *
     * Kenapa diganti ke Facebook:
     * - Google login butuh handle popup terpisah (different package)
     * - Facebook login biasanya in-app atau via WebView
     * - Lebih simple flow, tidak perlu monitor package switch
     *
     * ======================================== */

    // Override: S0 doesn't handle chat (login service only)
    override suspend fun handleChat(
        service: AccessibilityService,
        message: ChatMessage,
        scope: CoroutineScope,
        shouldSkip: Boolean
    ) {
        Log.d(TAG, "⚠️ handleChat called on S0Service (login service) - ignoring")
    }

    // Override: Skip app launch logic (we handle it in login loop)
    override fun shouldSkipLaunch(): Boolean {
        return true // Login service doesn't need auto-launch
    }

    // Override: No navigation needed for login service
    override suspend fun navigateToConversation(
        service: AccessibilityService,
        option: Int,
        autoHi: Int,
        retryCount: Int,
        maxRetries: Int
    ) {
        Log.d(TAG, "⏭️ Skip navigation - login service")
    }

    // Override: No diamond logging for login service
    override fun logDiamond(service: AccessibilityService) {
        // Skip diamond logging
    }
}
package com.example.ngontol

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.delay

object GeminiApi {

    private const val TAG = "GeminiApi"

    data class TextPart(
        @Json(name = "text") val text: String
    )

    data class Content(
        @Json(name = "role") val role: String = "user",
        @Json(name = "parts") val parts: List<TextPart>
    )

    data class RequestPayload(
        @Json(name = "contents") val contents: List<Content>
    )

    data class ResponsePart(
        @Json(name = "text") val text: String
    )

    data class ResponseContent(
        @Json(name = "parts") val parts: List<ResponsePart>?
    )

    data class Candidate(
        @Json(name = "content") val content: ResponseContent?
    )

    data class GeminiResponse(
        @Json(name = "candidates") val candidates: List<Candidate>?
    )

    private val client = OkHttpClient()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private val requestAdapter = moshi.adapter(RequestPayload::class.java)
    private val responseAdapter = moshi.adapter(GeminiResponse::class.java)

    @SuppressLint("UseKtx")
    suspend fun generateReply(
        context: Context,
        inputText: String,
        persona: Persona,
        model: BotPersona = BotPersona.GENZ_CENTIL,
        userCity: String? = null
    ): String? {
        val prefs = context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val cooldownPrefs = context.getSharedPreferences("key_cooldown", Context.MODE_PRIVATE)

        val cooldownMS = 8000L
        val currentTime = System.currentTimeMillis()

        // Ambil API keys
        var apiKeys = prefs.getStringSet("apiKeyx", emptySet())?.toList()

        if (apiKeys.isNullOrEmpty()) {
            Log.d(TAG, "🔥 Keys kosong, load dari assets...")
            FirebaseManager.ensureDeviceInitialized(context)
            FirebaseManager.fetchMaxKeys(context)
            apiKeys = prefs.getStringSet("apiKeyx", emptySet())?.toList()
        }

        if (apiKeys.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ Tidak ada API keys tersedia")
            return null
        }

        // Filter keys yang sudah melewati cooldown
        val availableKeys = apiKeys.filter { key ->
            val lastUsed = cooldownPrefs.getLong(key, 0L)
            (currentTime - lastUsed) >= cooldownMS
        }

        if (availableKeys.isEmpty()) {
            Log.w(TAG, "⏱️ Semua keys dalam cooldown (${apiKeys.size} total keys)")
            return null
        }

        // Shuffle untuk distribusi merata
        val keysToUse = availableKeys.shuffled()

        Log.d(TAG, "🎯 Trying ${keysToUse.size}/${apiKeys.size} available keys")

        // Model priority
        val modelPriority = listOf("gemini-2.0-flash")

        var successCount = 0
        var totalFails = 0

        // Loop semua available keys
        for ((index, apiKey) in keysToUse.withIndex()) {
            // Delay random 1000-2000ms sebelum request (kecuali key pertama)
            if (index > 0) {
                delay((1000L..2000L).random())
            }

            var reply: String? = null
            var keySuccess = false

            // Loop semua model sampai ada yang berhasil
            for (modelName in modelPriority) {
                if (keySuccess) break

                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                    val systemPrompt = generateSystemPrompt(persona, model, userCity)

                    val payload = RequestPayload(
                        contents = listOf(
                            Content(parts = listOf(TextPart(systemPrompt))),
                            Content(parts = listOf(TextPart(inputText)))
                        )
                    )

                    val json = requestAdapter.toJson(payload)
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(body).build()

                    client.newCall(request).execute().use { response ->
                        // Update timestamp SETELAH request
                        cooldownPrefs.edit().putLong(apiKey, System.currentTimeMillis()).apply()

                        when (response.code) {
                            200 -> {
                                val bodyStr = response.body?.string().orEmpty()
                                val text = responseAdapter.fromJson(bodyStr)
                                    ?.candidates?.firstOrNull()
                                    ?.content?.parts?.firstOrNull()?.text?.trim()

                                if (text != null) {
                                    Log.d(TAG, "✅ Success: $modelName (key ${index + 1}/${keysToUse.size})")
                                    reply = text
                                    keySuccess = true
                                    successCount++
                                }
                            }
                            429 -> {
                                Log.w(TAG, "⚠️ 429 Rate Limited: $modelName (key ${index + 1})")
                                totalFails++
                            }
                            403 -> {
                                Log.w(TAG, "⚠️ 403 Forbidden: $modelName (key ${index + 1})")
                                totalFails++
                            }
                            400 -> {
                                val bodyStr = response.body?.string().orEmpty()
                                Log.w(TAG, "🚫 400 Bad Request: $modelName (key ${index + 1}) - ${bodyStr.take(100)}")
                                totalFails++
                            }
                            else -> {
                                Log.w(TAG, "❌ Error ${response.code}: $modelName (key ${index + 1})")
                                totalFails++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception $modelName (key ${index + 1}): ${e.message}")
                    totalFails++
                    cooldownPrefs.edit().putLong(apiKey, System.currentTimeMillis()).apply()
                }

                if (!keySuccess) {
                    delay(300)
                }
            }

            if (!reply.isNullOrEmpty()) {
                FirebaseManager.incrementSent(context)
                return reply
            }
        }

        Log.w(TAG, "⚠️ All keys failed or in cooldown (Success: $successCount, Fails: $totalFails)")
        return null
    }
}
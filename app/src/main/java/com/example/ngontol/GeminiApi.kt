package com.example.ngontol

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.ngontol.firebase.FirebaseManager
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
        randomize: Boolean = true, // Ganti default jadi true
        userCity: String? = null
    ): String? {
        val prefs = context.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
        val cooldownPrefs = context.getSharedPreferences("key_cooldown", Context.MODE_PRIVATE)

        val cooldownMS = 8000L // Naikin jadi 8 detik biar lebih aman
        val currentTime = System.currentTimeMillis()

        // Ambil API keys
        var apiKeys = prefs.getStringSet("apiKey2", emptySet())?.toList()

        if (apiKeys.isNullOrEmpty()) {
            Log.d(TAG, "📥 Keys kosong, fetch dari Firebase...")
            FirebaseManager.ensureDeviceInitialized(context)
            FirebaseManager.fetchMaxKeys(context)
            apiKeys = prefs.getStringSet("apiKey2", emptySet())?.toList()
        }

        if (apiKeys.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ Tidak ada API keys tersedia")
            return null
        }

        // Filter keys yang sudah melewati cooldown
        val availableKeys = apiKeys.filter { key ->
            val lastUsed = cooldownPrefs.getLong(key, 0L)
            val isAvailable = (currentTime - lastUsed) >= cooldownMS

            if (!isAvailable) {
                val remaining = ((cooldownMS - (currentTime - lastUsed)) / 1000.0)
                Log.d(TAG, "⏳ Key ${key.takeLast(8)} cooldown: ${remaining}s")
            }

            isAvailable
        }

        if (availableKeys.isEmpty()) {
            Log.w(TAG, "⏱️ Semua keys dalam cooldown")
            return null
        }

        // RANDOM shuffled keys
        val keysToUse = availableKeys.shuffled()
//        Log.d(TAG, "🔑 Available: ${keysToUse.size}/${apiKeys.size} keys (random order)")

        // Loop semua available keys
        for ((index, apiKey) in keysToUse.withIndex()) {
            // Delay random 300-800ms sebelum request (kecuali key pertama)
            if (index > 0) {
                val randomDelay = (2000L..3000L).random()
//                Log.d(TAG, "⏳ Delay ${randomDelay}ms sebelum key ${index + 1}")
                delay(randomDelay)
            }

            val reply = try {
//                Log.d(TAG, "🚀 Trying key ${index + 1}/${keysToUse.size}")

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
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
//                                Log.d(TAG, "✅ Sukses dengan key ${index + 1}")
//                                Log.d(TAG, "✅ [${index + 1}] Sukses respons: $text")

                            }
                            text
                        }
                        429 -> {
//                            Log.w(TAG, "⏱️ Rate limit key ${index + 1}")
                            null
                        }
                        else -> {
                            Log.w(TAG, "❌ Error ${response.code} key ${index + 1}")
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Exception key ${index + 1}: ${e.message}")
                // Update timestamp meskipun exception
                cooldownPrefs.edit().putLong(apiKey, System.currentTimeMillis()).apply()
                null
            }

            if (!reply.isNullOrEmpty()) {
                FirebaseManager.incrementSent(context)
                return reply
            }
        }

        Log.w(TAG, "⚠️ Semua keys gagal atau cooldown")
        return null
    }
}
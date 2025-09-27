package com.example.ngontol

import android.content.Context
import android.util.Log
import com.example.ngontol.firebase.FirebaseManager
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

    suspend fun generateReply(
        context: Context,
        inputText: String,
        persona: Persona,
        model: BotPersona = BotPersona.GENZ_CENTIL,
        retryCount: Int = 3
    ): String? {
        var remainingRetries = retryCount

        while (remainingRetries > 0) {
            val apiKey = FirebaseManager.getAvailableApiKey()
            if (apiKey == null) {
                Log.e(TAG, "❌ Semua API key habis/limit!")
                delay(1000L)
                remainingRetries--
                continue
            }

            val reply = withContext(Dispatchers.IO) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
                val systemPrompt = generateSystemPrompt(persona, model)
                val payload = RequestPayload(
                    contents = listOf(
                        Content(parts = listOf(TextPart(systemPrompt))),
                        Content(parts = listOf(TextPart(inputText)))
                    )
                )
                val json = requestAdapter.toJson(payload)
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                try {
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        val bodyStr = response.body?.string().orEmpty()

                        when (code) {
                            200 -> {
                                val result = responseAdapter.fromJson(bodyStr)
                                result?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                            }
                            400, 401, 403 -> {
                                FirebaseManager.disableApiKey(apiKey)
                                Log.w(TAG, "🚫 Key $apiKey disabled karena HTTP $code, ganti key...")
                                null
                            }
                            429 -> {
                                FirebaseManager.cooldownApiKey(apiKey, 60)
                                Log.w(TAG, "⏳ Key $apiKey cooldown 60s karena 429, ganti key...")
                                null
                            }
                            500, 503, 504 -> {
                                Log.w(TAG, "⚠️ Server error $code, retrying...")
                                null
                            }
                            else -> {
                                Log.e(TAG, "❌ Unexpected HTTP $code: $bodyStr")
                                null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Exception: ${e.message}", e)
                    null
                }
            }

            if (!reply.isNullOrEmpty()) {
                FirebaseManager.incrementSent(context)
                Log.d(TAG, "✅ Gemini reply: $reply")
                return reply
            }

            remainingRetries--
            if (remainingRetries > 0) delay(1000L)
        }

        Log.e(TAG, "❌ Maksimal retry habis")
        return null
    }

}
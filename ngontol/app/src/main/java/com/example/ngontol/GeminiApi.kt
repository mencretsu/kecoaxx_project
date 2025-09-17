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

    fun generateReply(
        context: Context,
        inputText: String,          // <-- ganti ke String, bukan Persona
        persona: Persona,           // <-- Persona lo yang custom class
        model: BotPersona = BotPersona.GENZ_CENTIL
    ): String?    {
        val triedKeys = mutableSetOf<String>()
        val maxRetries = Constants.listapikey.size

        while (triedKeys.size < maxRetries) {
            val apiKey = (Constants.listapikey - triedKeys).random()
            triedKeys.add(apiKey)

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

            val systemPrompt: String = generateSystemPrompt(persona, model)

            val payload = RequestPayload(
                contents = listOf(
                    Content(parts = listOf(TextPart(systemPrompt))),
                    Content(parts = listOf(TextPart(inputText.toString())))
                )
            )

            val json = requestAdapter.toJson(payload)
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            try {
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorMsg = response.body?.string().orEmpty()
                    Log.e(TAG, "HTTP ${response.code} – $errorMsg")

                    var isLimit = false
                    var isInvalidKey = false

                    // Deteksi 429: limit/habis
                    if (response.code == 429) {
                        try {
                            val jsonObj = JSONObject(errorMsg)
                            val message = jsonObj.optJSONObject("error")?.optString("message") ?: ""
                            if (
                                message.contains("Requests per day", ignoreCase = true) ||
                                message.contains("Requests per minute", ignoreCase = true) ||
                                message.contains("quota", ignoreCase = true) ||
                                message.contains("limit", ignoreCase = true)
                            ) {
                                isLimit = true
                                Log.e(TAG, "❌ Limit detected: $message (ganti key)")
                            }
                        } catch (ex: Exception) {
                            if (
                                errorMsg.contains("Requests per day", ignoreCase = true) ||
                                errorMsg.contains("Requests per minute", ignoreCase = true) ||
                                errorMsg.contains("quota", ignoreCase = true) ||
                                errorMsg.contains("limit", ignoreCase = true)
                            ) {
                                isLimit = true
                                Log.e(TAG, "❌ Limit detected (fallback): $errorMsg (ganti key)")
                            }
                        }
                    }

                    // Deteksi 400: API key tidak valid
                    if (response.code == 400) {
                        try {
                            val jsonObj = JSONObject(errorMsg)
                            val reason = jsonObj.optJSONObject("error")
                                ?.optJSONArray("details")
                                ?.optJSONObject(0)
                                ?.optString("reason")
                                ?: ""
                            if (reason == "API_KEY_INVALID") {
                                isInvalidKey = true
                                Log.e(TAG, "❌ API key invalid (ganti key)")
                            }
                        } catch (ex: Exception) {
                            if (errorMsg.contains("API key not valid", ignoreCase = true)) {
                                isInvalidKey = true
                                Log.e(TAG, "❌ API key invalid (fallback) (ganti key)")
                            }
                        }
                    }

                    if (isLimit || isInvalidKey) continue // coba key berikutnya
                    return null // error lain
                }

                val jsonString = response.body?.string()
                val result = responseAdapter.fromJson(jsonString ?: "")
                val reply = result?.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstOrNull()
                    ?.text
                    ?.trim()

                if (!reply.isNullOrEmpty()) {
//                    totalSent++ // counter lokal
                    FirebaseManager.incrementSent(context) // ✅ harus Context

                    Log.d(TAG, "✅ Gemini reply: $reply")
                    return reply
                } else {
                    Log.e(TAG, "❌ Reply kosong atau null! result = $result")
                }


            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}", e)
                return null
            }
        }
        Log.e(TAG, "❌ Semua API key limit/habis!")
        return null
    }
}

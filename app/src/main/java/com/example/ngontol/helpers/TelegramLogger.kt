package com.example.ngontol.helpers

import android.util.Log
import com.example.ngontol.utils.BotConstants
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TelegramLogger {
    private val client = OkHttpClient()

    fun sendDiamondUpdate(
        botName: String,
        appName: String,
        diamondText: String?
    ) {
        val todayString = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("id", "ID")).format(Date())

        val logLine = if (diamondText.isNullOrBlank()) {
            "⚠️ Bot turu\n\n👤 : $botName\n💎 : Bot macet bos.. turu cik\n\n$todayString"
        } else {
            val diamondCount = diamondText.filter { it.isDigit() }.toIntOrNull() ?: 0
            val idrAmount = diamondCount * 2.8
            val idrFormatted = String.format(Locale.US, "%,.0f", idrAmount)
            "🚀 Diamond update\n\n👤 : $botName\n🌐 : $appName\n💎 : $diamondText\n💰 : Rp $idrFormatted\n\n$todayString"
        }

        send(logLine)
    }

    private fun send(text: String) {
        val url = "https://api.telegram.org/bot${BotConstants.Telegram.BOT_TOKEN}/sendMessage"

        val requestBody = FormBody.Builder()
            .add("chat_id", BotConstants.Telegram.CHAT_ID)
            .add("text", text)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("TelegramLogger", "Failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
            }
        })
    }
}
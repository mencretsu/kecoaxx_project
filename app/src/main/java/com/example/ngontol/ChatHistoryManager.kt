package com.example.ngontol//import android.content.Context
//import android.util.Log
//import com.squareup.moshi.Moshi
//import com.squareup.moshi.Types
//import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
//import java.io.File
//
//data class ChatMessage(
//    val role: String, // "user" atau "model"
//    val text: String,
//    val senderName: String? = null,
//    val timestamp: Long = System.currentTimeMillis()
//)
//
//object ChatHistoryManager {
//
//    private lateinit var appContext: Context
//
//    fun init(context: Context) {
//        appContext = context.applicationContext
//        val dir = File(appContext.filesDir, "chat_history")
//        if (dir.mkdirs()) {
//            Log.d("ChatHistory", "✅ chat_history dir created at ${dir.absolutePath}")
//        } else {
//            Log.d("ChatHistory", "📂 chat_history dir already exists or failed at ${dir.absolutePath}")
//        }
//    }
//
//
//    fun load(userId: String): List<ChatMessage> {
//        val file = File(appContext.filesDir, "chat_history/$userId.json")
//        Log.d("ChatHistory", "📂 Load from: ${file.absolutePath}")
//        if (!file.exists()) {
//            Log.d("ChatHistory", "⚠️ File not found")
//            return emptyList()
//        }
//
//        val json = file.readText()
//        Log.d("ChatHistory", "📄 JSON Loaded: $json")
//
//        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
//        val type = Types.newParameterizedType(List::class.java, ChatMessage::class.java)
//        return moshi.adapter<List<ChatMessage>>(type).fromJson(json) ?: emptyList()
//    }
//
//
//    fun save(userId: String, messages: List<ChatMessage>) {
//        val file = File(appContext.filesDir, "chat_history/$userId.json")
//        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
//        val type = Types.newParameterizedType(List::class.java, ChatMessage::class.java)
//        val json = moshi.adapter<List<ChatMessage>>(type).toJson(messages)
//
//        Log.d("ChatHistory", "💾 Saving ${messages.size} messages to ${file.absolutePath}")
//        file.writeText(json)
//        Log.d("ChatHistory", "✅ File written.")
//    }
//
//    fun append(userId: String, userInput: String, senderName: String, modelReply: String) {
//        Log.d("ChatHistory", "📥 Appending chat for $userId")
//        val history = load(userId).toMutableList()
//        history.add(ChatMessage("user", userInput, senderName))
//        history.add(ChatMessage("model", modelReply))
//        save(userId, history)
//    }
//
//}

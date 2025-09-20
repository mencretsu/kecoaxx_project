//package com.example.ngontol
//
//import android.accessibilityservice.AccessibilityService
//import android.accessibilityservice.GestureDescription
//import android.content.ClipData
//import android.content.ClipboardManager
//import android.content.Context
//import android.graphics.Rect
//import android.os.Build
//import android.os.Bundle
//import android.util.Log
//import android.view.accessibility.AccessibilityNodeInfo
//import android.view.accessibility.AccessibilityEvent
//import androidx.annotation.RequiresApi
//import kotlinx.coroutines.*
//import java.io.File
//import java.text.SimpleDateFormat
//import java.time.LocalTime
//import java.util.*
//
//object S1Service {
//
//    private const val TAG = "S1_SERVICE"
//
//    // View-IDs khusus Voicemaker
//    private const val SUGO_ID_LIST = "com.voicemaker.android:id/ll_chat_item"
//    private const val SUGO_ID_UNREAD = "com.voicemaker.android:id/id_unread_tcv"
//    private const val SUGO_ID_INPUT = "com.voicemaker.android:id/id_input_edit_text"
//    private const val SUGO_ID_SEND  = "com.voicemaker.android:id/id_send_btn"
//    private const val SUGO_ID_DIAMOND = "com.voicemaker.android:id/contentView"
//
//    private const val CACHE_CLEAR_INTERVAL = 15 * 1000L
//    private const val MAX_CACHE = 20
//    private const val PREF_KEY  = "processed_keys"
//
//    private var lastRun = 0L
//    private var lastCacheClear = System.currentTimeMillis()
//    private var interval = 2500L
//    private val processed = LinkedHashSet<String>()
//
//    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
//        // mulai diamond logger
//        startDiamondLogger(service, scope, isRunning)
//    }
//
//    // ----------------- Accessibility Event -----------------
//    @RequiresApi(Build.VERSION_CODES.O)
//    fun onAccessibilityEvent(
//        service: AccessibilityService,
//        event: AccessibilityEvent?,
//        scope: CoroutineScope,
//        isRunning: () -> Boolean
//    ) {
//        if (!isRunning()) return
//
//        val now = System.currentTimeMillis()
//        if (now - lastRun < interval) return
//        lastRun = now
//
//        val root = service.rootInActiveWindow ?: return
//        val rows = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/bodyView")
//
//        if (rows.isEmpty()) return
//
//        var unreadOnScreen = 0
//        val unreadList = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
//
//        for (row in rows) {
//            try {
//                val name = row.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/title")
//                    ?.firstOrNull()?.text?.toString().orEmpty()
//                if (name.isBlank()) continue
//
//                val key = name // sekarang cache pakai nama aja
//                if (key in processed) continue
//
//                // Clear cache tiap interval tertentu
//                if (now - lastCacheClear >= CACHE_CLEAR_INTERVAL) {
//                    processed.clear()
//                    service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE)
//                        .edit().clear().apply()
//                    lastCacheClear = now
//                }
//
//                val unread = findUnreadCount(row)
//                if (unread > 0) {
//                    unreadList.add(row to name)
//                } else {
//                    processed.add(key)
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ Row error: ${e.message}")
//            }
//        }
//
//        if (unreadList.isNotEmpty()) {
//            scope.launch {
//                for ((row, name) in unreadList) {
//                    try {
//                        handleUnread(service, row, name,  scope)
//                    } catch (e: Exception) {
//                        Log.e(TAG, "❌ Error reply: ${e.message}")
//                    } finally {
//                        row.recycle()
//                    }
//                }
//                service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE)
//                    .edit().putStringSet(PREF_KEY, processed).apply()
//                interval = if (unreadOnScreen > 3) 4500L else 2500L
//            }
//        } else {
//            service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE)
//                .edit().putStringSet(PREF_KEY, processed).apply()
//            interval = if (unreadOnScreen > 3) 4500L else 2500L
//        }
//    }
//
//    private fun findUnreadCount(row: AccessibilityNodeInfo): Int {
//        row.findAccessibilityNodeInfosByViewId(SUGO_ID_UNREAD)
//            .firstOrNull()?.text?.toString()?.toIntOrNull()?.let { return it }
//
//        val stack = ArrayDeque<AccessibilityNodeInfo>()
//        for (i in 0 until row.childCount) row.getChild(i)?.let { stack.add(it) }
//
//        while (stack.isNotEmpty()) {
//            val cur = stack.removeFirst()
//            val txt = cur.text?.toString() ?: ""
//            if (cur.className == "android.widget.TextView" && txt.matches(Regex("\\d+"))) {
//                val count = txt.toInt()
//                cur.recycle()
//                return count
//            }
//            for (i in 0 until cur.childCount) cur.getChild(i)?.let { stack.add(it) }
//            cur.recycle()
//        }
//        return 0
//    }
//
//    // ----------------- Handle Unread & Reply -----------------
//    private fun clickFirstClickable(node: AccessibilityNodeInfo): Boolean {
//        var cur: AccessibilityNodeInfo? = node
//        while (cur != null && !cur.isClickable) cur = cur.parent
//        return cur?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
//    }
//
//    private fun waitInputBox(service: AccessibilityService, timeoutMs: Long = 3000): AccessibilityNodeInfo? {
//        val start = System.currentTimeMillis()
//        while (System.currentTimeMillis() - start < timeoutMs) {
//            val input = service.rootInActiveWindow
//                ?.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/editContent") // langsung ke EditText
//                ?.firstOrNull()
//            if (input != null) {
//                Log.d(S2Service.TAG, "✅ Input box ditemukan")
//                return input
//            }
//            Thread.sleep(80)
//        }
//        Log.e(S2Service.TAG, "❌ Input box not found")
//        return null
//    }
//
//    private suspend fun sendReply(service: AccessibilityService, input: AccessibilityNodeInfo, text: String) {
//        // delay natural berdasarkan panjang teks (sama prinsip di kode awal)
//        val delayTime = if (text.length <= 30) {
//            (90..290).random().toLong()
//        } else {
//            (670..2490).random().toLong()
//        }
//
//        val rect = Rect()
//        input.getBoundsInScreen(rect)
//        val path = android.graphics.Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
//        val gesture = GestureDescription.Builder()
//            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
//            .build()
//        service.dispatchGesture(gesture, null, null)
//        Thread.sleep(200)
//
//        // 2. Coba ketik pakai BotKeyboard
//        BotKeyboard.instance?.typeText(text)
//        Log.d(S2Service.TAG, "⌨️ BotKeyboard commit: $text")
//
//        delay(delayTime)
//
//        // cobalah dua id tombol send: SUGO_ID_SEND dulu, fallback ke id_chat_send_btn
//        val sendBtn = service.rootInActiveWindow
//            ?.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/ivSend")
//            ?.firstOrNull()
//
//        if (sendBtn != null) {
//            val rect = Rect()
//            sendBtn.getBoundsInScreen(rect)
//            val cx = rect.centerX().toFloat()
//            val cy = rect.centerY().toFloat()
//
//            val path = android.graphics.Path().apply { moveTo(cx, cy) }
//            val gesture = GestureDescription.Builder()
//                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
//                .build()
//            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
//                override fun onCompleted(gestureDescription: GestureDescription?) {
//                    Log.d(S2Service.TAG, "📨 Klik tombol kirim sukses")
//                }
//            }
//        }
//    @RequiresApi(Build.VERSION_CODES.O)
//    private fun handleUnread(
//        service: AccessibilityService,
//        row: AccessibilityNodeInfo,
//        name: String,
//        scope: CoroutineScope
//    ) {
//        // klik item
//        if (!clickFirstClickable(row)) {
//            return
//        }
//        Thread.sleep(500) // beri jeda animasi
//
//        // tunggu input box
//        val input = waitInputBox(service) ?: run {
//            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
//            return
//        }
//        // cari pesan terakhir di dalam chat
//        val root = service.rootInActiveWindow ?: return
//        val messages = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/message_text")
//        val rawMsg = messages?.lastOrNull()?.text?.toString().orEmpty()
//
//        if (rawMsg.isBlank()) {
//            Log.w(TAG, "⚠️ Pesan kosong dari $name")
//            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
//            return
//        }
//        // ambil persona (jika ga ada, balik)
//        val persona = PersonaManager.getPersona(service) ?: run {
//            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
//            return
//        }
//
//// pilih opener sesuai jam
//        val hour = LocalTime.now().hour
//        val opener = when (hour) {
//            in 5..9 -> OpenerData.morningOpeners.random()
//            in 10..15 -> OpenerData.noonOpeners.random()
//            in 16..19 -> OpenerData.genZOpeners.random()
//            in 20..23 -> OpenerData.nightOpeners.random()
//            else -> OpenerData.lateNightOpeners.random()
//        }
//
//        // jalankan logic reply di coroutine supaya bisa call network (GeminiApi dsb)
//        scope.launch {
//            try {
//                val safeName = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
//                val userId = safeName
//
//                // 1. load history
//                val history = loadHistory(service, userId).toMutableList()
//
//                // 2. tentukan replyText
//                val replyText = when {
//                    OpenerData.cocokPatterns.any { rawMsg.contains(it) } -> {
//                        delay(230)
//                        opener
//                    }
//                    OpenerData.partnerPatterns.any { rawMsg.contains(it) } -> {
//                        delay(230)
//                        opener
//                    }
//                    else -> {
//                        delay(230)
//                        val prompt = StringBuilder()
//                        history.forEach { prompt.appendLine(it) }
//                        prompt.appendLine("$name: ${rawMsg.trim()}")
//
//                        val modelStr = service.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
//                            .getString("selected_model", BotPersona.GENZ_CENTIL.name) ?: BotPersona.GENZ_CENTIL.name
//                        val selectedModel = BotPersona.valueOf(modelStr)
//
//                        val ai = withContext(Dispatchers.IO) {
//                            GeminiApi.generateReply(prompt.toString(), persona, selectedModel)
//                        } ?: OpenerData.delayMessages.random()
//
//                        // ==== FILTER & CLEAN ====
//                        var clean = ai
//                        // 1. Replace pengirim name -> ganteng
//                        clean = clean.replace(Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE), "ganteng ")
//
//                        // 2. Replace punctuations & chars
//                        clean = clean.replace("!", "...")
//                        clean = clean.replace("'", " ")
//                        clean = clean.replace('"', ' ')
//                        clean = clean.replace("~", "...")
//                        clean = clean.replace("*", " ")
//
//                        // remove persona.botName mentions
//                        val namePattern = Regex("\\b${Regex.escape(persona.botName)}\\b[\\p{Punct}\\s]*", RegexOption.IGNORE_CASE)
//                        clean = clean.replace(namePattern, " ")
//
//                        clean = clean.replace(":", " ")
//                        clean = clean.replace("jadi penasaran", " ")
//                        clean = clean.replace("Hai", "iyh ")
//                        clean = clean.replace("\uD83D\uDE1C", " ")
//                        clean = clean.replace("\uD83D\uDE09", "\uD83E\uDD2D ")
//                        clean = clean.replace("\uD83E\uDD2A", " ")
//                        clean = clean.replace(Regex("\\(.*?\\)"), "") // hapus (isi)
//                        clean = clean.replace("[\\[\\]{}]".toRegex(), "") // hapus [,],{,}
//
//                        // 3. replace beberapa kata khusus
//                        val shithayo = listOf("hayo", "hayoo", "hayooo")
//                        shithayo.forEach {
//                            val pattern = Regex("\\b$it\\b", RegexOption.IGNORE_CASE)
//                            clean = clean.replace(pattern, "beb ")
//                        }
//
//                        val forbidden = listOf(
//                            "whatsapp","video","tiktok","instagram","ig","fb","facebook","kasur", "spesial", "sangee", "sange",
//                            "dick","narko","sial","telegram","tele","snapchat","michat","messenger","nomor","elus","racun","anjing","anjrit",
//                            "anjay","kampret","idiot","tolol","memek","ngentot","taik","jancok","goblok","bajingan","banci","pelacur",
//                            "lonte","jembut","colmek","klamin","onani","fuck","shit","asshole","bitch","cunt","faggot","nigger","retard"
//                        )
//                        forbidden.forEach {
//                            clean = clean.replace(Regex(it, RegexOption.IGNORE_CASE), "**")
//                        }
//
//                        clean = clean.replace(Regex("\\s{2,}"), " ").trim()
//
//                        // 4. cek link/gambar/english-weird -> fallback pesan
//                        val urlRegex = Regex("(https?://\\S+|www\\.\\S+)")
//                        val imgRegex = Regex("(!\\[.*?]\\(.*?\\)|\\[image.*?]|\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp)", RegexOption.IGNORE_CASE)
//                        val englishWeirdRegex = Regex("\\b(the|you|your|are|is|am|my|me|a|an|for|with|can|will|shall|of|and|to|in|on|from|by|it|at|as)\\b", RegexOption.IGNORE_CASE)
//
//                        if (urlRegex.containsMatchIn(clean) || imgRegex.containsMatchIn(clean) || englishWeirdRegex.containsMatchIn(clean)) {
//                            clean = OpenerData.delayMessages.random()
//                        }
//
//                        clean = clean.lowercase()
//                        clean
//                    }
//                }
//
//                // 3. kirim reply
//                sendReply(service, input, replyText)
//
//                // 4. update history dan simpan (max 20)
//                history.add("$name: ${rawMsg.trim()}")
//                history.add("${persona.botName}: $replyText")
//                val trimmedHistory = history.takeLast(16)
//                saveHistory(service, userId, trimmedHistory)
//
//                delay(100)
//                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
//
//                // mark processed key
//                val key = "$name|${rawMsg.trim()}"
//                processed.add(key)
//                if (processed.size > MAX_CACHE) processed.remove(processed.first())
//            } catch (e: Exception) {
//                Log.e(TAG, "❌ handleUnread failed: ${e.message}")
//                // pastikan kembali ke layar sebelumnya
//                try { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch(_: Exception) {}
//            }
//        }
//    }
//
//    // ----------------- Diamond Logger -----------------
//    private fun getDiamondText(service: AccessibilityService): String? {
//        val root = service.rootInActiveWindow ?: return null
//        val nodes = root.findAccessibilityNodeInfosByViewId(SUGO_ID_DIAMOND)
//        return nodes.firstOrNull()?.text?.toString()
//    }
//
//    private fun sendTelegramMessage(text: String) {
//        // variant cepat (hard-coded) - ganti token/chat id sesuai kebutuhan
//        val botToken = "7672304092:AAEkAxt3MSMkpm_W_UVrvF0svr9sxmS-GV0"
//        val chatId = "-1002713670199"
//
//        val url = "https://api.telegram.org/bot$botToken/sendMessage"
//        val client = okhttp3.OkHttpClient()
//
//        val requestBody = okhttp3.FormBody.Builder()
//            .add("chat_id", chatId)
//            .add("text", text)
//            .add("parse_mode", "HTML")
//            .add("disable_web_page_preview", "true")
//            .build()
//
//        val request = okhttp3.Request.Builder()
//            .url(url)
//            .post(requestBody)
//            .build()
//
//        client.newCall(request).enqueue(object : okhttp3.Callback {
//            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
//                Log.e(TAG, "❌ Failed to send Telegram: ${e.message}")
//            }
//
//            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
//                Log.d(TAG, "✅ Telegram sent: ${response.code}")
//                response.close()
//            }
//        })
//    }
//
//    fun startDiamondLogger(
//        service: AccessibilityService,
//        scope: CoroutineScope,
//        isRunning: () -> Boolean
//    ) {
//        scope.launch {
//            while (isRunning()) {
//                val text = getDiamondText(service)
//                val todayString = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("id", "ID")).format(Date())
//                val persona = PersonaManager.getPersona(service)
//
//                val logLine = if (text.isNullOrBlank()) {
//                    "⚠️ Bot turu\n\n" +
//                            "👤 : ${persona?.botName ?: "unknown"}\n" +
//                            "💎 : Bot macet bos.. turu cik\n\n" +
//                            todayString
//                } else {
//                    val diamondCount = text.replace("\\D".toRegex(), "").toIntOrNull() ?: 0
//                    val idrAmount = diamondCount * 2.8
//                    val idrFormatted = String.format(Locale.US, "%,.0f", idrAmount)
//
//                    "🚀 Diamond update\n\n" +
//                            "👤 : ${persona?.botName ?: "unknown"}\n" +
//                            "🌐 : SUGO\n" +
//                            "💎 : $text\n" +
//                            "💰 : Rp $idrFormatted\n\n" +
//                            todayString
//                }
//
//                sendTelegramMessage(logLine)
//                delay(30 * 60 * 1000L)
//            }
//        }
//    }
//
//    // ----------------- History helpers -----------------
//    fun loadHistory(context: Context, userId: String): List<String> {
//        val file = File(context.filesDir, "chat_history/$userId.json")
//        if (!file.exists()) return emptyList()
//        return try {
//            file.readLines()
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Failed load history: ${e.message}")
//            emptyList()
//        }
//    }
//
//    fun saveHistory(context: Context, userId: String, history: List<String>) {
//        val dir = File(context.filesDir, "chat_history")
//        if (!dir.exists()) dir.mkdirs()
//        val file = File(dir, "$userId.json")
//        try {
//            file.writeText(history.joinToString("\n"))
//        } catch (e: Exception) {
//            Log.e(TAG, "❌ Failed save history: ${e.message}")
//        }
//    }
//}

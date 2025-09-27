package com.example.ngontol

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.os.postDelayed
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.*
import java.util.logging.Handler

object S1Service {

    private const val TAG = "S1_SERVICE"

    // View-IDs khusus Voicemaker
    private const val SUGO_ID_LIST = "com.voicemaker.android:id/ll_chat_item"
    private const val SUGO_ID_UNREAD = "com.voicemaker.android:id/id_unread_tcv"
    private const val SUGO_ID_INPUT = "com.voicemaker.android:id/id_input_edit_text"
    private const val SUGO_ID_SEND  = "com.voicemaker.android:id/id_send_btn"
    private const val SUGO_ID_DIAMOND = "com.voicemaker.android:id/contentView"

    private const val CACHE_CLEAR_INTERVAL = 30 * 60 * 1000L // 30 menit
    private const val SWITCH_TAB_INTERVAL = 30 * 60 * 1000L
    private const val MAX_CACHE = 20
    private const val PREF_KEY  = "processed_keys"

    private var lastRun = 0L
    private var lastCacheClear = System.currentTimeMillis()
    private var lastSwitchTab = System.currentTimeMillis()
    private var interval = 2500L
    private val processed = LinkedHashSet<String>()
    var isSwitchingTab = false // taruh ini di scope global atau class-level
    var isRefreshing = false // tambahan untuk cache refresh
    var totalClicked = 0
    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        // mulai diamond logger
        Log.d(TAG,"just start")
        S1Service.onAccessibilityEvent(service, null, scope, isRunning)

    }
    // ----------------- Accessibility Event -----------------
    @RequiresApi(Build.VERSION_CODES.O)
    fun onAccessibilityEvent(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        if (!isRunning()) return
        val rootNode = service.rootInActiveWindow

        val cancelBtn = rootNode
            ?.findAccessibilityNodeInfosByViewId("com.voicemaker.android:id/tv_cancel")
            ?.firstOrNull()
            ?: rootNode
                ?.findAccessibilityNodeInfosByViewId("com.voicemaker.android:id/id_close_dialog")
                ?.firstOrNull()

        if (cancelBtn != null) {
            cancelBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d("BotDebug", "Klik tombol cancel ✅")
        }
        val root = service.rootInActiveWindow ?: return

        val now = System.currentTimeMillis()
        if (now - lastCacheClear >= CACHE_CLEAR_INTERVAL) {
            processed.clear()
            service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE).edit().clear().apply()

            val pkg = "com.voicemaker.android"

            // ---------- 1. Simpan tab asal ----------
            var asalIndex: Int? = null
            run {
                val tabs = root.findAccessibilityNodeInfosByViewId("$pkg:id/id_conv_tab_all")
                if (tabs.isNotEmpty()) {
                    val parent = tabs[0].parent
                    for (i in 0 until (parent?.childCount ?: 0)) {
                        val child = parent?.getChild(i) ?: continue
                        if (child.isSelected) {
                            asalIndex = i
                            Log.d("BotDebug", "Tab asal disimpan index=$asalIndex")
                            break
                        }
                    }
                } else {
                    Log.d("BotDebug", "Tidak ada tab asal yang disimpan")
                }
            }

            // ---------- 2. Close & reopen ----------
            val pm = service.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            if (launchIntent != null) {
                service.startActivity(launchIntent)

                // ---------- 3. Klik bottomtab conv ----------
                var convClicked = false

                android.os.Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        if (convClicked) return  // ✅ stop kalau sudah klik conv

                        val root2 = service.rootInActiveWindow
                        val closeBtn = root2?.findAccessibilityNodeInfosByViewId("$pkg:id/close")?.firstOrNull()
                        if (closeBtn != null) {
                            closeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d("BotDebug", "Klik tombol close ✅")
                        }

                        val convTab = root2?.findAccessibilityNodeInfosByViewId(
                            "$pkg:id/id_main_bottomtab_conv"
                        )?.firstOrNull()

                        if (convTab != null) {
                            convTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            convClicked = true  // ✅ tandain biar ga klik lagi
                            Log.d("BotDebug", "Klik bottomtab conv ✅")

                            // balik ke tab asal kalau ada
                            if (asalIndex != null) {
                                android.os.Handler(Looper.getMainLooper()).postDelayed({
                                    val root3 = service.rootInActiveWindow
                                    val tabs2 = root3?.findAccessibilityNodeInfosByViewId("$pkg:id/id_conv_tab_all")
                                    if (!tabs2.isNullOrEmpty()) {
                                        val parent2 = tabs2[0].parent
                                        val backNode = parent2?.getChild(asalIndex!!)
                                        if (backNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                                            Log.d("BotDebug", "Balik ke tab asal index=$asalIndex ✅")
                                        } else {
                                            Log.d("BotDebug", "⚠️ Gagal balik ke tab asal index=$asalIndex")
                                        }
                                    }
                                }, 2000L)
                            }
                        } else {
                            Log.d("BotDebug", "Conv tab belum ketemu, retry...")
                            android.os.Handler(Looper.getMainLooper()).postDelayed(this, 500L)
                        }
                    }
                }, 2500L)

            }
            // telegram sender
            val text = getDiamondText(service)
            val todayString = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("id", "ID")).format(Date())
            val persona = PersonaManager.getPersona(service)

            val logLine = if (text.isNullOrBlank()) {
                "⚠️ Bot turu\n\n" +
                        "👤 : ${persona?.botName ?: "unknown"}\n" +
                        "💎 : Bot macet bos.. turu cik\n\n" +
                        todayString
            } else {
                val diamondCount = text.replace("\\D".toRegex(), "").toIntOrNull() ?: 0
                val idrAmount = diamondCount * 2.8
                val idrFormatted = String.format(Locale.US, "%,.0f", idrAmount)

                "🚀 Diamond update\n\n" +
                        "👤 : ${persona?.botName ?: "unknown"}\n" +
                        "🌐 : SUGO\n" +
                        "💎 : $text\n" +
                        "💰 : Rp $idrFormatted\n\n" +
                        todayString
            }
            lastCacheClear = now
            sendTelegramMessage(logLine)
        }

        if (now - lastRun < interval) return
        lastRun = now

        val rows = root.findAccessibilityNodeInfosByViewId(SUGO_ID_LIST)
        // ---------- Cache clear & pull-to-refresh ----------

        if (rows.isEmpty()) return

        // ---------- Proses unread chat (kode asli lo) ----------
        var unreadOnScreen = 0
        val unreadList = mutableListOf<Triple<AccessibilityNodeInfo, String, String>>()

        for (row in rows) {
            try {
                val name = row.getChild(1)?.text?.toString().orEmpty()
                val msg  = row.getChild(3)?.text?.toString().orEmpty()
                if (name.isBlank() || msg.isBlank()) continue

                val key = "$name|$msg"
                if (key in processed) continue

                val unread = findUnreadCount(row)
                val persona = PersonaManager.getPersona(service)
                if (unread > 0) {
                    val blacklist = persona?.blacklist ?: emptyList()
                    val agenshit = (listOf("agen","agency","mengikutimu.","partner","agensi","agensy") + blacklist).distinct()
                    val msgLow = msg.trim().lowercase()
                    val nameLow = name.trim().lowercase()

                    if (
                        agenshit.any { msgLow.contains(it) } ||
                        agenshit.any { nameLow.contains(it) } ||
                        name.matches(Regex("^\\d+$"))
                    ) {
                        processed.add(key)
                        if (processed.size > MAX_CACHE) processed.remove(processed.first())
                        continue
                    }

                    unreadOnScreen++
                    unreadList.add(Triple(row, name, msg))
                } else {
                    processed.add(key)
                    if (processed.size > MAX_CACHE) processed.remove(processed.first())
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Row error: ${e.message}")
            }
        }

        if (unreadList.isNotEmpty()) {
            scope.launch {
                for ((row, name, msg) in unreadList) {
                    try {
                        handleUnread(service, row, name, msg, scope)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error reply: ${e.message}")
                    } finally {
                        row.recycle()
                    }
                }
                service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE)
                    .edit().putStringSet(PREF_KEY, processed).apply()
                interval = if (unreadOnScreen > 3) 4500L else 2500L
            }
        } else {
            service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE)
                .edit().putStringSet(PREF_KEY, processed).apply()
            interval = if (unreadOnScreen > 3) 4500L else 2500L
        }
    }

    private fun findUnreadCount(row: AccessibilityNodeInfo): Int {

        row.findAccessibilityNodeInfosByViewId(SUGO_ID_UNREAD)
            .firstOrNull()?.text?.toString()?.toIntOrNull()?.let { return it }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until row.childCount) row.getChild(i)?.let { stack.add(it) }

        while (stack.isNotEmpty()) {
            val cur = stack.removeFirst()
            val txt = cur.text?.toString() ?: ""
            if (cur.className == "android.widget.TextView" && txt.matches(Regex("\\d+"))) {
                val count = txt.toInt()
                cur.recycle()
                return count
            }
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { stack.add(it) }
            cur.recycle()
        }
        return 0
    }
    // ----------------- Handle Unread & Reply -----------------
    private fun clickFirstClickable(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null && !cur.isClickable) cur = cur.parent
        return cur?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun waitInputBox(service: AccessibilityService, timeoutMs: Long = 3000): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            service.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId(SUGO_ID_INPUT)
                ?.firstOrNull()?.let { return it }
            Thread.sleep(80)
        }
        Log.e(TAG, "Input box not found")
        return null
    }

    private suspend fun sendReply(service: AccessibilityService, input: AccessibilityNodeInfo, text: String) {
        // delay natural berdasarkan panjang teks (sama prinsip di kode awal)
        val delayTime = if (text.length <= 30) {
            (90..290).random().toLong()
        } else {
            (670..2490).random().toLong()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            val clip = ClipData.newPlainText("reply", text)
            (service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        delay(delayTime)

        // cobalah dua id tombol send: SUGO_ID_SEND dulu, fallback ke id_chat_send_btn
        service.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(SUGO_ID_SEND)
            ?.firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ?: service.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("com.voicemaker.android:id/id_chat_send_btn")
                ?.firstOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleUnread(
        service: AccessibilityService,
        row: AccessibilityNodeInfo,
        name: String,
        rawMsg: String,
        scope: CoroutineScope
    ) {

        // klik item
        if (!clickFirstClickable(row)) {
            return
        }
        Thread.sleep(500) // beri jeda animasi

        // tunggu input box
        val input = waitInputBox(service) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        // ambil persona (jika ga ada, balik)
        val persona = PersonaManager.getPersona(service) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

// pilih opener sesuai jam
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY) // 0..23

        val opener = when (hour) {
            in 5..9 -> OpenerData.morningOpeners.random()
            in 10..15 -> OpenerData.noonOpeners.random()
            in 16..19 -> OpenerData.genZOpeners.random()
            in 20..23 -> OpenerData.nightOpeners.random()
            else -> OpenerData.lateNightOpeners.random()
        }


        // jalankan logic reply di coroutine supaya bisa call network (GeminiApi dsb)
        scope.launch {
            try {
                val safeName = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val userId = safeName

                // 1. load history
                val history = loadHistory(service, userId).toMutableList()

                // 2. tentukan replyText
                val replyText = when {
                    OpenerData.cocokPatterns.any { rawMsg.contains(it) } -> {
                        delay(230)
                        opener
                    }
                    OpenerData.partnerPatterns.any { rawMsg.contains(it) } -> {
                        delay(230)
                        opener
                    }
                    else -> {
                        delay(230)
                        val prompt = StringBuilder()
                        history.forEach { prompt.appendLine(it) }
                        prompt.appendLine("$name: ${rawMsg.trim()}")

                        val modelStr = service.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
                            .getString("selected_model", BotPersona.GENZ_CENTIL.name) ?: BotPersona.GENZ_CENTIL.name
                        val selectedModel = BotPersona.valueOf(modelStr)

                        val ai = withContext(Dispatchers.IO) {
                            GeminiApi.generateReply(
                                service,        // Context
                                prompt.toString(),
                                persona,        // Persona
                                selectedModel   // BotPersona
                            )
                        } ?: OpenerData.delayMessages.random()

                        // ==== FILTER & CLEAN ====
                        var clean = ai
                        // 1. Replace nama pengirim "
                        clean = clean.replace(
                            Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
                        ) {
                            // cari kata pertama yg hanya huruf
                            val firstWord = Regex("[A-Za-z]+").find(it.value)?.value ?: ""
                            "ayang $firstWord"
                        }                        // 2. Replace ! dengan ...
                        clean = clean.replace("!", "...")
                        clean = clean.replace("'", " ")
                        clean = clean.replace('"', ' ')
                        clean = clean.replace("~", "...")
                        clean = clean.replace("*", " ")
                        val namePattern = Regex("\\b${Regex.escape(persona.botName)}\\b[\\p{Punct}\\s]*", RegexOption.IGNORE_CASE)
                        clean = clean.replace(namePattern, " ")
                        clean = clean.replace(":", " ")
                        clean = clean.replace("jadi penasaran", " ")
//                        clean = clean.replace("Hai", "iyh ")
                        clean = clean.replace("\uD83D\uDE1C", " ")
                        clean = clean.replace("\uD83D\uDE09", "\uD83E\uDD2D ")
                        clean = clean.replace("\uD83E\uDD2A", " ")
                        clean = clean
                            .replace(Regex("\\(.*?\\)"), "") // hapus (isi)
                            .replace("[\\[\\]{}]".toRegex(), "") // hapus karakter [, ], {, }

                        // 3. Filter forbidden words 😜
                        val shithayo = listOf("hayo", "hayoo", "hayooo")
                        shithayo.forEach {
                            val pattern = Regex("\\b$it\\b", RegexOption.IGNORE_CASE)
                            clean = clean.replace(pattern, "beb")
                        }

                        val forbidden = listOf(
                            "whatsapp","video","tiktok","instagram","ig","fb","facebook","kasur", "spesial", "sangee", "sange", "dick", "hiya", "narko", "sial", "cil","emut",
                            "telegram","tele","snapchat","michat","messenger","nomor","spesial","elus", "racun", "anjing", "anjrit", "anjay", "anjg", "anjgng",
                            "bangsat", "bngst", "bgsat","emut","bh","colmek","coli","c0li","bra", "kontol", "kontl", "kntl", "kntol", "knt0l", "memek", "mmk", "mek", "mewek", "memk", "ngentot", "ngntot", "ngentod", "ngntd", "ngntl",
                            "taik", "tae", "t4i", "tayk", "jancok", "jancoek", "jancoq", "jencok", "jancuk", "goblok", "gblk", "gblok",
                            "bajingan", "bjingan", "bajngan", "b4jingan", "kampret", "kamvret", "kampret", "kamfret", "idiot", "idi0t", "tolol", "toll", "tolool", "toloolll", "brengsek", "brngsek", "brgsek", "brengsk", "keparat", "keprat", "setan", "syaiton", "satan", "syetan", "bodoh", "bod0h", "bdoh", "pantek", "pantk", "panteq",
                            "pepek", "pepk", "pepq", "silit", "pantat", "bokep", "bencong", "banci", "waria", "pelacur", "lonte", "lontee", "lonteq", "jembut", "jmbt", "jemb0t",
                            "colmek", "colmekk", "klamin", "onani",  "fuck", "fck", "fack", "fuk", "fcku", "fukin", "fcking", "fucker", "motherfucker",
                            "shit", "sh1t", "sht", "sh1t", "shieet", "shiet", "ass", "asss", "arse", "asshole", "hole", "ahole", "bitch", "biatch", "btch", "b1tch", "btc", "cum",
                            "dick", "d1ck", "dck", "dik", "d1k", "pussy", "pusy", "pussi", "puzzy", "pu55y", "bastard", "bstrd", "basturd", "slut", "slutty", "s1ut", "slutt", "whore", "hore", "ho", "h0e", "hoe",
                            "cunt", "cnt", "c3nt", "kunt", "faggot", "fag", "f4g", "fag", "fat", "nigger", "nigga", "n1gga", "nigga", "ningger", "ninggaa", "retard", "retarded", "r3tard", "r3t4rd",
                            "moron", "idiot", "stupid", "dumb", "jerk", "wanker", "twat", "douche", "scumbag", "motherfcker", "mf", "mthrfkr", "mofo"
                        )
//                        timo use " add \ to be string, \"
                        forbidden.forEach {
                            val spaced = it.toCharArray().joinToString(" ")
                            clean = clean.replace(Regex(it, RegexOption.IGNORE_CASE), spaced)
                        }
                        clean = clean.replace(Regex("\\s{2,}"), " ").trim()
                        // 6. Deteksi gambar/link/teks Inggris ngaco
                        val urlRegex = Regex("(https?://\\S+|www\\.\\S+)")

                        val imgRegex = Regex("(!\\[.*?]\\(.*?\\)|\\[image.*?]|\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp)", RegexOption.IGNORE_CASE)
                        val englishWeirdRegex = Regex("\\b(the|you|your|are|is|am|my|me|a|an|for|with|can|will|shall|of|and|to|in|on|from|by|it|at|as)\\b", RegexOption.IGNORE_CASE)

                        // Cek jika ada gambar atau link atau text Inggris ngaco
                        if (urlRegex.containsMatchIn(clean) ||
                            imgRegex.containsMatchIn(clean) ||
                            englishWeirdRegex.containsMatchIn(clean)
                        ) {
                            clean = OpenerData.delayMessages.random()
                        }
                        // 5. Lowercase semua
                        clean = clean.lowercase()
                        clean
                    }
                }

                // 3. kirim reply
                sendReply(service, input, replyText)

                // 4. update history dan simpan (max 20)
                history.add("$name: ${rawMsg.trim()}")
                history.add("${persona.botName}: $replyText")
                val trimmedHistory = history.takeLast(20)
                saveHistory(service, userId, trimmedHistory)

                delay(100)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

                // mark processed key
                val key = "$name|${rawMsg.trim()}"
                processed.add(key)
                if (processed.size > MAX_CACHE) processed.remove(processed.first())
            } catch (e: Exception) {
                Log.e(TAG, "❌ handleUnread failed: ${e.message}")
                // pastikan kembali ke layar sebelumnya
                try { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch(_: Exception) {}
            }
        }
    }

    // ----------------- Diamond Logger -----------------
    private fun getDiamondText(service: AccessibilityService): String? {
        val root = service.rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(SUGO_ID_DIAMOND)
        return nodes.firstOrNull()?.text?.toString()
    }

    private fun sendTelegramMessage(text: String) {
        val botToken = "7672304092:AAEkAxt3MSMkpm_W_UVrvF0svr9sxmS-GV0"
        val chatId = "-1002713670199"

        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val client = okhttp3.OkHttpClient()

        val requestBody = okhttp3.FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", text)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .build()

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "❌ Failed to send Telegram: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                Log.d(TAG, "✅ Telegram sent: ${response.code}")
                response.close()
            }
        })
    }


    // ----------------- History helpers -----------------
    fun loadHistory(context: Context, userId: String): List<String> {
        val file = File(context.filesDir, "chat_history/$userId.json")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed load history: ${e.message}")
            emptyList()
        }
    }

    fun saveHistory(context: Context, userId: String, history: List<String>) {
        val dir = File(context.filesDir, "chat_history")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$userId.json")
        try {
            file.writeText(history.joinToString("\n"))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed save history: ${e.message}")
        }
    }
}
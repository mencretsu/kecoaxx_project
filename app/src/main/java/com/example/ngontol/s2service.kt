package com.example.ngontol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalTime
import java.util.LinkedHashSet

object S2Service {
    const val TAG = "S2_SERVICE"

    private const val SUGO_ID_INPUT = "com.hwsj.club:id/editContent"
    private const val SUGO_ID_SEND = "com.hwsj.club:id/ivSend"
    private const val SUGO_ID_UNREAD = "com.hwsj.club:id/tvBadge"
    private const val CACHE_CLEAR_INTERVAL = 15 * 60 * 1000L // 15 menit

    private var lastRun = 0L
    private var interval = 2500L
    private var lastCacheClear = System.currentTimeMillis()
    private val processed = LinkedHashSet<String>()
    var isRefreshing = false // tambahan untuk cache refresh

    @RequiresApi(Build.VERSION_CODES.O)
    fun start(service: AccessibilityService, scope: CoroutineScope, isRunning: () -> Boolean) {
        Log.d(TAG, "🚀 S2Service.start() dipanggil")
        // Trigger manual tanpa event
        onAccessibilityEvent(service, null, scope, isRunning)
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun onAccessibilityEvent(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        scope: CoroutineScope,
        isRunning: () -> Boolean
    ) {
        if (!isRunning()) return
        if (clickIfCancelExists(service)) {
            Log.d(TAG, "Dialog ketemu → klik Batal, skip proses lain")
            return
        }

        val now = System.currentTimeMillis()
        if (now - S2Service.lastRun < S2Service.interval) return
        S2Service.lastRun = now

        val root = service.rootInActiveWindow ?: return

// cek kalau ada text "Tidak ada data"
        val emptyNode = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/tvEmpty")
            .firstOrNull { it.text?.toString()?.contains("Tidak ada data", ignoreCase = true) == true }

        if (emptyNode != null) {
            Log.d(S2Service.TAG, "⛔ Ada 'Tidak ada data' → skip total")
            return
        }
        // ---------- Cache clear & pull-to-refresh ----------
        if (now - lastCacheClear >= S2Service.CACHE_CLEAR_INTERVAL) {
            processed.clear()
            service.getSharedPreferences("bot_cache", Context.MODE_PRIVATE).edit().clear().apply()

            val scrollable =
                root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/vp_message")
                    ?.firstOrNull { it.isScrollable }

            if (scrollable != null) {
                isRefreshing = true // mulai refresh
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

                // delay supaya UI sempat refresh
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    isRefreshing = false // refresh selesai, baru bisa proses chat
                    lastCacheClear = System.currentTimeMillis()
                }, 2500L) // bisa di-adjust sesuai kecepatan UI
            } else {
                lastCacheClear = now
            }
        }
// --- lanjut scan rows kalau ada data ---
        val rows = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/title")
        Log.d(S2Service.TAG, "rows=${rows.size}")
        if (rows.size < 4) return // pastiin ada minimal 2 item

        val unreadList = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        val blacklist = listOf("Tag", "Pesan interaktif", "Asisten Resmi", "Match Hi", "Video", "Suara", "Keintiman")

        if (rows.size > 4) {
            val secondRow = rows[4]
            val name = secondRow.text?.toString().orEmpty()
            Log.d(S2Service.TAG, "⚡ Ambil item ke fck = $name")

            if (name.isNotBlank() && name !in blacklist) {
                unreadList.add(secondRow to name)
            } else {
                Log.d(S2Service.TAG, "⛔ Skip karena masuk blacklist/blank: $name")
            }
        } else {
            Log.d(S2Service.TAG, "⚠️ Rows kurang dari 5 → skip total")
        }


        Log.d(S2Service.TAG, "✅ unreadList=${unreadList.size}")

        if (unreadList.isNotEmpty()) {
            scope.launch {
                for ((row, name) in unreadList) {
                    try {
                        Log.d("BOT", "- handleUnread dipanggil untuk $name")
                        handleUnread(service, row, name, scope)
                    } catch (e: Exception) {
                        Log.e(S2Service.TAG, "❌ Error reply: ${e.message}")
                    } finally {
                        row.recycle()
                    }
                }
                // atur interval biar ga spam
                S2Service.interval = if (unreadList.size > 3) 4500L else 2500L
            }
        }
    }

    private fun clickIfCancelExists(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false

        // Cari tombol batal
        val cancelBtn = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/negativeButton")
            ?.firstOrNull { it.text?.toString()?.contains("Batal", ignoreCase = true) == true }

        if (cancelBtn != null) {
            val rect = Rect()
            cancelBtn.getBoundsInScreen(rect)
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()

            val path = android.graphics.Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            Log.d(TAG, "⛔ Klik tombol Batal otomatis di ($x,$y)")

            return service.dispatchGesture(gesture, null, null)
        }

        return false
    }

    // ----------------- Handle Unread & Reply -----------------
    private fun clickFirstClickable(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()

        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        Log.d("BOT", "👉 Gesture klik di ($x,$y)")

        return service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d("BOT", "🖱️ Klik sukses via gesture di ($x,$y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w("BOT", "⚠️ Gesture klik dibatalkan ($x,$y)")
                }
            },
            null
        )
    }

    private fun waitInputBox(service: AccessibilityService, timeoutMs: Long = 3000): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val input = service.rootInActiveWindow
                ?.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/editContent")
                ?.firstOrNull()
            if (input != null) {
                Log.d(TAG, "✅ Input box ditemukan")
                return input
            }
            Thread.sleep(80)
        }
        Log.e(TAG, "❌ Input box not found")
        return null
    }

    @SuppressLint("ServiceCast")
    private suspend fun sendReply(service: AccessibilityService, input: AccessibilityNodeInfo, text: String) {
        val delayTime = if (text.length <= 30) (990..999).random().toLong() else (1370..1690).random().toLong()

        val rect = Rect()
        input.getBoundsInScreen(rect)
        val path = android.graphics.Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        service.dispatchGesture(gesture, null, null)
        Thread.sleep(300)

        BotKeyboard.instance?.typeText(text)
        Log.d(TAG, "⌨️ BotKeyboard commit: $text")

        delay(delayTime)
// ⬇️ Tutup keyboard langsung setelah ngetik
        input.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)

        val sendBtn = service.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(SUGO_ID_SEND)
            ?.firstOrNull()

        if (sendBtn != null) {

            val rect2 = Rect()
            sendBtn.getBoundsInScreen(rect2)
            val cx = rect2.centerX().toFloat()
            val cy = rect2.centerY().toFloat()

            val path2 = android.graphics.Path().apply { moveTo(cx, cy) }
            val gesture2 = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path2, 0, 100))
                .build()

            service.dispatchGesture(gesture2, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "📨 Klik tombol kirim sukses")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "⚠️ Klik tombol kirim dibatalkan")
                }
            }, null)
        } else {
            Log.w(TAG, "⚠️ Tombol kirim tidak ditemukan")
        }
    }
    private fun waitLastMessage(service: AccessibilityService, timeoutMs: Long = 5000): String? {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = service.rootInActiveWindow ?: continue
            val messages = root.findAccessibilityNodeInfosByViewId("com.hwsj.club:id/content")

            if (!messages.isNullOrEmpty()) {
                val last = messages.lastOrNull()
                val text = last?.text?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) {
                    Log.d(S2Service.TAG, "✅ Pesan terakhir ketemu: $text")
                    return text
                }
            }

            Thread.sleep(150) // jangan spam, kasih jeda
        }

        Log.w(S2Service.TAG, "⚠️ Pesan terakhir gak ketemu (timeout)")
        return null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleUnread(
        service: AccessibilityService,
        row: AccessibilityNodeInfo,
        name: String,
        scope: CoroutineScope
    ) {

        Log.d("BOT", "- handle unread - next clicking")

        if (!clickFirstClickable(service, row)) return
        Thread.sleep(1500)

        val input = waitInputBox(service)
        if (input == null) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        Thread.sleep(1500)

        val rawMsg = waitLastMessage(service)
        if (rawMsg.isNullOrBlank()) {
            Log.w(S2Service.TAG, "⚠️ Gagal ambil pesan terakhir, balik ke list")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }


        if (rawMsg.isBlank()) {
            Log.w(TAG, "⚠️ Pesan kosong dari $name")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        val persona = PersonaManager.getPersona(service) ?: run {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return
        }

        val hour = LocalTime.now().hour
        val opener = when (hour) {
            in 5..9 -> OpenerData.morningOpeners.random()
            in 10..15 -> OpenerData.noonOpeners.random()
            in 16..19 -> OpenerData.genZOpeners.random()
            in 20..23 -> OpenerData.nightOpeners.random()
            else -> OpenerData.lateNightOpeners.random()
        }

        scope.launch {
            try {
                val safeName = name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val userId = safeName
                val history = loadHistory(service, userId).toMutableList()

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
                        // 1. Replace nama pengirim dengan "ganteng"
                        clean = clean.replace(
                            Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
                        ) {
                            // cari kata pertama yg hanya huruf
                            val firstWord = Regex("[A-Za-z]+").find(it.value)?.value ?: ""
                            "ayang $firstWord"
                        }
                        // 2. Replace ! dengan ...
                        clean = clean.replace("!", "...")
                        clean = clean.replace("'", " ")
                        clean = clean.replace('"', ' ')
                        clean = clean.replace("~", "...")
                        clean = clean.replace("*", " ")
                        val namePattern = Regex("\\b${Regex.escape(persona.botName)}\\b[\\p{Punct}\\s]*", RegexOption.IGNORE_CASE)
                        clean = clean.replace(namePattern, " ")
                        clean = clean.replace(":", " ")
//                        clean = clean.replace("jadi penasaran", " ")
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
                            clean = clean.replace(pattern, "beb ")
                        }

                        val forbidden = listOf(
                            "whatsapp","video","tiktok","instagram","ig","fb","facebook","kasur", "spesial", "sangee", "sange", "dick", "hiya", "narko", "sial", "sial", "duit", "tf", "transfer", "cil","emut", "uang",
                            "telegram","tele","snapchat","michat","messenger","nomor","spesial","elus", "racun", "anjing", "anjrit", "anjay", "anjg", "anjgng",
                            "bangsat", "bngst", "bgsat","emut","bh","colmek","coli","c0li","bra", "kontol", "kontl", "kntl", "kntol", "knt0l", "memek", "mmk", "mek", "mewek", "memk", "ngentot", "ngntot", "ngentod", "ngntd", "ngntl",
                            "taik", "tae", "t4i", "tayk", "jancok", "jancoek", "jancoq", "jencok", "jancuk", "goblok", "gblk", "gblok",
                            "bajingan", "bjingan", "bajngan", "b4jingan", "kampret", "kamvret", "kampret", "kamfret", "idiot", "idi0t", "tolol", "toll", "tolool", "toloolll", "brengsek", "brngsek", "brgsek", "brengsk", "keparat", "keprat", "setan", "syaiton", "satan", "syetan", "bodoh", "bod0h", "bdoh", "pantek", "pantk", "panteq",
                            "pepek", "pepk", "pepq", "silit", "pantat", "bokep", "bencong", "banci", "waria", "pelacur", "lonte", "lontee", "lonteq", "jembut", "jmbt", "jemb0t",
                            "colmek", "colmekk", "klamin", "onani",  "fuck", "fck", "fack", "fuk", "fcku", "fukin", "fcking", "fucker", "motherfucker",
                            "shit", "sh1t", "sht", "sh1t", "shieet", "shiet", "ass", "asss", "arse", "asshole", "hole", "ahole", "bitch", "biatch", "btch", "b1tch", "btc", "cum",
                            "dick", "d1ck", "dck", "dik", "d1k", "pussy", "pusy", "pussi", "puzzy", "pu55y", "bastard", "bstrd", "basturd", "slut", "slutty", "s1ut", "slutt", "whore", "hore", "ho", "h0e", "hoe",
                            "cunt", "cnt", "c3nt", "kunt", "faggot", "fag", "f4g", "fag", "fat", "nigger", "nigga", "n1gga", "nigga", "ningger", "ninggaa", "retard", "retarded", "r3tard", "r3t4rd",
                            "moron", "idiot", "stupid", "dumb", "jerk", "wanker", "twat", "douche", "scumbag", "motherfcker", "mf", "mthrfkr", "mofo", "ngocok", "cicil", "cil", "coli"
                        )
                        forbidden.forEach {
                            val spaced = it.toCharArray().joinToString("\"")
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
                            clean = "ehhh bentar aku ke wc dlu beb "
                        }
                        // 5. Lowercase semua
                        clean = clean.lowercase()
                        clean

                    }
                }

                sendReply(service, input, replyText)

                history.add("$name: ${rawMsg.trim()}")
                history.add("${persona.botName}: $replyText")
                val trimmedHistory = history.takeLast(16)
                saveHistory(service, userId, trimmedHistory)

                delay(900)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Log.w(S2Service.TAG, "back 1  < --")

                delay(500) // kasih jeda biar animasi selesai
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Log.w(S2Service.TAG, "back 2 < --")

            } catch (e: Exception) {
                Log.e(TAG, "❌ handleUnread failed: ${e.message}")
                try { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } catch(_: Exception) {}
            }
        }
    }

    private fun loadHistory(context: Context, userId: String): List<String> {
        val file = File(context.filesDir, "chat_history/$userId.json")
        if (!file.exists()) return emptyList()
        return try { file.readLines() } catch (_: Exception) { emptyList() }
    }

    private fun saveHistory(context: Context, userId: String, history: List<String>) {
        val dir = File(context.filesDir, "chat_history")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$userId.json")
        file.writeText(history.joinToString("\n"))
    }
}

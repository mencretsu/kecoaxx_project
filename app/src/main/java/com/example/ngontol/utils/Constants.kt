package com.example.ngontol.utils

object BotConstants {
    // ✅ 2 jam restart interval (lebih masuk akal)
    const val CACHE_CLEAR_INTERVAL = 15 * 60 * 1000L  // 30 menit
    const val POST_RESTART_COOLDOWN = 30000L              // 30 detik
    const val MIN_RESTART_INTERVAL = 30000L               // 30 detik
    const val AUTO_LAUNCH_DELAY = 10000L                  // 10 deti0
    const val LAUNCH_COOLDOWN = 30000L                    // 30 detik

    const val MAX_CACHE = 20
    const val DEFAULT_INTERVAL = 2500L
    const val PREF_KEY_PROCESSED = "processed_keys"
    const val PREF_NAME_CACHE = "bot_cache"
    const val PREF_NAME_BOT = "bot_prefs"

    object Telegram {
        const val BOT_TOKEN = "7672304092:AAEkAxt3MSMkpm_W_UVrvF0svr9sxmS-GV0"
        const val CHAT_ID = "-1002713670199"
    }
}
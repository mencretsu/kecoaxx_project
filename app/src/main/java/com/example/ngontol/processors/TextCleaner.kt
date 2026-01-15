package com.example.ngontol.processors

import com.example.ngontol.OpenerData
import com.example.ngontol.Persona

object TextCleaner {

    private val FORBIDDEN_WORDS = listOf(
        // Social Media & Messaging Apps
        "whatsapp", "wa", "watsap", "whasap", "telegram", "tele", "tg", "telegramm", "semut",
        "instagram", "ig", "insta", "instragam", "facebook", "fb", "facbook", "facebok",
        "tiktok", "snapchat", "snap", "michat", "mi chat", "messenger", "msngr",
        "line", "wechat", "discord", "skype", "viber", "signal", "kik",
        "twitter", "x", "youtube", "yt", "linkedin", "reddit", "tumblr", "twitch",

        // Contact & Transfer Related
        "nomor", "nomer", "no hp", "nohp", "kontak", "contact", "pin bb", "dm me",
        "transfer", "tf", "pulsa", "uang", "duit", "bayar", "kirim", "saldo",

        // Toxic & Profanity (Indonesian)
        "anjing", "anjir", "anjrit", "anjay", "anjg", "anjgng", "jir", "njir", "nying",
        "bangsat", "bngst", "bgsat", "bajingan", "bjingan", "bajngan", "b4jingan",
        "kampret", "kamvret", "kamfret", "keparat", "keprat", "kprt",
        "goblok", "gblk", "gblok", "goblog", "tolol", "toll", "tolool", "tll", "tlol",
        "brengsek", "brngsek", "brgsek", "brengsk", "brengs3k",
        "setan", "syaiton", "satan", "syetan", "iblis", "sial", "sialan", "bgsd",
        "bodoh", "bod0h", "bdoh", "bodo", "pantek", "pantk", "panteq", "pntek",
        "jancok", "jancoek", "jancoq", "jencok", "jancuk", "cok",
        "taik", "tae", "t4i", "tayk", "tai", "eek",

        // Toxic & Profanity (English)
        "fuck", "fck", "fack", "fuk", "fcku", "fukin", "fcking", "fucker", "fker", "motherfucker",
        "shit", "sh1t", "sht", "shieet", "shiet", "crap", "damn", "dmn",
        "ass", "asss", "arse", "asshole", "ahole",
        "bitch", "biatch", "btch", "b1tch", "btc", "beach",
        "bastard", "bstrd", "basturd", "bstd",
        "cunt", "cnt", "c3nt", "kunt",
        "moron", "idiot", "idi0t", "stupid", "stpd", "dumb", "jerk", "wanker", "twat", "douche", "scumbag",
        "mf", "mthrfkr", "mofo",
        "faggot", "fag", "f4g", "nigger", "nigga", "n1gga", "ningger", "ninggaa",
        "retard", "retarded", "r3tard", "r3t4rd",

        // Adult/NSFW Content (Indonesian)
        "kontol", "kontl", "kntl", "kntol", "knt0l", "ktl", "kont0l",
        "memek", "mmk", "mek", "mewek", "memk", "meki", "emek",
        "ngentot", "ngntot", "ngentod", "ngntd", "ngntl", "entot", "ntot",
        "pepek", "pepk", "pepq", "puki", "pepe",
        "jembut", "jmbt", "jemb0t", "jmb0t",
        "colmek", "colmekk", "coli", "c0li", "colok",
        "onani", "onan1", "crotz", "crot", "crott", "becek",
        "pelacur", "lonte", "lontee", "lonteq", "perek", "jablay", "jabl4y",
        "bencong", "banci", "waria", "bencot",
        "toket", "tobrut", "tete", "nenen", "susu", "tetek",
        "pantat", "silit", "bokong", "pntt",
        "puting", "pentil", "pntl",
        "ngaceng", "ngacng", "ereksi",
        "bh", "bra", "celdam",
        "emut", "hisap", "elus", "raba", "pegang",
        "kasur", "ranjang",  "hotel", "kos",
        "klamin", "kelamin", "vital",
        "sangee", "sange", "sangeee", "horny", "birahi",
        "desah", "desahan", "erang", "erangan", "stmj", "stm",

        // Adult/NSFW Content (English)
        "dick", "d1ck", "dck", "dik", "d1k", "cock", "penis", "pen1s",
        "pussy", "pusy", "pussi", "puzzy", "pu55y", "vagina", "vag",
        "cum", "cumm", "jizz", "sperm",
        "slut", "slutty", "s1ut", "slutt",
        "whore", "h0e", "hoe", "prostitute",
        "sex", "s3x", "sexy", "s3xy",
        "porn", "p0rn", "porno", "pornhub", "xvideos", "xnxx",
        "onlyfans", "nsfw",
        "boobs", "boob", "tits", "titties", "breast",
        "nude", "naked", "telanjang",
        "blowjob", "bj", "handjob", "hj", "footjob",
        "masturbate", "fap",
        "anal", "butt", "booty",
        "milf", "dilf", "gilf",
        "hentai", "doujin", "ecchi",
        "bokep", "bokp", "b0kep", "jav",

        // Slang & Mancing
        "hiya", "hiyaaaat", "cil", "gacil", "gacor", "gaskeun", "gasskk",

        // Substances
        "narko", "narkoba", "sabu", "ganja", "pil", "drugs", "weed", "cocaine", "heroin",
        "racun", "narkotika", "putaw",

        // Weapons & Violence
        "bom", "bomb", "granat", "grenade", "pistol", "senapan", "peluru", "senjata", "weapon",
        "bunuh", "kill", "die", "bundir", "suicide",

        // Marriage & Dating Scam
        "nikah", "kawin", "married", "marry", "menikah", "merit", "vc","vcs",

        // Transaction & Scam Related
        "rekening", "rek", "rekber", "rekeningber", "escrow", "uang","pulsa","transfer",
        "dana", "ovo", "gopay", "shopeepay", "linkaja", "jenius", "blu",
        "atm", "bca", "mandiri", "bri", "bni", "cimb", "permata", "bank",
        "top up", "topup", "isi saldo", "withdraw", "wd", "tarik tunai",
        "payment", "pembayaran", "pay", "paid", "lunas",
        "invoice", "bill", "tagihan", "cicilan", "utang", "hutang", "pinjam",
        "invest", "profit", "passive income",
        "bisnis", "business", "peluang", "opportunity", "join",
        "member", "membership", "vip", "premium", "upgrade", "paket",
         "multi level", "network marketing",  "dropship",
        "komisi", "commission", "cashback", "reward", "hadiah",
         "sale",
        "cod",  "pengiriman", "jne", "jnt",
        "fake", "scam", "penipuan",
        "ktp", "identitas", "foto ktp", "selfie ktp", "verifikasi", "verify",
        "kode otp", "otp", "kode verifikasi", "sms", "token",
        "pinjol", "kredit", "loan", "borrow",
        "trading", "forex", "saham", "crypto", "bitcoin", "eth", "binance",
        "judi", "slot", "casino", "betting", "taruhan", "togel", "poker",
        "admin", "cs", "customer service", "hub admin", "chat admin",
        "klaim", "claim", "redeem", "tukar",
         "penjahat", "hacker", "phising", "phishing",
        "giveaway", "undian", "lucky draw", "winner",
        "survey", "survei", "kuesioner", "questionnaire", "isi form",
        "referral", "referal", "refferal", "ref", "ajak teman", "invite",
        "marketplace", "tokopedia", "shopee", "lazada", "bukalapak", "blibli",
        "cek ongkir", "ongkos kirim", "resi", "tracking", "lacak",
        "password", "pass", "pw", "sandi", "kata sandi",
        "akun", "account", "username", "user", "login", "sign in",
         "share", "berbagi",
         "need", "urgent", "darurat", "emergency",
        "cepat", "fast", "kilat", "instant", "instan", "now",
        "easy", "secret", "private",
        "blokir", "block", "banned", "suspend", "anj", "anjing",
        "seller", "penjual", "jual", "sell", "beli", "buy",
         "legit",
        "testimoni", "testi", "review", "ulasan", "feedback",
        "garansi", "guarantee", "jaminan", "refund", "return",

        // Miscellaneous Spam/Scam
        "video", "link", "click", "download", "unduh", "sial",
        "promo", "bonus", "free", "nomornya", "nomernya", "nomormu"
    )
    private val PHONE_REGEX = Regex(
        // Super robust - detect nomor telepon dengan berbagai separator
        // 0 diikuti 9-12 digit dengan optional separator (dash, spasi, dot, underscore, parenthesis, slash, comma)
        "\\b0[0-9][0-9\\s\\-._()\\[\\]/,]*[0-9]{7,}\\b|" +
                // +62 atau 62 (country code) dengan separator
                "\\+?62[0-9\\s\\-._()\\[\\]/,]*[0-9]{8,}|" +
                // Format: (0)811 2345 6789 atau variasi lainnya
                "\\(0\\)[0-9\\s\\-._()\\[\\]/,]{9,}|" +
                // Catch: 081-123-456-789 (multiple dash)
                "\\b0[0-9](?:[\\s\\-._,]+[0-9]+){3,}\\b|" +
                // Catch: 081.123.456.789 (dengan dot)
                "\\b0[0-9](?:\\.[0-9]{2,4})+\\b|" +
                // Catch: 081_123_456_789 (dengan underscore)
                "\\b0[0-9](?:_[0-9]{2,4})+\\b|" +
                // Catch: 081(123)456(789) atau variant bracket
                "\\b0[0-9](?:\\([0-9]+\\))*[0-9]{6,}\\b"
        , RegexOption.IGNORE_CASE)

    // Fungsi helper untuk mengecek apakah text mengandung nomor
    private fun containsPhoneNumber(text: String): Boolean {
        return PHONE_REGEX.containsMatchIn(text)
    }
    private val DEFAULT_BLACKLIST = listOf("agen", "agency", "partner", "mengikutimu.", "asisten resmi")

    private val forbiddenRegex: Regex by lazy {
        val pattern = FORBIDDEN_WORDS.joinToString("|") { Regex.escape(it) }
        Regex("\\b($pattern)\\b", RegexOption.IGNORE_CASE)
    }

    private val REPLACE_PATTERNS = mapOf(
        "!" to "...",
        "'" to " ",
        "\"" to " ",
        "~" to "...",
        "*" to " ",
        "\uD83E\uDD2A" to " ",
        "\uD83D\uDE12" to " ",
        "\uD83D\uDE1C" to " ",
        "\uD83D\uDE1B" to " ",
         ":" to " ",
        "boong" to "bohong"
    )

    private val URL_REGEX = Regex("(https?://\\S+|www\\.\\S+)")
    private val IMG_REGEX = Regex("(!\\[.*?]\\(.*?\\)|\\[image.*?]|\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp)", RegexOption.IGNORE_CASE)
    private val ENGLISH_REGEX = Regex("\\b(the|you|your|are|is|am|my|me|a|an|for|with|can|will|shall|of|and|to|in|on|from|by|it|at|as)\\b", RegexOption.IGNORE_CASE)
    // Whitelist emoji yang BOLEH lewat (sisanya dihapus semua)
    private val ALLOWED_LAUGH_EMOJI = setOf(
        "😂", "🤣", "😆", "😹", "😅", "😁",
        "😀", "😃", "😄", "😊", "😋", "😎", "😍", "😘", "😗", "😙", "😚", "🙂",
        "🤗", "🤩", "🤔", "🤨", "😐", "😑", "😶", "🙄", "😏", "😣", "😥", "😮", "🤐", "😯", "😪",
        "😫", "🥱", "😴", "😌", "😝", "🤤", "😓", "😔", "😕", "🙃", "🫠", "🤑", "😲",
        "☹️", "🙁", "😖", "😞", "😟", "😤", "😢", "😭", "😦", "😧", "😨", "😩", "🤯", "😬",
        "😰", "😱", "🥵", "🥶", "😳", "😵", "😵‍💫", "🥴", "😠", "😡", "🤬", "😷", "🤒",
        "🤕", "🤢", "🤮", "🤧", "😇", "🥳", "🥸", "🥺", "🤠", "🫢", "🫣", "🫡", "🤥", "🫨",
        "👋", "🤚", "🖐", "✋", "🖖", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "🖕", "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌",
        "🫶", "👐", "🤲", "🙏", "✍️", "💅", "🤳", "💪", "🦾"
    )

    // Regex untuk nangkep SEMUA emoji
    private val EMOJI_REGEX = Regex(
        "[\\p{So}\\p{Sc}\\p{Sk}\\p{Sm}]|" + // Unicode symbols
                "[\\u2600-\\u27BF]|" + // Misc symbols
                "[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]|" + // Surrogate pairs (emoji modern)
                "[\\u2300-\\u23FF]|" + // Misc technical
                "\\u2B50|" + // Star
                "[\\u231A-\\u231B]|" + // Watch
                "[\\u23E9-\\u23FA]|" + // Media controls
                "[\\u25AA-\\u25FE]|" + // Geometric shapes
                "[\\u2934-\\u2935]|" + // Arrows
                "[\\u2190-\\u21FF]|" + // More arrows
                "\\u3030|" + // Wavy dash
                "\\u303D|" + // Part alternation mark
                "\\u3297|" + // Japanese congratulations
                "\\u3299|" + // Japanese secret
                "\\uFE0F|" + // Variation selector
                "\\u200D" // Zero Width Joiner
    )

    fun clean(
        text: String,
        userName: String,
        botName: String,
        persona: Persona? = null
    ): String {
        var cleaned = text

        cleaned = cleaned.replace(
            Regex("(?i)\\s*${Regex.escape(botName)}\\s*:\\s*"),
            ""
        )

        cleaned = cleaned.replace(
            Regex("\\b${Regex.escape(userName)}\\b", RegexOption.IGNORE_CASE)
        ) {
            val firstWord = Regex("[A-Za-z]+").find(it.value)?.value ?: ""
            firstWord  // ✅ Hapus "ayang " di sini
        }

        REPLACE_PATTERNS.forEach { (old, new) -> cleaned = cleaned.replace(old, new) }

        cleaned = cleaned
            .replace(Regex("\\(.*?\\)"), "")
            .replace("[\\[\\]{}]".toRegex(), "")

        listOf("hayo", "hayoo", "hayooo").forEach {
            cleaned = cleaned.replace(Regex("\\b$it\\b", RegexOption.IGNORE_CASE), " ")
        }

        cleaned = forbiddenRegex.replace(cleaned) { match ->
            val txt = match.value
            val half = (txt.length + 1) / 2
            txt.take(half) + "*".repeat(txt.length - half)
        }

        persona?.blacklist?.let { blacklist ->
            if (blacklist.isNotEmpty()) {
                val personaPattern = blacklist.joinToString("|") { Regex.escape(it) }
                val personaRegex = Regex("\\b($personaPattern)\\b", RegexOption.IGNORE_CASE)

                cleaned = personaRegex.replace(cleaned) { match ->
                    val txt = match.value
                    val half = (txt.length + 1) / 2
                    txt.take(half) + "*".repeat(txt.length - half)
                }
            }
        }

        val defaultPattern = DEFAULT_BLACKLIST.joinToString("|") { Regex.escape(it) }
        val defaultRegex = Regex("\\b($defaultPattern)\\b", RegexOption.IGNORE_CASE)
        cleaned = defaultRegex.replace(cleaned) { match ->
            val txt = match.value
            val half = (txt.length + 1) / 2
            txt.take(half) + "*".repeat(txt.length - half)
        }

        // ✅ FIXED: Filter emoji dengan cara yang lebih robust
        // 1. Cari semua emoji sequence
        // 2. Cek apakah ada di whitelist (compare by codepoints, bukan string)
        // 3. Hapus yang tidak diperbolehkan
        cleaned = EMOJI_REGEX.replace(cleaned) { match ->
            val emojiSequence = match.value

            // ✅ Check apakah emoji ini diperbolehkan
            val isAllowed = ALLOWED_LAUGH_EMOJI.any { allowedEmoji ->
                // Compare by content, handle surrogate pairs
                emojiSequence == allowedEmoji ||
                        emojiSequence.codePoints().toArray().contentEquals(allowedEmoji.codePoints().toArray())
            }

            if (isAllowed) {
                emojiSequence
            } else {
                // Coba pecah dan cek base emoji (tanpa modifier)
                val baseEmoji = emojiSequence.codePoints()
                    .filter { cp ->
                        // Ambil base emoji, skip modifier
                        cp < 0x1F3FB || cp > 0x1F3FF // Skip skin tone
                    }
                    .toArray()
                    .let { String(it, 0, it.size) }

                val isBaseAllowed = ALLOWED_LAUGH_EMOJI.any { allowedEmoji ->
                    baseEmoji == allowedEmoji ||
                            baseEmoji.codePoints().toArray().contentEquals(allowedEmoji.codePoints().toArray())
                }

                if (isBaseAllowed) baseEmoji else "" // Hapus emoji yang tidak diperbolehkan
            }
        }

        cleaned = cleaned.replace(Regex("\\s{2,}"), " ").trim()

        if (URL_REGEX.containsMatchIn(cleaned) ||
            IMG_REGEX.containsMatchIn(cleaned) ||
            ENGLISH_REGEX.containsMatchIn(cleaned)) {
            return OpenerData.delayMessages.random()
        }
        if (containsPhoneNumber(cleaned)) {
            return OpenerData.delayMessages.random()
        }
        return cleaned.lowercase()
    }
}
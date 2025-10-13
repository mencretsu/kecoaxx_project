package com.example.ngontol.processors

import com.example.ngontol.OpenerData
import com.example.ngontol.Persona

object TextCleaner {

    private val FORBIDDEN_WORDS = listOf(
        // Social Media & Messaging Apps
        "whatsapp", "wa", "watsap", "whasap", "telegram", "tele", "tg", "telegramm",
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
        "taik", "tae", "t4i", "tayk", "tai", "eek", "ek",

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
        "colmek", "colmekk", "coli", "c0li", "colok", "cl",
        "onani", "onan1", "crotz", "crot", "crott", "becek",
        "pelacur", "lonte", "lontee", "lonteq", "perek", "jablay", "jabl4y",
        "bencong", "banci", "waria", "bencot",
        "toket", "tobrut", "tete", "nenen", "susu", "tetek",
        "pantat", "silit", "bokong", "pntt",
        "puting", "pentil", "pntl",
        "ngaceng", "ngacng", "tegang", "ereksi",
        "bh", "bra", "cd", "celdam",
        "emut", "hisap", "elus", "raba", "pegang",
        "kasur", "ranjang", "kamar", "hotel", "kos",
        "klamin", "kelamin", "vital", "intim",
        "sangee", "sange", "sangeee", "horny", "birahi",
        "desah", "desahan", "erang", "erangan", "stmj", "stm",

        // Adult/NSFW Content (English)
        "dick", "d1ck", "dck", "dik", "d1k", "cock", "penis", "pen1s",
        "pussy", "pusy", "pussi", "puzzy", "pu55y", "vagina", "vag",
        "cum", "cumm", "jizz", "sperm",
        "slut", "slutty", "s1ut", "slutt",
        "whore", "hore", "ho", "h0e", "hoe", "prostitute",
        "sex", "s3x", "sexy", "s3xy",
        "porn", "p0rn", "porno", "pornhub", "xvideos", "xnxx",
        "onlyfans", "of", "nsfw",
        "boobs", "boob", "tits", "titties", "breast",
        "nude", "naked", "telanjang",
        "blowjob", "bj", "handjob", "hj", "footjob",
        "masturbate", "fap",
        "anal", "butt", "booty",
        "milf", "dilf", "gilf",
        "hentai", "doujin", "ecchi",
        "bokep", "bokp", "b0kep", "jav",

        // Slang & Mancing
        "hiya", "hiyaaaat", "cil", "gacil", "gacor", "gas", "gaskeun", "gasskk",
        "aowkwk", "wkwk", "wkkw", "kwkw",

        // Substances
        "narko", "narkoba", "sabu", "ganja", "pil", "drugs", "weed", "cocaine", "heroin",
        "racun", "narkotika", "obat", "putaw",

        // Weapons & Violence
        "bom", "bomb", "granat", "grenade", "pistol", "senapan", "peluru", "senjata", "weapon",
        "bunuh", "kill", "mati", "die", "bundir", "suicide",

        // Marriage & Dating Scam
        "nikah", "kawin", "married", "marry", "menikah", "merit",

        // Transaction & Scam Related
        "rekening", "rek", "rekber", "rekeningber", "escrow",
        "dana", "ovo", "gopay", "shopeepay", "linkaja", "jenius", "blu",
        "atm", "bca", "mandiri", "bri", "bni", "cimb", "permata", "bank",
        "top up", "topup", "isi saldo", "withdraw", "wd", "tarik tunai",
        "payment", "pembayaran", "pay", "paid", "lunas",
        "invoice", "bill", "tagihan", "cicilan", "utang", "hutang", "pinjam",
        "invest", "investasi", "profit", "untung", "cuan", "passive income",
        "bisnis", "business", "peluang", "opportunity", "join", "daftar", "register",
        "member", "membership", "vip", "premium", "upgrade", "paket",
        "mlm", "multi level", "network marketing", "reseller", "dropship",
        "komisi", "commission", "cashback", "reward", "hadiah",
        "voucher", "kupon", "diskon", "sale",
        "cod", "barang", "pengiriman", "jne", "jnt",
        "fake", "palsu", "scam", "penipuan", "tipu", "bohong", "nipu",
        "ktp", "identitas", "foto ktp", "selfie ktp", "verifikasi", "verify",
        "kode otp", "otp", "kode verifikasi", "sms", "token",
        "pinjol", "kredit", "loan", "borrow",
        "trading", "forex", "saham", "crypto", "bitcoin", "eth", "binance",
        "judi", "slot", "casino", "betting", "taruhan", "togel", "poker",
        "admin", "cs", "customer service", "hub admin", "chat admin",
        "klaim", "claim", "redeem", "tukar",
        "modus", "penjahat", "hacker", "hack", "phising", "phishing",
        "giveaway", "ga", "undian", "lucky draw", "pemenang", "winner",
        "survey", "survei", "kuesioner", "questionnaire", "isi form",
        "referral", "referal", "refferal", "ref", "ajak teman", "invite",
        "marketplace", "tokopedia", "shopee", "lazada", "bukalapak", "blibli",
        "cek ongkir", "ongkos kirim", "resi", "tracking", "lacak",
        "password", "pass", "pw", "sandi", "kata sandi",
        "akun", "account", "username", "user", "login", "sign in",
        "minta", "kasih", "bagi", "share", "berbagi",
        "butuh", "perlu", "need", "urgent", "darurat", "emergency",
        "cepat", "fast", "kilat", "instant", "instan", "sekarang", "now",
        "mudah", "gampang", "easy", "simple", "tanpa ribet",
        "jangan bilang", "rahasia", "secret", "private", "pribadi",
        "blokir", "block", "banned", "suspend",
        "seller", "penjual", "jual", "sell", "beli", "buy",
        "trusted", "terpercaya", "aman", "safe", "legit",
        "testimoni", "testi", "review", "ulasan", "feedback",
        "garansi", "guarantee", "jaminan", "refund", "return",

        // Miscellaneous Spam/Scam
        "video", "link", "klik", "click", "download", "unduh", "spesial", "special",
        "promo", "bonus", "gratis", "free"
    )

    private val DEFAULT_BLACKLIST = listOf("agen", "agency", "partner", "mengikutimu.")

    // ✅ OPTIMIZED: Compile regex ONCE at initialization
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
        ":" to " ",
        "boong" to "bohong"
    )

    private val URL_REGEX = Regex("(https?://\\S+|www\\.\\S+)")
    private val IMG_REGEX = Regex("(!\\[.*?]\\(.*?\\)|\\[image.*?]|\\.jpg|\\.jpeg|\\.png|\\.gif|\\.webp)", RegexOption.IGNORE_CASE)
    private val ENGLISH_REGEX = Regex("\\b(the|you|your|are|is|am|my|me|a|an|for|with|can|will|shall|of|and|to|in|on|from|by|it|at|as)\\b", RegexOption.IGNORE_CASE)

    private val ALLOWED_LAUGH_EMOJI = listOf(
        "😂","🤣","😆","😹","😅","😁",
        "😀","😃","😄","😅","😆","😉","😊","😋","😎","😍","😘","😗","😙","😚","🙂",
        "🤗","🤩","🤔","🤨","😐","😑","😶","🙄","😏","😣","😥","😮","🤐","😯","😪",
        "😫","🥱","😴","😌","😛","😜","😝","🤤","😒","😓","😔","😕","🙃","🫠","🤑",
        "😲","☹️","🙁","😖","😞","😟","😤","😢","😭","😦","😧","😨","😩","🤯","😬",
        "😰","😱","🥵","🥶","😳","🤪","😵","😵‍💫","🥴","😠","😡","🤬","😷","🤒",
        "🤕","🤢","🤮","🤧","😇","🥳","🥸","🥺","🤠","🫢","🫣","🫡","🤥","🫨",
        "👋","🤚","🖐","✋","🖖","👌","🤌","🤏","✌️","🤞","🫰","🤟","🤘","🤙",
        "👈","👉","👆","🖕","👇","☝️","👍","👎","✊","👊","🤛","🤜","👏","🙌",
        "🫶","👐","🤲","🙏","✍️","💅","🤳","💪","🦾"
    )
    private val EMOJI_REGEX = Regex("[\\p{So}\\p{Cn}]")

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
            "ayang $firstWord"
        }

        REPLACE_PATTERNS.forEach { (old, new) -> cleaned = cleaned.replace(old, new) }

        cleaned = cleaned
            .replace(Regex("\\(.*?\\)"), "")
            .replace("[\\[\\]{}]".toRegex(), "")

        listOf("hayo", "hayoo", "hayooo").forEach {
            cleaned = cleaned.replace(Regex("\\b$it\\b", RegexOption.IGNORE_CASE), "beb")
        }

        // ✅ OPTIMIZED: Single regex for ALL forbidden words
        cleaned = forbiddenRegex.replace(cleaned) { match ->
            val txt = match.value
            val half = (txt.length + 1) / 2
            txt.take(half) + "*".repeat(txt.length - half)
        }

        // ✅ OPTIMIZED: Handle persona blacklist separately (if provided)
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

        // ✅ Handle DEFAULT_BLACKLIST
        val defaultPattern = DEFAULT_BLACKLIST.joinToString("|") { Regex.escape(it) }
        val defaultRegex = Regex("\\b($defaultPattern)\\b", RegexOption.IGNORE_CASE)
        cleaned = defaultRegex.replace(cleaned) { match ->
            val txt = match.value
            val half = (txt.length + 1) / 2
            txt.take(half) + "*".repeat(txt.length - half)
        }

        cleaned = cleaned.map { char ->
            val str = char.toString()
            if (ALLOWED_LAUGH_EMOJI.contains(str)) str else {
                if (EMOJI_REGEX.containsMatchIn(str)) "" else str
            }
        }.joinToString("")

        cleaned = cleaned.replace(Regex("\\s{2,}"), " ").trim()

        if (URL_REGEX.containsMatchIn(cleaned) ||
            IMG_REGEX.containsMatchIn(cleaned) ||
            ENGLISH_REGEX.containsMatchIn(cleaned)) {
            return OpenerData.delayMessages.random()
        }

        return cleaned.lowercase()
    }
}
package com.opendroid.ai.core.voice

/**
 * Rewrites Tanglish (Tamil in English letters) into Tamil script so a Tamil voice reads it
 * the way it is meant to sound. Rule based and offline; it is approximate, so real English
 * sentences are detected and left alone for the English voice.
 */
object TanglishToTamil {

    private val englishHints = setOf(
        "the", "is", "are", "you", "your", "and", "to", "of", "it", "this", "that", "for", "with",
        "can", "not", "have", "has", "was", "will", "would", "could", "please", "opened", "playing",
        "sorry", "couldn't", "isn't", "i", "my", "me", "on", "in", "at", "be", "been", "from"
    )

    private val loanWords = mapOf(
        "sir" to "சார்", "youtube" to "யூடியூப்", "whatsapp" to "வாட்ஸ்அப்", "capcut" to "கேப்கட்",
        "jarvis" to "ஜார்விஸ்", "ok" to "ஓகே", "okay" to "ஓகே", "hi" to "ஹாய்", "hello" to "ஹலோ",
        "gemini" to "ஜெமினி", "groq" to "குரோக்", "chat" to "சாட்", "video" to "வீடியோ",
        "message" to "மெசேஜ்", "app" to "ஆப்", "open" to "ஓபன்", "search" to "சர்ச்",
        "subscribe" to "சப்ஸ்கிரைப்", "channel" to "சேனல்", "edit" to "எடிட்", "phone" to "ஃபோன்",
        "settings" to "செட்டிங்ஸ்", "permission" to "பர்மிஷன்", "music" to "மியூசிக்",
        "volume" to "வால்யூம்", "call" to "கால்", "contact" to "காண்டாக்ட்", "number" to "நம்பர்",
        "error" to "எரர்", "sorry" to "சாரி", "thanks" to "தேங்க்ஸ்", "time" to "டைம்",
        "battery" to "பேட்டரி", "wifi" to "வைஃபை", "bluetooth" to "புளூடூத்",
        "instagram" to "இன்ஸ்டாகிராம்", "telegram" to "டெலிகிராம்", "google" to "கூகுள்",
        "spotify" to "ஸ்பாட்டிஃபை", "status" to "ஸ்டேட்டஸ்", "ready" to "ரெடி", "done" to "டன்",
        "start" to "ஸ்டார்ட்", "stop" to "ஸ்டாப்", "play" to "பிளே", "pause" to "பாஸ்",
        "send" to "சென்ட்", "text" to "டெக்ஸ்ட்", "camera" to "கேமரா", "screen" to "ஸ்கிரீன்",
        "tap" to "டேப்", "click" to "கிளிக்", "button" to "பட்டன்", "please" to "ப்ளீஸ்",
        "yes" to "எஸ்", "no" to "நோ", "online" to "ஆன்லைன்", "mode" to "மோட்", "teaching" to "டீச்சிங்",
        "reference" to "ரெஃபரன்ஸ்", "style" to "ஸ்டைல்", "folder" to "ஃபோல்டர்", "file" to "ஃபைல்",
        "download" to "டவுன்லோட்", "upload" to "அப்லோட்", "update" to "அப்டேட்", "internet" to "இன்டர்நெட்",
        "voice" to "வாய்ஸ்", "male" to "மேல்", "female" to "ஃபீமேல்", "key" to "கீ", "api" to "ஏபிஐ"
    )

    private class Cons(val bases: List<String>)

    private val consonants: List<Pair<String, Cons>> = listOf(
        "ndr" to Cons(listOf("ன", "ற")), "ng" to Cons(listOf("ங", "க")), "nj" to Cons(listOf("ஞ", "ஜ")),
        "th" to Cons(listOf("த")), "dh" to Cons(listOf("த")), "sh" to Cons(listOf("ஷ")),
        "ch" to Cons(listOf("ச")), "zh" to Cons(listOf("ழ")), "kh" to Cons(listOf("க")),
        "gh" to Cons(listOf("க")), "bh" to Cons(listOf("ப")), "ph" to Cons(listOf("ஃப")),
        "k" to Cons(listOf("க")), "g" to Cons(listOf("க")), "c" to Cons(listOf("க")),
        "j" to Cons(listOf("ஜ")), "t" to Cons(listOf("ட")), "d" to Cons(listOf("ட")),
        "p" to Cons(listOf("ப")), "b" to Cons(listOf("ப")), "m" to Cons(listOf("ம")),
        "y" to Cons(listOf("ய")), "r" to Cons(listOf("ர")), "l" to Cons(listOf("ல")),
        "v" to Cons(listOf("வ")), "w" to Cons(listOf("வ")), "s" to Cons(listOf("ச")),
        "h" to Cons(listOf("ஹ")), "f" to Cons(listOf("ஃப")), "z" to Cons(listOf("ஜ")),
        "q" to Cons(listOf("க")), "x" to Cons(listOf("க", "ஸ")), "n" to Cons(listOf("ன"))
    )

    // independent letter, vowel sign (empty for the inherent "a")
    private class Vow(val independent: String, val sign: String)

    private val vowels: List<Pair<String, Vow>> = listOf(
        "aa" to Vow("ஆ", "ா"), "ai" to Vow("ஐ", "ை"), "au" to Vow("ஔ", "ௌ"),
        "ee" to Vow("ஈ", "ீ"), "ii" to Vow("ஈ", "ீ"), "oo" to Vow("ஊ", "ூ"),
        "uu" to Vow("ஊ", "ூ"), "ou" to Vow("ஔ", "ௌ"),
        "a" to Vow("அ", ""), "e" to Vow("எ", "ெ"), "i" to Vow("இ", "ி"),
        "o" to Vow("ஒ", "ொ"), "u" to Vow("உ", "ு")
    )

    private const val PULLI = "்"

    private val tamilRomanHints = setOf(
        "naan", "nan", "na", "neenga", "nee", "ne", "enakku", "unakku", "unga",
        "epdi", "eppadi", "iruka", "irukku", "irukkum", "pannunga", "panra", "panren",
        "pannu", "sollu", "sollunga", "venum", "vendaam", "illa", "illai", "aama",
        "seri", "ippo", "eppo", "enna", "enga", "romba", "nalla", "mudiyum",
        "mudiyala", "vaanga", "paaru", "paathu", "kudunga", "appo", "apram",
        "athu", "idhu", "ithu", "dhan", "thaan", "maari", "pola", "kooda"
    )

    /** True when [text] reads like real English rather than Tanglish. */
    fun looksEnglish(text: String): Boolean {
        val words = Regex("[A-Za-z']+").findAll(text).map { it.value.lowercase() }.toList()
        if (words.size < 3) return false
        return words.count { it in englishHints } >= 2
    }

    /** True when Roman-letter text contains common spoken-Tamil transliterations. */
    fun looksTanglish(text: String): Boolean {
        val words = Regex("[A-Za-z']+").findAll(text).map { it.value.lowercase() }.toList()
        return words.any { it in tamilRomanHints } && !looksEnglish(text)
    }

    /** Converts every Roman word in [text] to Tamil script; other characters are kept. */
    fun convert(text: String): String =
        Regex("[A-Za-z']+").replace(text) { word(it.value.lowercase().replace("'", "")) }

    private fun word(w: String): String {
        loanWords[w]?.let { return it }
        val sb = StringBuilder()
        var pending: String? = null
        fun flush() {
            pending?.let { sb.append(it).append(PULLI) }
            pending = null
        }
        var i = 0
        while (i < w.length) {
            val c = consonants.firstOrNull { w.startsWith(it.first, i) }
            if (c != null) {
                for (base in c.second.bases) {
                    flush()
                    pending = if (i == 0 && c.first == "n") "ந" else base
                }
                i += c.first.length
                continue
            }
            val v = vowels.firstOrNull { w.startsWith(it.first, i) }
            if (v != null) {
                if (pending != null) {
                    sb.append(pending).append(v.second.sign)
                    pending = null
                } else {
                    sb.append(v.second.independent)
                }
                i += v.first.length
                continue
            }
            flush()
            sb.append(w[i])
            i++
        }
        flush()
        return sb.toString()
    }
}

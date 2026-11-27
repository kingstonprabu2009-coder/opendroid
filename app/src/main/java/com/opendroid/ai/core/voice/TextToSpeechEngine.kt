package com.opendroid.ai.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class TextToSpeechEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val elevenLabsBaseUrl: String = "https://api.elevenlabs.io",
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val edgeTts = EdgeTtsClient(OkHttpClient.Builder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build())
    private var isInitialized = false
    // Completes when the phone's TTS engine has finished starting (it takes a moment).
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Bumped by stop(). A speak() started under an older generation must not play
    // anything, including the local fallback, once its fetch or playback unwinds.
    private val generation = AtomicLong()
    @Volatile private var inFlightCall: Call? = null

    // The player and the cached clip it plays are replaced and released together.
    private val playerLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var currentClip: File? = null

    // deleteOnExit never runs on Android, so clips from earlier processes are still here.
    // speak() waits for this so it cannot delete a clip that is about to play.
    private val staleClipCleanup = scope.launch { deleteCachedClips() }

    /** Called on the main thread when real audio for an utterance starts. */
    var onStartListener: ((utteranceId: String) -> Unit)? = null

    /** Called on the main thread with the id speak() returned, when that utterance ends. */
    var onCompletionListener: ((utteranceId: String) -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post { onStartListener?.invoke(utteranceId.orEmpty()) }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId?.contains(CHUNK_MARK) == true) return
                mainHandler.post {
                    onCompletionListener?.invoke(utteranceId.orEmpty())
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId?.contains(CHUNK_MARK) == true) return
                mainHandler.post { showProblem("Jarvis voice error $errorCode") }
                onError(utteranceId)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    onCompletionListener?.invoke(utteranceId.orEmpty())
                }
            }
        })
    }

    /** Toasts are UI work: speech/network coroutines run on Dispatchers.IO. */
    private fun showProblem(message: String) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onInit(status: Int) {
        configure(status)
        ready.complete(Unit)
    }

    private fun configure(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            // Jarvis speaks Tamil (India). If the Tamil voice data is missing on this phone,
            // fall back to Indian English, then the phone's default language, so speech never goes silent.
            var result = engine.setLanguage(Locale.forLanguageTag("ta-IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = engine.setLanguage(Locale.forLanguageTag("en-IN"))
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = engine.setLanguage(Locale.getDefault())
            }
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
                // Play on the media volume.
                engine.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Pitch and speed come from the phone's Text-to-speech settings.
            }
        }
    }

    /** Tamil-script text and recognized Tanglish use the Tamil voice; English uses Indian English. */
    private fun hasTamil(text: String): Boolean = text.any { it in '\u0B80'..'\u0BFF' }

    /** Legacy local-phone voice preference; provider selection is stored separately. */
    private fun voiceProvider(): String =
        context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getString("provider", "phone") ?: "phone"

    private fun voiceChoice(): String =
        context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getString("gender", "male") ?: "male"

    /** Picks an installed voice matching the chosen gender; null means keep the phone default. */
    private fun pickVoice(engine: TextToSpeech, lang: String): android.speech.tts.Voice? {
        val choice = voiceChoice()
        if (choice == "phone") return null
        val voices = try { engine.voices?.filter { it.locale.language == lang } } catch (e: Exception) { null }
            ?: return null
        // A voice the user picked by ear in Settings wins (Google voices carry no gender in their names).
        val saved = context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getString("voice_$lang", null)
        if (saved != null) voices.firstOrNull { it.name == saved }?.let { return it }
        fun isMale(v: android.speech.tts.Voice) = v.name.contains("male", true) && !v.name.contains("female", true)
        fun isFemale(v: android.speech.tts.Voice) = v.name.contains("female", true)
        return if (choice == "male") voices.firstOrNull { isMale(it) && !it.isNetworkConnectionRequired }
            ?: voices.firstOrNull { isMale(it) }
        else voices.firstOrNull { isFemale(it) && !it.isNetworkConnectionRequired }
            ?: voices.firstOrNull { isFemale(it) }
    }

    /** Picks the Tamil or Indian English voice for [text]. */
    private fun prepareVoiceFor(engine: TextToSpeech, text: String) {
        val tag = if (hasTamil(text)) "ta" else "en"
        val locale = if (tag == "ta") Locale.forLanguageTag("ta-IN") else Locale.forLanguageTag("en-IN")
        try {
            var r = engine.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = engine.setLanguage(Locale.forLanguageTag("en-IN"))
            }
            val v = pickVoice(engine, locale.language)
            if (v != null) engine.setVoice(v)
            // No name match: keep the phone's selected voice (e.g. Tamil Voice II) as it is.
        } catch (e: Exception) {
            // Keep whatever voice is already set.
        }
    }

    /** Speaks a short sample with the saved choice (used by the Test voice button). */
    fun speakTest() { speak("Vanakkam sir, naan Jarvis. Intha voice ok va?") }

    /** Removes JSON / code blocks and symbols so Jarvis only speaks the human sentence. */
    private fun cleanForSpeech(raw: String): String {
        var s = raw.replace(Regex("```[\\s\\S]*?```"), " ")
        s = s.replace("```", " ")
        s = s.replace(Regex("\\[\\s*\\{[\\s\\S]*\\}\\s*\\]"), " ")
        s = s.replace(Regex("[*_#`]+"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Speaks [text] and returns [utteranceId], which [onCompletionListener] receives when
     * this utterance, and not some other one, finishes.
     */
    fun speak(rawText: String, utteranceId: String = UUID.randomUUID().toString()): String {
        val text = cleanForSpeech(rawText)
        // Convert only recognizable Tanglish for the local Tamil voice. English and
        // already-Tamil replies keep their original wording.
        val localSpeechText = if (!hasTamil(text) && TanglishToTamil.looksTanglish(text)) {
            TanglishToTamil.convert(text)
        } else {
            text
        }
        if (text.isBlank()) {
            mainHandler.post { onCompletionListener?.invoke(utteranceId) }
            return utteranceId
        }
        val speechGeneration = generation.get()
        scope.launch {
            staleClipCleanup.join()
            val config = settingsRepository.llmConfig.first()
            val apiKey = config.elevenLabsApiKey
            val voiceId = config.elevenLabsVoiceId.ifEmpty { "pNInz6obpgDQGcFmaJgB" }
            val provider = voiceProvider()

            if (provider == "elevenlabs" && apiKey.isBlank()) {
                showProblem("ElevenLabs key missing; using phone voice for this reply.")
            }

            if (provider == "edge") {
                try {
                    val edgeVoice = selectedEdgeVoice(localSpeechText)
                    val success = playEdgeTts(localSpeechText, edgeVoice, utteranceId, speechGeneration)
                    if (success) return@launch
                } catch (_: Exception) { }
                if (generation.get() == speechGeneration) {
                    showProblem("Edge Neural unavailable; using phone voice for this reply.")
                } else return@launch
                // Fall through to the built-in Android TTS fallback below.
            }

            if (provider == "elevenlabs" && apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId, utteranceId, speechGeneration)
                    if (success) return@launch
                    showProblem("Selected ElevenLabs voice could not generate audio. Check its key and Voice ID, or choose another voice engine.")
                } catch (_: Exception) {
                    showProblem("ElevenLabs unavailable; using phone voice for this reply.")
                }
                // Fall through to the built-in Android TTS fallback below.
            }

            // Free on-device Phone TTS is the default and the reliable fallback.
            // Local playback must run on the main thread. Not after stop(): a cancelled
            // ElevenLabs fetch fails, and must not turn into local speech.
            // The first reply often arrives before the engine is ready, so wait for it.
            withTimeoutOrNull(1500) { ready.await() }
            if (generation.get() == speechGeneration) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val engine = tts
                    var started = false
                    if (isInitialized && engine != null) {
                        prepareVoiceFor(engine, localSpeechText)
                        // Long replies are split at sentence ends and queued, so Android TTS
                        // never cuts or drops a reply for being too long.
                        val parts = splitForSpeech(localSpeechText, minOf(TextToSpeech.getMaxSpeechInputLength() - 200, 1500))
                        started = true
                        parts.forEachIndexed { i, part ->
                            val last = i == parts.lastIndex
                            val id = if (last) utteranceId else "$utteranceId$CHUNK_MARK$i"
                            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                            if (engine.speak(part, mode, null, id) != TextToSpeech.SUCCESS) started = false
                        }
                    }
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    if (started && audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                        showProblem("Media volume 0-la irukku. Volume ethunga.")
                    }
                    if (!started) {
                        showProblem("Jarvis voice start aagala. Phone Text-to-speech settings check pannunga.")
                        onCompletionListener?.invoke(utteranceId)
                    }
                }
            }
        }
        return utteranceId
    }

    private data class EdgeVoiceChoice(val id: String, val pitch: String = "+0Hz", val rate: String = "+0%")

    private fun selectedEdgeVoice(text: String): EdgeVoiceChoice {
        val prefs = context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE)
        val chosenProfile = prefs.getString("edge_voice", "ta-IN-ValluvarNeural") ?: "ta-IN-ValluvarNeural"
        val chosen = chosenProfile.substringBefore('|')
        val pitch = when (chosenProfile.substringAfter('|', "")) {
            "soft" -> "-2Hz"
            "bright" -> "+2Hz"
            else -> prefs.getString("edge_pitch", "+0Hz") ?: "+0Hz"
        }
        val rate = prefs.getString("edge_rate", "+0%") ?: "+0%"
        val voice = if (hasTamil(text)) chosen else when (chosen) {
            "ta-IN-PallaviNeural", "ta-LK-SaranyaNeural", "ta-MY-KaniNeural", "ta-SG-VenbaNeural" -> "en-IN-NeerjaNeural"
            else -> "en-IN-PrabhatNeural"
        }
        return EdgeVoiceChoice(voice, pitch, rate)
    }

    private fun playEdgeTts(text: String, voice: EdgeVoiceChoice, utteranceId: String, speechGeneration: Long): Boolean {
        if (generation.get() != speechGeneration) return true
        val clip = File.createTempFile("edge_tts_", ".mp3", context.cacheDir)
        if (!edgeTts.synthesize(text, voice.id, clip, voice.pitch, voice.rate)) { clip.delete(); return false }
        return prepareAndPlayClip(clip, utteranceId, speechGeneration)
    }

    private fun playElevenLabsTts(
        text: String,
        apiKey: String,
        voiceId: String,
        utteranceId: String,
        speechGeneration: Long
    ): Boolean {
        val url = "$elevenLabsBaseUrl/v1/text-to-speech/$voiceId"
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val jsonPayload = """
            {
              "text": "$escapedText",
              "model_id": "eleven_monolingual_v1",
              "voice_settings": {
                "stability": 0.5,
                "similarity_boost": 0.75
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        if (generation.get() != speechGeneration) return true
        val call = client.newCall(request)
        inFlightCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return false

                val clip = File.createTempFile(CLIP_PREFIX, CLIP_SUFFIX, context.cacheDir)
                try {
                    FileOutputStream(clip).use { fos ->
                        response.body.byteStream().copyTo(fos)
                    }
                } catch (e: Exception) {
                    clip.delete()
                    throw e
                }

                return prepareAndPlayClip(clip, utteranceId, speechGeneration)
            }
        } finally {
            if (inFlightCall === call) inFlightCall = null
        }
    }

    /**
     * Preparing media can block while Android opens/decodes the file. Never hold playerLock
     * during prepare(): stop() is called from the main thread and must never wait on decoding.
     */
    private fun prepareAndPlayClip(clip: File, utteranceId: String, speechGeneration: Long): Boolean {
        if (generation.get() != speechGeneration) {
            clip.delete()
            return true
        }
        val player = MediaPlayer()
        try {
            player.setDataSource(clip.absolutePath)
            player.prepare() // caller is the TTS IO dispatcher; deliberately outside playerLock
            player.setOnCompletionListener { finishClip(player, utteranceId) }
            player.setOnErrorListener { _, _, _ -> finishClip(player, utteranceId); true }
            synchronized(playerLock) {
                if (generation.get() != speechGeneration) {
                    player.release()
                    clip.delete()
                    return true
                }
                // Replacing a clip mid-play releases it, which fires no completion.
                releasePlayerLocked()
                mediaPlayer = player
                currentClip = clip
                player.start()
            }
            mainHandler.post { onStartListener?.invoke(utteranceId) }
            return true
        } catch (e: Exception) {
            synchronized(playerLock) {
                if (mediaPlayer === player) {
                    releasePlayerLocked()
                } else {
                    runCatching { player.release() }
                    clip.delete()
                }
            }
            throw e
        }
    }

    private fun finishClip(player: MediaPlayer, utteranceId: String) {
        synchronized(playerLock) {
            if (mediaPlayer === player) releasePlayerLocked()
        }
        mainHandler.post {
            onCompletionListener?.invoke(utteranceId)
        }
    }

    private fun releasePlayerLocked() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentClip?.delete()
        currentClip = null
    }

    private fun deleteCachedClips() {
        context.cacheDir.listFiles { file ->
            file.name.startsWith(CLIP_PREFIX) && file.name.endsWith(CLIP_SUFFIX)
        }?.forEach { it.delete() }
    }

    private fun splitForSpeech(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (sentence in text.split(Regex("(?<=[.!?।])\\s+"))) {
            if (cur.isNotEmpty() && cur.length + sentence.length + 1 > max) {
                out.add(cur.toString()); cur.clear()
            }
            if (sentence.length > max) {
                sentence.chunked(max).forEach { out.add(it) }
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(sentence)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** Silences speech in progress and drops any ElevenLabs clip still being fetched. */
    fun stop() {
        generation.incrementAndGet()
        inFlightCall?.cancel()
        edgeTts.cancel()
        scope.coroutineContext.cancelChildren()
        tts?.stop()
        synchronized(playerLock) { releasePlayerLocked() }
    }

    fun destroy() {
        generation.incrementAndGet()
        inFlightCall?.cancel()
        edgeTts.cancel()
        scope.cancel()
        tts?.shutdown()
        tts = null
        synchronized(playerLock) { releasePlayerLocked() }
    }

    private companion object {
        const val CHUNK_MARK = "#chunk"
        const val CLIP_PREFIX = "elevenlabs_"
        const val CLIP_SUFFIX = ".mp3"
    }
}
// END

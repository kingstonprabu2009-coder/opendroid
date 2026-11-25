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
    private var isInitialized = false
    // Completes when the phone's TTS engine has finished starting (it takes a moment).
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Bumped by stop(). A speak() started under an older generation must not play
    // anything, including the local fallback, once its fetch or playback unwinds.
    private val generation = AtomicLong()
    @Volatile private var inFlightCall: Call? = null
    private val edgeTts = EdgeTtsClient(client)

    // The player and the cached clip it plays are replaced and released together.
    private val playerLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var currentClip: File? = null

    // deleteOnExit never runs on Android, so clips from earlier processes are still here.
    // speak() waits for this so it cannot delete a clip that is about to play.
    private val staleClipCleanup = scope.launch { deleteCachedClips() }

    /** Called on the main thread with the id speak() returned, when that utterance ends. */
    var onCompletionListener: ((utteranceId: String) -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    onCompletionListener?.invoke(utteranceId.orEmpty())
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
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

    private fun showProblem(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
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
                // Deeper, calmer voice, closer to a male Jarvis style.
                engine.setPitch(0.72f)
                engine.setSpeechRate(0.95f)
            }
        }
    }

    /** Text with Tamil letters is read by the Tamil voice; Tanglish / English by Indian English. */
    private fun hasTamil(text: String): Boolean = text.any { it in '\u0B80'..'\u0BFF' }

    /** Language of [text] from its script. English letters (Tanglish) count as Tamil, as before. */
    private fun scriptOf(text: String): String = when {
        text.any { it in '\u0B80'..'\u0BFF' } -> "ta"
        text.any { it in '\u0900'..'\u097F' } -> "hi"
        text.any { it in '\u0C00'..'\u0C7F' } -> "te"
        text.any { it in '\u0C80'..'\u0CFF' } -> "kn"
        text.any { it in '\u0D00'..'\u0D7F' } -> "ml"
        else -> "latin"
    }

    /** Male Edge voice for the language. Tanglish (English letters) uses the Tamil voice. */
    private fun edgeVoiceFor(text: String): String = when (scriptOf(text)) {
        "hi" -> "hi-IN-MadhurNeural"
        "te" -> "te-IN-MohanNeural"
        "kn" -> "kn-IN-GaganNeural"
        "ml" -> "ml-IN-MidhunNeural"
        else -> "ta-IN-ValluvarNeural"
    }

    /** Picks the Tamil or Indian English voice for [text]. */
    private fun prepareVoiceFor(engine: TextToSpeech, text: String) {
        val locale = when (scriptOf(text)) {
            "ta" -> Locale.forLanguageTag("ta-IN")
            "hi" -> Locale.forLanguageTag("hi-IN")
            "te" -> Locale.forLanguageTag("te-IN")
            "kn" -> Locale.forLanguageTag("kn-IN")
            "ml" -> Locale.forLanguageTag("ml-IN")
            else -> Locale.forLanguageTag("en-IN")
        }
        try {
            var r = engine.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = engine.setLanguage(Locale.forLanguageTag("en-IN"))
            }
        } catch (e: Exception) {
            // Keep whatever voice is already set.
        }
    }

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
        if (text.isBlank()) {
            mainHandler.post { onCompletionListener?.invoke(utteranceId) }
            return utteranceId
        }
        val speechGeneration = generation.get()
        scope.launch {
            staleClipCleanup.join()
            val config = settingsRepository.llmConfig.first()
            val apiKey = config.elevenLabsApiKey
            val voiceId = config.elevenLabsVoiceId.ifEmpty { "pNInz6obpgDQGcFmaJgB" } // default Adam (male) voice

            if (apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId, utteranceId, speechGeneration)
                    if (success) return@launch
                } catch (e: Exception) {
                    // Fallback to local TTS if ElevenLabs fails
                }
            }

            // Free male Tamil voice. If it fails for any reason, the phone voice below speaks.
            try {
                if (generation.get() != speechGeneration) return@launch
                val mp3 = edgeTts.synthesize(text, edgeVoiceFor(text))
                if (mp3 != null && playClipBytes(mp3, utteranceId, speechGeneration)) return@launch
            } catch (e: Exception) {
                // Fall through to the phone voice.
            }

            // Local fallback — must run on main thread. Not after stop(): a cancelled
            // ElevenLabs fetch fails, and must not turn into local speech.
            // The first reply often arrives before the engine is ready, so wait for it.
            withTimeoutOrNull(5000) { ready.await() }
            if (generation.get() == speechGeneration) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val engine = tts
                    var started = false
                    if (isInitialized && engine != null) {
                        prepareVoiceFor(engine, text)
                        started = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) ==
                            TextToSpeech.SUCCESS
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

                synchronized(playerLock) {
                    if (generation.get() != speechGeneration) {
                        clip.delete()
                        return true
                    }
                    // Replacing a clip mid-play releases it, which fires no completion.
                    releasePlayerLocked()
                    val player = MediaPlayer()
                    mediaPlayer = player
                    currentClip = clip
                    try {
                        player.setDataSource(clip.absolutePath)
                        player.prepare()
                        player.setOnCompletionListener { finishClip(player, utteranceId) }
                        player.setOnErrorListener { _, _, _ ->
                            finishClip(player, utteranceId)
                            true
                        }
                        player.start()
                    } catch (e: Exception) {
                        releasePlayerLocked()
                        throw e
                    }
                }
                return true
            }
        } finally {
            if (inFlightCall === call) inFlightCall = null
        }
    }

    /** Plays mp3 [bytes]. Returns false if it could not start, so the caller can fall back. */
    private fun playClipBytes(bytes: ByteArray, utteranceId: String, speechGeneration: Long): Boolean {
        if (generation.get() != speechGeneration) return true
        val clip = File.createTempFile(CLIP_PREFIX, CLIP_SUFFIX, context.cacheDir)
        try {
            FileOutputStream(clip).use { it.write(bytes) }
        } catch (e: Exception) {
            clip.delete()
            return false
        }
        synchronized(playerLock) {
            if (generation.get() != speechGeneration) {
                clip.delete()
                return true
            }
            releasePlayerLocked()
            val player = MediaPlayer()
            mediaPlayer = player
            currentClip = clip
            try {
                player.setDataSource(clip.absolutePath)
                player.prepare()
                player.setOnCompletionListener { finishClip(player, utteranceId) }
                player.setOnErrorListener { _, _, _ ->
                    finishClip(player, utteranceId)
                    true
                }
                player.start()
            } catch (e: Exception) {
                releasePlayerLocked()
                return false
            }
        }
        return true
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
        const val CLIP_PREFIX = "elevenlabs_"
        const val CLIP_SUFFIX = ".mp3"
    }
}
// END

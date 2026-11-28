package com.opendroid.ai.core.voice

import android.content.Context
import android.media.MediaPlayer
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class TextToSpeechEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val elevenLabsBaseUrl: String = "https://api.elevenlabs.io",
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val edgeTts = EdgeTtsClient(OkHttpClient.Builder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build())
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Bumped by stop(). A speak() started under an older generation must not play
    // a late network clip after cancellation.
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

    /** Toasts are UI work: speech/network coroutines run on Dispatchers.IO. */
    private fun showProblem(message: String) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Tamil-script text and recognized Tanglish use the Tamil voice; English uses Indian English. */
    private fun hasTamil(text: String): Boolean = text.any { it in '\u0B80'..'\u0BFF' }

    /** Only non-Google network voice engines are accepted; old Phone values migrate to Edge. */
    private fun voiceProvider(): String {
        val saved = context.getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE)
            .getString("provider", "edge")
        // Legacy Phone TTS selections are migrated to the non-Google Edge voice path.
        return if (saved == "elevenlabs") "elevenlabs" else "edge"
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
        val localSpeechText = if (!hasTamil(text) && TanglishToTamil.looksTanglish(text)) {
            TanglishToTamil.convert(text)
        } else text
        if (text.isBlank()) {
            mainHandler.post { onCompletionListener?.invoke(utteranceId) }
            return utteranceId
        }
        val speechGeneration = generation.get()
        val provider = voiceProvider()
        scope.launch {
            // Edge clips use a distinct temporary-file prefix, so they do not need
            // to wait for the ElevenLabs cache cleanup before starting synthesis.
            if (provider == "elevenlabs") staleClipCleanup.join()
            val config = if (provider == "elevenlabs") settingsRepository.llmConfig.first() else null
            val apiKey = config?.elevenLabsApiKey.orEmpty()
            val voiceId = config?.elevenLabsVoiceId.orEmpty().ifEmpty { "pNInz6obpgDQGcFmaJgB" }

            if (provider == "edge") {
                try {
                    val success = playEdgeTts(localSpeechText, selectedEdgeVoice(localSpeechText), utteranceId, speechGeneration)
                    if (success) return@launch
                } catch (_: Exception) { }
                if (generation.get() == speechGeneration) {
                    showProblem("Edge Neural unavailable. No Google/system TTS fallback is enabled.")
                    mainHandler.post { onCompletionListener?.invoke(utteranceId) }
                }
                return@launch
            }

            if (provider == "elevenlabs" && apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId, utteranceId, speechGeneration)
                    if (success) return@launch
                    showProblem("Selected ElevenLabs voice could not generate audio. Check its key and Voice ID, or choose Edge Neural.")
                } catch (_: Exception) {
                    showProblem("ElevenLabs unavailable. No Google/system TTS fallback is enabled.")
                }
                if (generation.get() == speechGeneration) {
                    mainHandler.post { onCompletionListener?.invoke(utteranceId) }
                }
                return@launch
            } else if (provider == "elevenlabs") {
                showProblem("ElevenLabs key missing. Choose Edge Neural or add your own key.")
                mainHandler.post { onCompletionListener?.invoke(utteranceId) }
                return@launch
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

    /** Silences speech in progress and drops any ElevenLabs clip still being fetched. */
    fun stop() {
        generation.incrementAndGet()
        inFlightCall?.cancel()
        edgeTts.cancel()
        scope.coroutineContext.cancelChildren()
        synchronized(playerLock) { releasePlayerLocked() }
    }

    fun destroy() {
        generation.incrementAndGet()
        inFlightCall?.cancel()
        edgeTts.cancel()
        scope.cancel()
        synchronized(playerLock) { releasePlayerLocked() }
    }

    private companion object {
        const val CLIP_PREFIX = "elevenlabs_"
        const val CLIP_SUFFIX = ".mp3"
    }
}
// END

package com.opendroid.ai.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Speech to text with Groq Whisper (much better at Tamil / Tanglish than the phone's
 * recogniser), followed by a small correction step that writes the sentence in Tanglish.
 */
class GroqSpeech(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    sealed interface Outcome {
        data class Heard(val text: String) : Outcome
        object NoSpeech : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** Records until the user stops talking, then transcribes. Cancel the coroutine to abort. */
    suspend fun listen(apiKey: String, onRecorded: () -> Unit = {}): Outcome = try {
        val pcm = withContext(Dispatchers.IO) { record() }
        if (pcm == null) {
            Outcome.NoSpeech
        } else {
            onRecorded()
            val raw = withContext(Dispatchers.IO) { transcribe(apiKey, wav(pcm)) }
            if (raw.isBlank()) Outcome.NoSpeech else Outcome.Heard(toTanglish(apiKey, raw))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: "speech error")
    }

    /** Rewrites [raw] in Roman letters and fixes obvious mis-hearings. Returns [raw] on failure. */
    suspend fun toTanglish(apiKey: String, raw: String): String = withContext(Dispatchers.IO) {
        try {
            val system = "You fix speech-to-text output of a Tamil user talking to a phone assistant. " +
                "Rewrite the sentence in Tanglish (Tamil written in English letters). Keep English words " +
                "and app names in normal English spelling (YouTube, WhatsApp, Instagram, CapCut). Fix only " +
                "obvious mis-hearings. Never answer it, translate its meaning, or add words. " +
                "Output only the sentence."
            val body = JSONObject()
                .put("model", TEXT_MODEL)
                .put("temperature", 0)
                .put("max_completion_tokens", 1024)
                .put("reasoning_effort", "low")
                .put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", raw))
                )
            val request = Request.Builder()
                .url("$BASE/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use raw
                JSONObject(response.body.string())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                    .trim().trim('"')
                    .ifBlank { raw }
            }
        } catch (e: Exception) {
            raw
        }
    }

    private fun transcribe(apiKey: String, audio: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", audio.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("language", "ta")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("prompt", "ஜார்விஸ், YouTube open பண்ணு. WhatsApp, Instagram, CapCut, Google, alarm, call.")
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url("$BASE/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Groq speech HTTP ${response.code}")
            return JSONObject(response.body.string()).optString("text").trim()
        }
    }

    /** 16 kHz mono PCM. Null when nobody spoke. The permission is checked before the service starts. */
    @SuppressLint("MissingPermission")
    private suspend fun record(): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME * 8)
        )
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Mic busy")
            recorder.startRecording()
            val frame = ShortArray(FRAME) // 100 ms
            val audio = ByteArrayOutputStream()
            var noise = Double.MAX_VALUE
            var loudFrames = 0
            var silentFrames = 0
            var started = false
            for (index in 0 until MAX_FRAMES) {
                coroutineContext.ensureActive()
                val read = recorder.read(frame, 0, FRAME)
                if (read <= 0) throw IOException("Mic read error")
                var sum = 0.0
                for (i in 0 until read) sum += frame[i].toDouble() * frame[i]
                val level = sqrt(sum / read)
                // Quietest early frame = room noise, even if the user starts talking at once.
                if (index < 5) noise = minOf(noise, level)
                val threshold = (noise * 2.5).coerceIn(350.0, 1800.0)
                val bytes = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) bytes.putShort(frame[i])
                audio.write(bytes.array())
                if (level > threshold) {
                    loudFrames++
                    silentFrames = 0
                    if (loudFrames >= 2) started = true
                } else {
                    silentFrames++
                }
                if (started && silentFrames >= END_SILENCE_FRAMES) break
                if (!started && index >= START_TIMEOUT_FRAMES) return null
            }
            return if (started) audio.toByteArray() else null
        } finally {
            try {
                recorder.stop()
            } catch (e: Exception) {
            }
            recorder.release()
        }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(pcm.size)
        return header.array() + pcm
    }

    private companion object {
        const val BASE = "https://api.groq.com/openai/v1"
        const val TEXT_MODEL = "openai/gpt-oss-20b"
        const val RATE = 16000
        const val FRAME = 1600
        const val MAX_FRAMES = 300          // 30 s
        const val START_TIMEOUT_FRAMES = 60 // 6 s without speech
        const val END_SILENCE_FRAMES = 15   // 1.5 s pause ends the sentence
    }
}

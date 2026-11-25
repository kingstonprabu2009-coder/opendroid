package com.opendroid.ai.core.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real-time voice conversation over the Gemini Live API (WebSocket, per
 * https://ai.google.dev/api/live): 16 kHz PCM goes up while the mic is open, 24 kHz PCM
 * comes back and is played as it arrives. The server stops its reply when the user
 * speaks over it ("interrupted"), and the queued audio is dropped here at once.
 * The phone is put in call mode so the system echo canceller can hear Jarvis's own voice.
 */
class GeminiLiveClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPhoneTask: (String) -> Unit,
    private val onClosed: (String) -> Unit
) {
    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var ready = false
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var socketGen = 0
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private var modelIndex = 0
    private var apiKeyHeld = ""
    private var voiceHeld = "Charon"
    private var gotAudioThisSession = false

    val isRunning: Boolean get() = running

    fun start(apiKey: String, voiceName: String = "Charon") {
        if (running) return
        running = true
        apiKeyHeld = apiKey
        voiceHeld = voiceName
        modelIndex = 0
        gotAudioThisSession = false
        openAudioOutput()
        connect()
    }

    fun stop() {
        if (!running) return
        running = false
        ready = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        micThread?.interrupt()
        micThread = null
        closeAudioOutput()
    }

    private fun connect() {
        val model = MODELS[modelIndex]
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKeyHeld"
        onStatus("Connecting...")
        val gen = ++socketGen
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != socketGen) return
                webSocket.send(setupMessage(model).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) { if (gen == socketGen) handle(text) }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (gen == socketGen) handle(bytes.utf8()) }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running || gen != socketGen) return
                tryNextModelOrClose("Live connection failed: ${response?.code ?: ""} ${t.message ?: ""}".trim())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!running || gen != socketGen) return
                tryNextModelOrClose("Live closed ($code) $reason".trim())
            }
        })
    }

    /** If the first model is refused before any audio was heard, try the next one once. */
    private fun tryNextModelOrClose(reason: String) {
        if (!gotAudioThisSession && modelIndex < MODELS.size - 1) {
            modelIndex++
            ready = false
            micThread?.interrupt()
            micThread = null
            try { ws?.cancel() } catch (_: Exception) {}
            connect()
            return
        }
        running = false
        ready = false
        micThread?.interrupt()
        micThread = null
        closeAudioOutput()
        onClosed(reason)
    }

    private fun setupMessage(model: String): JSONObject {
        val phoneTool = JSONObject()
            .put("name", "run_phone_task")
            .put("description", "Do something on the user's phone: open an app, play a YouTube video, send a WhatsApp message, set an alarm, etc.")
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().put("task", JSONObject()
                    .put("type", "STRING")
                    .put("description", "What to do, in the user's own words.")))
                .put("required", JSONArray().put("task")))
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceHeld)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(phoneTool))))
        return JSONObject().put("setup", setup)
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        if (msg.has("setupComplete")) {
            ready = true
            onStatus("Listening")
            startMic()
            return
        }
        msg.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted", false)) {
                flushOutput()
                onStatus("Listening")
            }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isNotEmpty()) {
                        gotAudioThisSession = true
                        val pcm = Base64.decode(data, Base64.DEFAULT)
                        playQueue.offer(pcm) // played by playThread so this socket thread never blocks
                        onStatus("Speaking")
                    }
                }
            }
            if (sc.optBoolean("turnComplete", false)) onStatus("Listening")
        }
        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                if (c.optString("name") == "run_phone_task") {
                    onPhoneTask(c.optJSONObject("args")?.optString("task").orEmpty())
                }
                responses.put(JSONObject()
                    .put("id", c.optString("id"))
                    .put("name", c.optString("name"))
                    .put("response", JSONObject().put("result", "started")))
            }
            ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
        }
    }

    // ---- audio out: 24 kHz mono PCM16 ----
    private fun openAudioOutput() {
        oldMode = audioManager.mode
        oldSpeaker = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, OUT_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
        playQueue.clear()
        playThread = Thread {
            try {
                while (running && !Thread.currentThread().isInterrupted) {
                    val chunk = playQueue.take()
                    track?.write(chunk, 0, chunk.size)
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
    }

    /** User spoke over Jarvis: drop everything not yet played, at once. */
    private fun flushOutput() {
        playQueue.clear()
        try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
    }

    private fun closeAudioOutput() {
        playThread?.interrupt()
        playThread = null
        playQueue.clear()
        try { track?.stop() } catch (_: Exception) {}
        track?.release()
        track = null
        audioManager.isSpeakerphoneOn = oldSpeaker
        audioManager.mode = oldMode
    }

    // ---- mic: 16 kHz mono PCM16, 40 ms frames ----
    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (micThread != null) return
        micThread = Thread {
            val frameBytes = IN_RATE / 25 * 2
            val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameBytes * 4))
            } catch (e: Exception) { onClosed("Mic error"); return@Thread }
            try {
                if (rec.state != AudioRecord.STATE_INITIALIZED) { onClosed("Mic busy"); return@Thread }
                try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
                try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } } catch (_: Exception) {}
                rec.startRecording()
                val buf = ByteArray(frameBytes)
                while (running && ready && !Thread.currentThread().isInterrupted) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                    ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                        JSONObject().put("data", b64).put("mimeType", "audio/pcm;rate=16000"))).toString())
                }
            } catch (_: Exception) {
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                aec?.release(); ns?.release(); aec = null; ns = null
                rec.release()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private companion object {
        const val IN_RATE = 16000
        const val OUT_RATE = 24000
        // Free-tier preview models listed in Google's Live API docs; the second is the fallback.
        val MODELS = listOf("gemini-2.5-flash-native-audio-preview-12-2025", "gemini-3.1-flash-live-preview")
        const val SYSTEM = "You are Jarvis, a friendly male voice assistant for a Tamil-speaking user in Chennai. " +
            "Talk in natural spoken Tamil mixed with simple English words, the way people in Chennai speak. " +
            "Keep answers short, one or two sentences, and stop when the user speaks. " +
            "When the user asks you to do something on the phone, call run_phone_task, then say briefly that you are doing it."
    }
}

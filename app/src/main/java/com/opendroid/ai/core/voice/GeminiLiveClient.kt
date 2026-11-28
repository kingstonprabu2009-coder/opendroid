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
    private val onTool: (name: String, args: JSONObject, respond: (String) -> Unit) -> Unit,
    private val onClosed: (String) -> Unit,
    private val onStopRequested: () -> Unit = {},
    private val onInputTranscript: (String) -> Unit = {},
    private val onOutputTranscript: (String) -> Unit = {}
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
    @Volatile private var speakingNow = false
    @Volatile private var lastActive = 0L
    private var idleMs = 0L
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false
    private var modelIndex = 0
    private var apiKeyHeld = ""
    private var voiceHeld = "Charon"
    private var memoryHeld = ""
    private var gotAudioThisSession = false

    val isRunning: Boolean get() = running

    fun start(apiKey: String, voiceName: String = "Charon", idleCloseMs: Long = 0L, memory: String = "") {
        if (running) return
        running = true
        memoryHeld = memory
        idleMs = idleCloseMs
        lastActive = System.currentTimeMillis()
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

    /** Ends the call from inside (idle timeout) and tells the service. */
    private fun closeWith(reason: String) {
        if (!running) return
        running = false
        ready = false
        try { ws?.close(1000, "idle") } catch (_: Exception) {}
        ws = null
        closeAudioOutput()
        onClosed(reason)
    }

    private fun setupMessage(model: String): JSONObject {
        fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props)
                .put("required", JSONArray().also { a -> required.forEach { a.put(it) } }))
        val phoneTool = tool("run_phone_task",
            "Do anything else on the user's phone (send a WhatsApp message, set an alarm, etc.). Result is not known immediately.",
            JSONObject().put("task", prop("STRING", "What to do, in the user's own words.")), listOf("task"))
        val youtubeTool = tool("play_youtube",
            "Search YouTube, play the video the user named, and optionally subscribe to the channel, like the video and post a comment. Returns SUCCESS or FAILED.",
            JSONObject()
                .put("query", prop("STRING", "Only the song or video name to search and play."))
                .put("subscribe", prop("BOOLEAN", "true to subscribe to the channel."))
                .put("like", prop("BOOLEAN", "true to like the video."))
                .put("comment", prop("STRING", "Comment text to post, if the user asked to comment.")),
            listOf("query"))
        val openAppTool = tool("open_app", "Open an installed app by name. Returns SUCCESS or FAILED.",
            JSONObject().put("app_name", prop("STRING", "App name, e.g. WhatsApp.")), listOf("app_name"))
        val readScreenTool = tool("read_screen", "Read visible labels and their pixel bounds on the phone screen. Use the returned x/y/w/h bounds to target controls; use it before and after actions.", JSONObject(), emptyList())
        val tapTool = tool("tap_text", "Tap the button or item on screen whose label contains this text. Returns SUCCESS or FAILED.",
            JSONObject().put("label", prop("STRING", "Text or description of the control to tap.")), listOf("label"))
        val typeTool = tool("type_text", "Type text into the text field on screen. Returns SUCCESS or FAILED.",
            JSONObject().put("text", prop("STRING", "Text to type.")), listOf("text"))
        val scrollTool = tool("scroll_screen", "Scroll the screen down or up.",
            JSONObject().put("direction", prop("STRING", "down or up")), listOf("direction"))
        val swipeTool = tool("swipe_screen", "Swipe or drag on the current app screen. For CapCut, use this to drag timeline clips or trim handles. Coordinates are pixels from the top-left; read the screen first and never guess coordinates when the UI is unclear.",
            JSONObject()
                .put("start_x", prop("NUMBER", "Starting horizontal screen coordinate in pixels."))
                .put("start_y", prop("NUMBER", "Starting vertical screen coordinate in pixels."))
                .put("end_x", prop("NUMBER", "Ending horizontal screen coordinate in pixels."))
                .put("end_y", prop("NUMBER", "Ending vertical screen coordinate in pixels."))
                .put("duration_ms", prop("INTEGER", "Gesture duration from 80 to 1500 milliseconds; default 350.")),
            listOf("start_x", "start_y", "end_x", "end_y"))
        val tapScreenTool = tool("tap_screen", "Tap a precise screen coordinate. Use only coordinates observed from the latest screen screenshot/read; coordinates are pixels from top-left.",
            JSONObject()
                .put("x", prop("NUMBER", "Horizontal screen coordinate in pixels."))
                .put("y", prop("NUMBER", "Vertical screen coordinate in pixels.")),
            listOf("x", "y"))
        val batchItemProperties = JSONObject()
            .put("action", prop("STRING", "One of tap_text, tap_screen, type_text, scroll_screen, swipe_screen."))
            .put("label", prop("STRING", "Visible label for tap_text."))
            .put("text", prop("STRING", "Text for type_text."))
            .put("direction", prop("STRING", "up or down for scroll_screen."))
            .put("x", prop("NUMBER", "Tap x coordinate in pixels."))
            .put("y", prop("NUMBER", "Tap y coordinate in pixels."))
            .put("start_x", prop("NUMBER", "Swipe start x coordinate."))
            .put("start_y", prop("NUMBER", "Swipe start y coordinate."))
            .put("end_x", prop("NUMBER", "Swipe end x coordinate."))
            .put("end_y", prop("NUMBER", "Swipe end y coordinate."))
            .put("duration_ms", prop("INTEGER", "Swipe duration in milliseconds."))
            .put("expect_text", prop("STRING", "Optional text that must appear after this action; aborts the batch if absent."))
        val batchItem = JSONObject().put("type", "OBJECT").put("properties", batchItemProperties)
            .put("required", JSONArray().put("action"))
        val batchTool = tool("batch_screen_actions", "Execute 1 to 6 ordered screen actions in one call to reduce voice round trips. Only use actions based on the latest observed screen. Each action is checked; optional expect_text aborts if the expected screen text is absent. Stops at first failure.",
            JSONObject().put("actions", JSONObject().put("type", "ARRAY").put("items", batchItem)), listOf("actions"))
        val navTool = tool("press_key", "Press a phone key: back or home.",
            JSONObject().put("key", prop("STRING", "back or home")), listOf("key"))
        val rememberTool = tool("remember", "Save a short lesson or fact to long-term memory so next time you do it right. Use when the user teaches or corrects you, or you find a way that works.",
            JSONObject().put("note", prop("STRING", "One short sentence.")), listOf("note"))
        val stopTool = JSONObject()
            .put("name", "stop_jarvis")
            .put("description", "Switch Jarvis itself completely off. Call this ONLY when the user clearly asks to turn Jarvis off (stop Jarvis, Jarvis stop, turn off, switch off, nillu Jarvis, nirutthu). Never call it for stop talking, wait, or cancel.")
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                // No long "thinking" before answering: it is what makes replies late.
                .also { if (model.contains("2.5")) it.put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) }
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceHeld)))))
            // Transcription is opt-in in Gemini Live setup; without these empty configs
            // the server sends audio but no input/output subtitle events.
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM + memoryHeld))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(phoneTool).put(youtubeTool).put(openAppTool).put(readScreenTool).put(tapTool).put(tapScreenTool).put(typeTool).put(scrollTool).put(swipeTool).put(batchTool).put(navTool).put(rememberTool).put(stopTool))))
        // Reply fast: treat ~0.6 s of silence as the end of the user's turn.
        setup.put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
            .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            .put("prefixPaddingMs", 100)
            .put("silenceDurationMs", 350)))
        return JSONObject().put("setup", setup)
    }

    private fun handle(text: String) {
        val msg = try { JSONObject(text) } catch (_: Exception) { return }
        if (msg.has("setupComplete")) {
            ready = true
            lastActive = System.currentTimeMillis()
            onStatus("Listening - Gemini Live (" + MODELS[modelIndex].substringBefore("-preview") + ")")
            startMic()
            return
        }
        msg.optJSONObject("serverContent")?.let { sc ->
            // Gemini Live sends transcription separately from the audio chunks.
            // Forward both directions so the Live screen can show real subtitles.
            sc.optJSONObject("inputTranscription")?.optString("text")
                ?.takeIf { it.isNotBlank() }?.let(onInputTranscript)
            sc.optJSONObject("outputTranscription")?.optString("text")
                ?.takeIf { it.isNotBlank() }?.let(onOutputTranscript)
            if (sc.optBoolean("interrupted", false)) {
                flushOutput()
                speakingNow = false
                onStatus("Listening")
            }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    val data = inline.optString("data")
                    if (data.isNotEmpty()) {
                        gotAudioThisSession = true
                        lastActive = System.currentTimeMillis()
                        val pcm = Base64.decode(data, Base64.DEFAULT)
                        playQueue.offer(pcm) // played by playThread so this socket thread never blocks
                        if (!speakingNow) { speakingNow = true; onStatus("Speaking") }
                    }
                }
            }
        }
        msg.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            lastActive = System.currentTimeMillis()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val id = c.optString("id")
                val name = c.optString("name")
                if (name == "stop_jarvis") {
                    sendToolResult(id, name, "Jarvis is switching off now.")
                    onStopRequested()
                    continue
                }
                // The real outcome goes back to Gemini, so it only says "done" when it was done.
                onTool(name, c.optJSONObject("args") ?: JSONObject()) { result ->
                    lastActive = System.currentTimeMillis()
                    sendToolResult(id, name, result)
                }
            }
        }
    }

    private fun sendToolResult(id: String, name: String, result: String) {
        val one = JSONObject().put("id", id).put("name", name).put("response", JSONObject().put("result", result))
        ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(one))).toString())
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
                    val chunk = playQueue.poll(300, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        // Queue ran dry: Jarvis has really finished talking.
                        if (speakingNow) { speakingNow = false; onStatus("Listening") }
                        continue
                    }
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
            val frameBytes = IN_RATE / 50 * 2 // 20 ms frames: less delay
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
                    // Battery saver: nobody has talked for a while, so hang up the live call.
                    if (idleMs > 0 && !speakingNow && System.currentTimeMillis() - lastActive > idleMs) {
                        closeWith("Idle, call ended to save battery")
                        break
                    }
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
            "For YouTube use play_youtube, for opening apps use open_app, for other phone jobs use run_phone_task. " +
            "NEVER say a phone task is done unless the tool result starts with SUCCESS. If it says FAILED, tell the user plainly what failed. " +
            "For CapCut editing, use the existing CapCut app only. Read the latest screen layout before choosing controls; it includes visible labels and pixel bounds. Use those observed bounds for taps/drags. Use batch_screen_actions for short ordered sequences (up to 6 actions) to reduce latency; use tap_text for labelled controls and tap_screen/swipe_screen only with coordinates observed from the latest screenshot. Use expect_text when the next screen state is predictable. Stop on any failed action, re-read the screen, and never invent controls, coordinates, export completion, or claim an edit succeeded without verification. Never switch to a newly built editor app. " +
            "When the user teaches or corrects you, call remember with one short sentence. "
    }
}

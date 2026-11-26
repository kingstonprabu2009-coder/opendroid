package com.opendroid.ai.core.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.PlanApprovalSummary
import com.opendroid.ai.core.voice.ApprovalListenGate
import com.opendroid.ai.core.voice.GroqSpeech
import com.opendroid.ai.core.voice.VoiceSubtitles
import com.opendroid.ai.core.voice.SpeechRecognitionEngine
import com.opendroid.ai.core.voice.TextToSpeechEngine
import com.opendroid.ai.core.voice.VoiceApprovalIntent
import com.opendroid.ai.core.voice.PendingApprovalListen
import com.opendroid.ai.core.voice.VoiceApprovalParser
import com.opendroid.ai.core.voice.WakeWordDetector
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class OpenDroidService : Service() {

    @Inject
    lateinit var agentLoop: AgentLoop

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var actionDispatcher: com.opendroid.ai.actions.ActionDispatcher

    @Inject
    lateinit var mcpServer: McpServer

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var speechRecognitionEngine: SpeechRecognitionEngine
    private lateinit var textToSpeechEngine: TextToSpeechEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingApprovalListen = PendingApprovalListen()
    private val approvalListenGate = ApprovalListenGate()
    private var foregroundStarted = false
    private val groqSpeech = GroqSpeech()
    private var listenJob: Job? = null
    private var wakeResumeJob: Job? = null
    private var greetingDone: Pair<String, CompletableDeferred<Unit>>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_TRIGGER_RECORD = "com.opendroid.ai.action.TRIGGER_RECORD"
        const val ACTION_WAKE = "com.opendroid.ai.action.WAKE"
        const val ACTION_CANCEL_LISTEN = "com.opendroid.ai.action.CANCEL_LISTEN"
        const val ACTION_HANGUP = "com.opendroid.ai.action.HANGUP"
        private const val CHANNEL_ID = "opendroid_channel"
        private const val NOTIFICATION_ID = 2024
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private const val PREFS = "jarvis_service_prefs"
        private const val KEY_WANTED = "service_wanted"

        private fun setWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_WANTED, wanted) }
        }

        fun start(context: Context) {
            setWanted(context, true)
            val intent = Intent(context, OpenDroidService::class.java)
            context.startForegroundService(intent)
        }

        /** Switches Jarvis ON and wakes it at once, as if "Jarvis" was heard by the service. */
        fun startAndWake(context: Context) {
            setWanted(context, true)
            val intent = Intent(context, OpenDroidService::class.java).apply {
                action = ACTION_WAKE
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            setWanted(context, false)
            val intent = Intent(context, OpenDroidService::class.java)
            context.stopService(intent)
        }

        /**
         * Called when the app opens: if the user left Jarvis switched on, bring the wake
         * word back without pressing START again (Android blocks starting the microphone
         * from the background, e.g. right after a phone restart).
         */
        fun resumeIfWanted(context: Context) {
            // Manual start only: Jarvis never switches itself on when the app opens.
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()

        // Start the foreground notification before initializing microphone services.
        // If Android refuses the start, stop cleanly rather than crashing the process.
        createNotificationChannel()
        foregroundStarted = startForegroundCompat()
        if (!foregroundStarted) {
            stopSelf()
            return
        }
        _isRunning.value = true

        // Keep the CPU awake so the wake word keeps listening with the screen off.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:WakeWord").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            wakeLock = null
        }

        // Initialize engines
        wakeWordDetector = WakeWordDetector(this)
        speechRecognitionEngine = SpeechRecognitionEngine(this)
        textToSpeechEngine = TextToSpeechEngine(this, settingsRepository)

        // Bind Agent Loop TTS: speak only when the user spoke by voice
        agentLoop.onSpeakCallback = { text ->
            if (agentLoop.speakReplies) {
                VoiceSubtitles.reply.value = text
                textToSpeechEngine.speak(text)
                startBargeWatch()
            } else if (agentLoop.agentState.value is AgentState.Speaking) {
                agentLoop.setAgentState(AgentState.Idle)
            }
        }
        // Stop and Clear cancel the task; speech already playing or still being fetched
        // must stop with it.
        agentLoop.onStopSpeakingCallback = {
            approvalListenGate.disarm()
            VoiceSubtitles.liveSpeaking.value = false
            textToSpeechEngine.stop()
        }

        // Set TTS completion listener to transition back to Idle
        textToSpeechEngine.onStartListener = {
            VoiceSubtitles.liveSpeaking.value = true
        }
        textToSpeechEngine.onCompletionListener = { utteranceId ->
            VoiceSubtitles.liveSpeaking.value = false
            // Only Speaking resets to Idle - speech that happens to play while a
            // plan sits in PlanProposed must not knock the approval gate over.
            bargeJob?.cancel()
            if (agentLoop.agentState.value is AgentState.Speaking) {
                agentLoop.setAgentState(AgentState.Idle)
            }
            greetingDone?.let { (id, done) -> if (id == utteranceId) done.complete(Unit) }
            if (approvalListenGate.onUtteranceDone(utteranceId)) {
                pendingApprovalListen.take()?.let { planId -> startListeningForApproval(planId) }
            }
        }

        mcpServer.start()

        // Wake-word listening stays active whenever the Jarvis service is running.
        startWakeWordDetection()

        // Hands-free plan approval (upstream issue 18 spec, voice section):
        // speak the prompt once per proposal; auto-listen for the reply only in
        // wake-word mode, where the user is known to be voice-driven. Keyed on the
        // whole plan: a re-evaluation can propose the same planId again mid-run,
        // and that second proposal must not wait in silence.
        serviceScope.launch {
            var promptedPlan: Plan? = null
            agentLoop.agentState.collectLatest { state ->
                pendingApprovalListen.onAgentState(state)
                if (state is AgentState.PlanProposed && state.plan != promptedPlan) {
                    promptedPlan = state.plan
                    // Armed with the prompt's own id before it is queued, so only the
                    // prompt finishing opens the mic, never an earlier utterance.
                    val promptId = UUID.randomUUID().toString()
                    if (agentLoop.speakReplies) {
                        pendingApprovalListen.arm(state.plan.planId)
                        approvalListenGate.armFor(promptId)
                    } else {
                        approvalListenGate.disarm()
                    }
                    if (agentLoop.speakReplies) {
                        textToSpeechEngine.speak(PlanApprovalSummary.spokenPrompt(state.plan), utteranceId = promptId)
                    }
                }
            }
        }
    }

    private fun startForegroundCompat(): Boolean {
        val notification = createNotification()
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val types = ForegroundStart.typesFor(android.os.Build.VERSION.SDK_INT, micGranted)
        return ForegroundStart.startFirstAllowed(types) { type ->
            if (type == null) {
                startForeground(NOTIFICATION_ID, notification)
            } else {
                androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            }
        }
    }

    /**
     * Cleans what the speech recogniser heard: Tamil-script spellings of common app names
     * become the real names, and a leading "Jarvis" (the wake word) is dropped.
     */
    private fun correctTranscript(raw: String): String {
        var s = raw.trim()
        val fixes = listOf(
            Regex("யூ\\s?டியூப்|யூ\\s?ட்யூப்|யுடியூப்|யூடியுப்|you tube", RegexOption.IGNORE_CASE) to "YouTube",
            Regex("வாட்ஸ்\\s?அப்|வாட்சப்|வாட்ஸப்|whats app", RegexOption.IGNORE_CASE) to "WhatsApp",
            Regex("இன்ஸ்டாகிராம்|இன்ஸ்டா") to "Instagram",
            Regex("கூகுள் குரோம்|குரோம்") to "Chrome",
            Regex("டெலிகிராம்") to "Telegram",
            Regex("ஃபேஸ்புக்|பேஸ்புக்") to "Facebook",
            Regex("^(hey |ok |ஹே |ஓகே )?(jarvis|ஜார்விஸ்|ஜார்விஸ|ஜார்வீஸ்)[,\\s]+", RegexOption.IGNORE_CASE) to ""
        )
        for ((pattern, replacement) in fixes) {
            s = pattern.replace(s, replacement)
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.isEmpty()) raw.trim() else s
    }

    /** Stops any reply that is being spoken so two voices never overlap. */
    private fun stopCurrentSpeech() {
        bargeJob?.cancel()
        approvalListenGate.disarm()
        VoiceSubtitles.liveSpeaking.value = false
        textToSpeechEngine.stop()
        if (agentLoop.agentState.value is AgentState.Speaking) {
            agentLoop.setAgentState(AgentState.Idle)
        }
    }

    private fun startWakeWordDetection() {
        wakeWordDetector.startListening { onWakeWordHeard() }
    }

    // True while a live conversation is open: after each reply Jarvis listens again by itself,
    // so "Jarvis" only has to be said once. Silence, "bye" or hang up closes it.
    @Volatile private var conversationActive = false
    private val bargeIn = com.opendroid.ai.core.voice.BargeInDetector()
    private var bargeJob: kotlinx.coroutines.Job? = null
    private var pendingPrefix: ByteArray? = null

    /** While Jarvis speaks in a live call, watch for the user talking over it and cut in. */
    private fun startBargeWatch() {
        val on = getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getBoolean("barge_in", true)
        if (!on || !conversationActive) return
        bargeJob?.cancel()
        bargeJob = serviceScope.launch {
            delay(1200) // let Jarvis's voice start so its own sound is not taken for the user
            val pcm = bargeIn.waitForSpeech()
            if (pcm != null && conversationActive) {
                pendingPrefix = pcm
                startListeningForQuery()
            }
        }
    }
    private var silentRounds = 0

    private var live: com.opendroid.ai.core.voice.GeminiLiveClient? = null

    /** Real-time streaming call with Gemini Live. Returns false if it cannot start (no key, switched off). */
    private fun tryStartLive(): Boolean {
        val enabled = getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getBoolean("live_gemini", true)
        if (!enabled || live?.isRunning == true) return enabled && live?.isRunning == true
        serviceScope.launch {
            val key = settingsRepository.llmConfig.first().apiKeys["Google Gemini"].orEmpty()
            if (key.isBlank()) {
                // No Gemini key: use the older Groq pipeline instead.
                VoiceSubtitles.reply.value = "Old mode (no Gemini key set)"
                conversationActive = true
                startListeningForQuery()
                return@launch
            }
            conversationActive = true
            agentLoop.speakReplies = false // the live voice speaks; the agent must stay silent
            VoiceSubtitles.clear()
            agentLoop.setAgentState(AgentState.Listening)
            live = com.opendroid.ai.core.voice.GeminiLiveClient(
                context = this@OpenDroidService,
                onStatus = { st -> serviceScope.launch {
                        VoiceSubtitles.reply.value = st
                        VoiceSubtitles.liveSpeaking.value = st == "Speaking"
                    } },
                onTool = { name, args, respond ->
                    serviceScope.launch {
                        val result = try { runLiveTool(name, args) } catch (e: Exception) { "FAILED: " + (e.message ?: "error") }
                        respond(result)
                    }
                },
                onStopRequested = { serviceScope.launch { endLive(); handleBye() } },
                onInputTranscript = { text ->
                    serviceScope.launch {
                        VoiceSubtitles.heard.value = text
                        VoiceSubtitles.reply.value = ""
                    }
                },
                onOutputTranscript = { text ->
                    serviceScope.launch { VoiceSubtitles.reply.value = text }
                },
                onClosed = { reason ->
                    serviceScope.launch {
                        VoiceSubtitles.reply.value = reason
                        endLive()
                        startWakeWordDetection()
                    }
                }
            ).also {
                val saver = getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getBoolean("battery_saver", true)
                val notes = getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE).getString("notes", "").orEmpty()
                val memory = if (notes.isBlank()) "" else "\nThings you have learned from the user, follow them:\n" + notes
                it.start(key, idleCloseMs = if (saver) 40_000L else 0L, memory = memory)
            }
        }
        return true
    }

    private fun describeResult(r: com.opendroid.ai.actions.base.ActionResult): String =
        if (r.success) "SUCCESS: " + (r.data ?: "done") else "FAILED: " + (r.error ?: "unknown error")

    /** Runs a phone job asked for by the live voice and returns its real outcome. */
    private suspend fun runLiveTool(name: String, args: org.json.JSONObject): String = when (name) {
        "play_youtube" -> {
            val p = mutableMapOf("query" to args.optString("query"))
            if (args.optBoolean("subscribe", false)) p["subscribe"] = "true"
            if (args.optBoolean("like", false)) p["like"] = "true"
            val c = args.optString("comment")
            if (c.isNotBlank()) p["comment"] = c
            describeResult(actionDispatcher.execute("PLAY_YOUTUBE", p, this))
        }
        "open_app" -> describeResult(
            actionDispatcher.execute("OPEN_APP", mapOf("appName" to args.optString("app_name")), this)
        )
        "read_screen" -> {
            val svc = com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()
            if (svc == null) "FAILED: Accessibility is off" else svc.getScreenText().take(1800).ifBlank { "Screen has no readable text" }
        }
        "tap_text" -> {
            val svc = com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()
            val ok = svc?.tapByText(args.optString("label")) == true
            delay(800)
            if (svc == null) "FAILED: Accessibility is off" else if (ok) "SUCCESS: tapped" else "FAILED: no control with that text is visible"
        }
        "type_text" -> {
            val svc = com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()
            val ok = svc?.typeIntoFocused(args.optString("text")) == true
            if (svc == null) "FAILED: Accessibility is off" else if (ok) "SUCCESS: typed" else "FAILED: no text field found"
        }
        "scroll_screen" -> {
            val svc = com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()
            val ok = svc?.performScroll(args.optString("direction").lowercase() != "up") == true
            delay(600)
            if (svc == null) "FAILED: Accessibility is off" else if (ok) "SUCCESS: scrolled" else "FAILED: nothing to scroll"
        }
        "press_key" -> {
            val svc = com.opendroid.ai.accessibility.OpenDroidAccessibilityService.getInstance()
            val back = args.optString("key").lowercase() == "back"
            val ok = svc?.let { if (back) it.goBack() else it.goHome() } == true
            delay(600)
            if (svc == null) "FAILED: Accessibility is off" else if (ok) "SUCCESS" else "FAILED"
        }
        "remember" -> {
            val note = args.optString("note").trim().take(200)
            if (note.isBlank()) "FAILED: empty note" else {
                val prefs = getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)
                val lines = prefs.getString("notes", "").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
                if (note !in lines) lines.add(note)
                prefs.edit().putString("notes", lines.takeLast(40).joinToString("\n")).apply()
                "SUCCESS: remembered"
            }
        }
        "run_phone_task" -> {
            val t = args.optString("task")
            if (t.isBlank()) "FAILED: no task given" else {
                agentLoop.processQuery(t, this)
                "STARTED: handed to the phone agent, the outcome is not known yet. Do not say it is done."
            }
        }
        else -> "FAILED: unknown tool"
    }

    private fun endLive() {
        VoiceSubtitles.liveSpeaking.value = false
        live?.stop()
        live = null
        conversationActive = false
        if (agentLoop.agentState.value is AgentState.Listening || agentLoop.agentState.value is AgentState.Speaking) {
            agentLoop.setAgentState(AgentState.Idle)
        }
    }

    private fun onWakeWordHeard() {
        cancelListening()
        stopCurrentSpeech()
        if (tryStartLive()) return
        conversationActive = true
        silentRounds = 0
        cancelListening()
        stopCurrentSpeech()
        agentLoop.speakReplies = true
        VoiceSubtitles.clear()
        VoiceSubtitles.reply.value = "Sollunga sir"
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        greetingDone = id to done
        textToSpeechEngine.speak("Sollunga sir", utteranceId = id)
        // Open the mic only after the greeting has finished, so it is never cut off.
        listenJob = serviceScope.launch {
            withTimeoutOrNull(5000) { done.await() }
            startListeningForQuery()
        }
    }

    /** After a plain spoken reply (no agent task), keep the live conversation going. */
    private fun resumeAfterSpeakOnly() {
        wakeResumeJob?.cancel()
        wakeResumeJob = serviceScope.launch {
            delay(3500)
            if (conversationActive) startListeningForQuery() else startWakeWordDetection()
        }
    }

    /** Stops every pending microphone session. */
    private fun cancelListening() {
        bargeJob?.cancel()
        listenJob?.cancel()
        wakeResumeJob?.cancel()
        wakeWordDetector.stopListening()
        speechRecognitionEngine.cancel()
    }

    /**
     * After a query is sent, wait until Jarvis has finished thinking and speaking
     * (agent state Idle, max 60 s) before the wake word mic opens again. This stops
     * the wake listener from fighting the reply voice.
     */
    private fun resumeWakeWhenIdle() {
        // Kept name; now continues the live conversation when one is open.
        wakeResumeJob?.cancel()
        wakeResumeJob = serviceScope.launch {
            delay(1500)
            var waited = 0
            while (waited < 60000 && agentLoop.agentState.value !is AgentState.Idle &&
                agentLoop.agentState.value !is AgentState.Error
            ) {
                delay(500)
                waited += 500
            }
            val state = agentLoop.agentState.value
            if (state is AgentState.Error) {
                VoiceSubtitles.reply.value = state.message
                textToSpeechEngine.speak("Mannikkanum sir, oru error vandhuruku.")
                delay(3500)
            }
            delay(500)
            if (conversationActive) startListeningForQuery() else startWakeWordDetection()
        }
    }

    /**
     * True when the whole utterance is just a goodbye ("bye", "bye bye", "goodbye",
     * "bye jarvis", "பை"). Exact match only, so a sentence like "say bye to mom"
     * is still sent to the agent as a normal query.
     */
    private fun isByeCommand(query: String): Boolean {
        val cleaned = query
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), "")
            .trim()
        return cleaned in setOf(
            "bye",
            "bye bye",
            "goodbye",
            "good bye",
            "bye jarvis",
            "bye bye jarvis",
            "goodbye jarvis",
            "பை",
            "பை பை",
            "stop jarvis",
            "jarvis stop",
            "turn off",
            "turn off jarvis",
            "switch off",
            "switch off jarvis",
            "shut down",
            "nillu",
            "nirutthu",
            "nirutu",
            "ஸ்டாப்",
            "ஸ்டாப் ஜார்விஸ்",
            "ஜார்விஸ் ஸ்டாப்",
            "நிறுத்து",
            "நில்லு"
        )
    }

    /**
     * User said bye: speak a short goodbye, then stop the whole service. The wake word
     * stops with it and stays off until the service is started again.
     */
    private fun handleBye() {
        conversationActive = false
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_WANTED, false) }
        wakeWordDetector.stopListening()
        agentLoop.setAgentState(AgentState.Idle)
        textToSpeechEngine.speak("பை சார்")
        serviceScope.launch {
            delay(1500)
            stopSelf()
        }
    }

    private fun startListeningForQuery() {
        // Temporarily pause wake word to avoid hearing itself
        cancelListening()
        // Tap to speak while Jarvis is talking: cut the old voice first.
        stopCurrentSpeech()

        // Set agent state to Listening
        agentLoop.setAgentState(AgentState.Listening)
        VoiceSubtitles.clear()

        listenJob = serviceScope.launch {
            val key = settingsRepository.llmConfig.first().apiKeys["Groq"].orEmpty()
            delay(250) // give the wake recogniser time to release the mic
            if (key.isBlank()) {
                listenWithPhone(key)
                return@launch
            }
            val prefix = pendingPrefix
            pendingPrefix = null
            val outcome = groqSpeech.listen(key, prefix) {
                serviceScope.launch {
                    if (agentLoop.agentState.value is AgentState.Listening) {
                        agentLoop.setAgentState(AgentState.Thinking)
                    }
                }
            }
            when (outcome) {
                is GroqSpeech.Outcome.Heard -> handleHeard(outcome.text)
                GroqSpeech.Outcome.NoSpeech -> {
                    // Live call: stay on the line through a few quiet rounds, no wake-word restart.
                    silentRounds++
                    if (conversationActive && silentRounds < (if (getSharedPreferences("jarvis_voice", Context.MODE_PRIVATE).getBoolean("battery_saver", true)) 2 else 4)) {
                        startListeningForQuery()
                    } else {
                        conversationActive = false
                        VoiceSubtitles.reply.value = "Onnum kekkala sir"
                        agentLoop.setAgentState(AgentState.Idle)
                        startWakeWordDetection()
                    }
                }
                is GroqSpeech.Outcome.Failed -> {
                    VoiceSubtitles.reply.value = "${outcome.reason}. Marubadi sollunga sir."
                    agentLoop.setAgentState(AgentState.Listening)
                    listenWithPhone(key)
                }
            }
        }
    }

    /** Fallback: the phone's own recogniser (no Groq key, or Groq is unreachable). */
    private fun listenWithPhone(groqKey: String) {
        speechRecognitionEngine.startListening(
            onResult = { heard ->
                listenJob = serviceScope.launch {
                    handleHeard(
                        if (groqKey.isBlank()) heard else groqSpeech.toTanglish(groqKey, heard)
                    )
                }
            },
            onPartialResult = { VoiceSubtitles.heard.value = it },
            onError = { error ->
                conversationActive = false
                VoiceSubtitles.reply.value = error
                agentLoop.setAgentState(AgentState.Idle)
                startWakeWordDetection()
            }
        )
    }

    private fun handleHeard(heard: String) {
        silentRounds = 0
        val query = correctTranscript(heard)
        VoiceSubtitles.heard.value = query
        VoiceSubtitles.reply.value = ""
        val lower = query.lowercase()
        val teachWord = lower.contains("teaching") || lower.contains("teach mode") || query.contains("டீச்சிங்")
        if (teachWord) {
            val off = lower.contains(" off") || lower.contains("stop") || lower.contains("mudi") ||
                lower.contains("நிறுத்து") || lower.contains("ஆஃப்")
            val reply: String
            if (off && com.opendroid.ai.core.teaching.TeachingMode.active) {
                val (name, n) = com.opendroid.ai.core.teaching.TeachingMode.stop(this)
                reply = if (n > 0) "Teaching mode off. $name la $n step kathukkitten sir." else "Teaching mode off sir. Edhuvum kathukala."
            } else if (!off) {
                com.opendroid.ai.core.teaching.TeachingMode.start(
                    this, lower.substringAfter("teaching", "").replace(Regex("\\b(mode|on|pannu)\\b"), "").trim()
                )
                reply = "Teaching mode on sir. Neenga pannunga, naan kathukkiren."
            } else {
                reply = "Teaching mode already off sir."
            }
            agentLoop.speakReplies = true
            VoiceSubtitles.reply.value = reply
            textToSpeechEngine.speak(reply)
            if (conversationActive) resumeAfterSpeakOnly() else startWakeWordDetection()
            return
        }
        if (isByeCommand(query)) {
            handleBye()
        } else {
            agentLoop.speakReplies = true
            val guide = com.opendroid.ai.core.teaching.TeachingMode.guideFor(this, query)
            agentLoop.processQuery(if (guide.isBlank()) query else query + "\n" + guide, this)
            resumeWakeWhenIdle()
        }
    }

    /**
     * One-shot reply capture after the spoken approval prompt. Unrecognized
     * speech (or an error) is a deliberate no-op: the plan stays in
     * PlanProposed with the visual modal still on screen. Never a grant path -
     * nothing spoken may widen the allowlist.
     */
    private fun startListeningForApproval(planId: String) {
        wakeWordDetector.stopListening()
        speechRecognitionEngine.startListening(
            onResult = { utterance ->
                when (VoiceApprovalParser.parse(utterance)) {
                    VoiceApprovalIntent.APPROVE -> agentLoop.approveProposedPlan(this, planId)
                    VoiceApprovalIntent.REJECT -> agentLoop.rejectProposedPlan()
                    VoiceApprovalIntent.NONE -> Unit
                }
                startWakeWordDetection()
            },
            onError = { _ ->
                startWakeWordDetection()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The service is already stopping; a sticky restart would only be refused again.
        if (!foregroundStarted) return START_NOT_STICKY
        if (intent?.action == ACTION_TRIGGER_RECORD) {
            conversationActive = true
            startListeningForQuery()
        }
        if (intent?.action == ACTION_HANGUP) {
            endLive()
            conversationActive = false
            cancelListening()
            stopCurrentSpeech()
            agentLoop.setAgentState(AgentState.Idle)
            // Hang up switches Jarvis fully off: notification gone, same as STOP JARVIS.
            setWanted(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_LISTEN) conversationActive = false
        if (intent?.action == ACTION_CANCEL_LISTEN &&
            agentLoop.agentState.value is AgentState.Listening
        ) {
            cancelListening()
            agentLoop.setAgentState(AgentState.Idle)
            startWakeWordDetection()
        }
        if (intent?.action == ACTION_WAKE) {
            onWakeWordHeard()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        live?.stop()
        serviceScope.cancel()
        if (!foregroundStarted) return
        mcpServer.stop()
        wakeWordDetector.destroy()
        speechRecognitionEngine.destroy()
        textToSpeechEngine.destroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jarvis Agent Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Jarvis background agent alive"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val hangUp = android.app.PendingIntent.getService(
            this, 77,
            Intent(this, OpenDroidService::class.java).setAction(ACTION_HANGUP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangUp)
            .setContentTitle("Jarvis Active")
            .setContentText("Listening for wake word 'Jarvis'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
// END

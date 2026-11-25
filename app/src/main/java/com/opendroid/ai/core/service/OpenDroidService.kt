package com.opendroid.ai.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.agent.PlanApprovalSummary
import com.opendroid.ai.core.voice.ApprovalListenGate
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
import kotlinx.coroutines.Dispatchers
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
    lateinit var mcpServer: McpServer

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var speechRecognitionEngine: SpeechRecognitionEngine
    private lateinit var textToSpeechEngine: TextToSpeechEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingApprovalListen = PendingApprovalListen()
    private val approvalListenGate = ApprovalListenGate()
    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_TRIGGER_RECORD = "com.opendroid.ai.action.TRIGGER_RECORD"
        private const val CHANNEL_ID = "opendroid_channel"
        private const val NOTIFICATION_ID = 2024
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private const val PREFS = "jarvis_service_prefs"
        private const val KEY_WANTED = "service_wanted"

        private fun setWanted(context: Context, wanted: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WANTED, wanted).apply()
        }

        fun start(context: Context) {
            setWanted(context, true)
            val intent = Intent(context, OpenDroidService::class.java)
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
            val wanted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WANTED, false)
            val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (wanted && micGranted && !_isRunning.value) {
                start(context)
            }
        }
    }

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
                textToSpeechEngine.speak(text)
            } else if (agentLoop.agentState.value is AgentState.Speaking) {
                agentLoop.setAgentState(AgentState.Idle)
            }
        }
        // Stop and Clear cancel the task; speech already playing or still being fetched
        // must stop with it.
        agentLoop.onStopSpeakingCallback = {
            approvalListenGate.disarm()
            textToSpeechEngine.stop()
        }

        // Set TTS completion listener to transition back to Idle
        textToSpeechEngine.onCompletionListener = { utteranceId ->
            // Only Speaking resets to Idle - speech that happens to play while a
            // plan sits in PlanProposed must not knock the approval gate over.
            if (agentLoop.agentState.value is AgentState.Speaking) {
                agentLoop.setAgentState(AgentState.Idle)
            }
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
        approvalListenGate.disarm()
        textToSpeechEngine.stop()
        if (agentLoop.agentState.value is AgentState.Speaking) {
            agentLoop.setAgentState(AgentState.Idle)
        }
    }

    private fun startWakeWordDetection() {
        wakeWordDetector.startListening { onWakeWordHeard() }
    }

    private fun onWakeWordHeard() {
        wakeWordDetector.stopListening()
        stopCurrentSpeech()
        agentLoop.speakReplies = true
        textToSpeechEngine.speak("சொல்லுங்க சார்")
        serviceScope.launch {
            delay(1100)
            startListeningForQuery()
        }
    }

    /**
     * After a query is sent, wait until Jarvis has finished thinking and speaking
     * (agent state Idle, max 60 s) before the wake word mic opens again. This stops
     * the wake listener from fighting the reply voice.
     */
    private fun resumeWakeWhenIdle() {
        serviceScope.launch {
            delay(1500)
            var waited = 0
            while (waited < 60000 && agentLoop.agentState.value !is AgentState.Idle) {
                delay(500)
                waited += 500
            }
            delay(500)
            startWakeWordDetection()
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
            "பை பை"
        )
    }

    /**
     * User said bye: speak a short goodbye, then stop the whole service. The wake word
     * stops with it and stays off until the service is started again.
     */
    private fun handleBye() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WANTED, false).apply()
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
        wakeWordDetector.stopListening()
        // Tap to speak while Jarvis is talking: cut the old voice first.
        stopCurrentSpeech()

        // Set agent state to Listening
        agentLoop.setAgentState(AgentState.Listening)

        // Start speech recognizer for query input
        speechRecognitionEngine.startListening(
            onResult = { heard ->
                val query = correctTranscript(heard)
                if (isByeCommand(query)) {
                    handleBye()
                } else {
                    agentLoop.speakReplies = true
                    agentLoop.processQuery(query, this)
                    resumeWakeWhenIdle()
                }
            },
            onError = { _ ->
                agentLoop.setAgentState(AgentState.Idle)
                startWakeWordDetection()
            }
        )
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
            startListeningForQuery()
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Active")
            .setContentText("Listening for wake word 'Jarvis'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
// END

package com.opendroid.ai.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opendroid.ai.R
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.core.voice.VoiceSubtitles
import com.opendroid.ai.core.voice.WakeWordDetector
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentPurple
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.ChatViewModel
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

private enum class JarvisMode { LIVE_VOICE, CHAT, TEACH, MEMORY }

private fun speakingState(state: AgentState): Boolean = state is AgentState.Speaking

@SuppressLint("BatteryLife")
@Composable
fun JarvisHomeScreen(
    settingsViewModel: SettingsViewModel,
    onNavigateToBenchmark: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToTermsOfUse: () -> Unit,
    onNavigateToHelpCenter: () -> Unit,
    onNavigateToLicense: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToAutoReply: () -> Unit,
    onNavigateToNotificationHistory: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToCrashLog: () -> Unit,
    onNavigateToRoutines: () -> Unit
) {
    val context = LocalContext.current
    val chatViewModel: ChatViewModel = hiltViewModel()
    val memoryViewModel: MemoryViewModel = hiltViewModel()
    val agentState by chatViewModel.agentState.collectAsState()
    var mode by remember { mutableStateOf(JarvisMode.LIVE_VOICE) }
    var showSettings by remember { mutableStateOf(false) }
    val serviceOn by OpenDroidService.isRunning.collectAsState()

    // Jarvis stays switched on after you leave the app; reopening brings it back.
    LaunchedEffect(Unit) {
        OpenDroidService.resumeIfWanted(context)
    }

    // Without this, Android may put the app to sleep and the wake word stops hearing
    // "Jarvis" once the app is closed or the screen is off.
    fun askBatteryExemption() {
        try {
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = ("package:" + context.packageName).toUri()
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // Some phones do not offer this screen; wake word still works while the app is open.
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            OpenDroidService.start(context)
            askBatteryExemption()
        }
    }

    fun startService() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            OpenDroidService.start(context)
            askBatteryExemption()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun triggerVoiceInput() {
        val intent = Intent(context, OpenDroidService::class.java).apply {
            action = OpenDroidService.ACTION_TRIGGER_RECORD
        }
        ContextCompat.startForegroundService(context, intent)
    }

    // Jarvis OFF but the app is open on the Live Voice screen: saying "Jarvis" switches
    // Jarvis ON by itself (no START press) and it starts listening.
    val lifecycleOwner = LocalLifecycleOwner.current
    var appResumed by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appResumed = true
            if (event == Lifecycle.Event.ON_PAUSE) appResumed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val inAppWakeOn = !serviceOn && !showSettings && mode == JarvisMode.LIVE_VOICE && appResumed
    DisposableEffect(inAppWakeOn) {
        var detector: WakeWordDetector? = null
        val micOk = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (inAppWakeOn && micOk) {
            val d = WakeWordDetector(context)
            detector = d
            d.startListening {
                d.stopListening()
                OpenDroidService.startAndWake(context)
            }
        }
        onDispose { detector?.destroy() }
    }

    Scaffold(
        containerColor = AppTheme.colors.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.background)
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (showSettings) {
                    IconButton(onClick = { showSettings = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                } else {
                    Text(
                        text = "JARVIS",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                }

                if (!showSettings) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                }
            }

            if (!showSettings) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.TabRow(
                    selectedTabIndex = mode.ordinal,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = AccentCyan
                ) {
                    listOf(
                        JarvisMode.LIVE_VOICE to "Live",
                        JarvisMode.CHAT to "Chat"
                    ).forEach { (m, label) ->
                        androidx.compose.material3.Tab(
                            selected = mode == m,
                            onClick = {
                                mode = m
                                if (m == JarvisMode.CHAT && serviceOn) {
                                    // Chat is typed only: close the mic if it was listening.
                                    context.startService(
                                        Intent(context, OpenDroidService::class.java)
                                            .setAction(OpenDroidService.ACTION_CANCEL_LISTEN)
                                    )
                                }
                            },
                            text = { Text(label, maxLines = 1, fontSize = 13.sp) },
                            selectedContentColor = AccentCyan,
                            unselectedContentColor = TextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                showSettings -> SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateToBenchmark = onNavigateToBenchmark,
                    onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                    onNavigateToTermsOfUse = onNavigateToTermsOfUse,
                    onNavigateToHelpCenter = onNavigateToHelpCenter,
                    onNavigateToLicense = onNavigateToLicense,
                    onNavigateToAbout = onNavigateToAbout,
                    onNavigateToAutoReply = onNavigateToAutoReply,
                    onNavigateToNotificationHistory = onNavigateToNotificationHistory,
                    onNavigateToPermissions = onNavigateToPermissions,
                    onNavigateToCrashLog = onNavigateToCrashLog,
                    onNavigateToRoutines = onNavigateToRoutines,
                    modifier = Modifier.weight(1f)
                )

                mode == JarvisMode.TEACH -> TeachTab(modifier = Modifier.weight(1f))

                mode == JarvisMode.MEMORY -> MemoryScreen(
                    viewModel = memoryViewModel,
                    modifier = Modifier.weight(1f)
                )

                mode == JarvisMode.CHAT -> ChatScreen(
                    viewModel = chatViewModel,
                    modifier = Modifier.weight(1f),
                    textOnly = true
                )

                else -> {
                    val liveSpeaking by VoiceSubtitles.liveSpeaking.collectAsState()
                    // Live voice can speak while AgentState remains Listening; audio state wins.
                    val speaking = agentState is AgentState.Speaking || liveSpeaking
                    val active = agentState is AgentState.Listening ||
                        agentState is AgentState.Thinking ||
                        agentState is AgentState.Speaking || speaking
                    val transition = rememberInfiniteTransition(label = "jarvisOrb")
                    // Clearly visible breathing in every mode; stronger/faster while active.
                    val pulse by transition.animateFloat(
                        initialValue = 1f,
                        targetValue = when {
                            speaking -> 1.075f
                            active -> 1.06f
                            else -> 1.025f
                        },
                        animationSpec = infiniteRepeatable(
                            animation = tween(
                                durationMillis = when {
                                    speaking -> 420
                                    active -> 760
                                    else -> 1800
                                },
                                easing = LinearEasing
                            ),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "orbPulse"
                    )
                    val shake by transition.animateFloat(
                        initialValue = -1f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(210, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "orbVoiceMotion"
                    )
                    val glowColorTarget = when {
                        speaking -> AccentCyan
                        agentState is AgentState.Listening -> AccentRed
                        agentState is AgentState.Thinking -> AccentPurple
                        agentState is AgentState.ExecutingPlan -> Color(0xFF35D07F)
                        else -> Color(0xFF22D3FF)
                    }
                    val glowColor by animateColorAsState(
                        targetValue = glowColorTarget,
                        animationSpec = tween(320),
                        label = "orbStateColor"
                    )
                    val heard by VoiceSubtitles.heard.collectAsState()
                    val reply by VoiceSubtitles.reply.collectAsState()
                    val history by chatViewModel.conversationHistory.collectAsState()
                    val spoken = history.filter { it.text.isNotBlank() }
                    // Show live recognition text until the saved conversation catches up.
                    val heardNote = if (heard.isNotBlank() && spoken.none { it.text == heard }) heard else ""
                    // Do not show a reply twice once it is in the saved history.
                    val note = if (reply.isNotBlank() && spoken.none { it.text == reply }) reply else ""
                    val historyState = rememberLazyListState()
                    val lines = spoken.size + (if (heardNote.isNotBlank()) 1 else 0) +
                        (if (note.isNotBlank()) 1 else 0)
                    LaunchedEffect(lines, heardNote, note, spoken.lastOrNull()?.text) {
                        if (lines > 0) historyState.animateScrollToItem(lines - 1)
                    }
                    val orbColor = when {
                        speaking -> AccentCyan
                        agentState is AgentState.Listening -> AccentRed
                        agentState is AgentState.Thinking -> AccentPurple
                        agentState is AgentState.ExecutingPlan -> AccentCyan
                        else -> TextPrimary
                    }
                    // 0..1, breathing slowly when idle and fast when active.
                    val breath = (pulse - 1f) / 0.06f
                    val status = when {
                        speaking -> "SPEAKING"
                        agentState is AgentState.Listening -> "LISTENING"
                        agentState is AgentState.Thinking -> "THINKING"
                        agentState is AgentState.ExecutingPlan -> "WORKING"
                        agentState is AgentState.Error -> "ERROR"
                        else -> if (serviceOn) "WAKE WORD ACTIVE" else "SERVICE STOPPED"
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.size(380.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Light that starts exactly at the orb's outer ring and fades out.
                            // No glow until Jarvis is switched ON.
                            if (serviceOn) Canvas(Modifier.fillMaxSize()) {
                                val orb = 126.dp.toPx() * (if (active) pulse else 1f)
                                val outer = size.minDimension / 2f
                                val edge = orb / outer
                                val strength = (if (active) 0.85f else 0.8f) + breath * 0.15f
                                fun at(t: Float) = edge + (1f - edge) * t
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        0f to glowColor.copy(alpha = strength),
                                        edge to glowColor.copy(alpha = strength),
                                        at(0.12f) to glowColor.copy(alpha = strength * 0.85f),
                                        at(0.35f) to glowColor.copy(alpha = strength * 0.55f),
                                        at(0.65f) to glowColor.copy(alpha = strength * 0.22f),
                                        1f to Color.Transparent,
                                        center = center,
                                        radius = outer
                                    ),
                                    radius = outer
                                )
                            }
                            // Only the ring art is shown; its black surround is clipped away
                            // so it cannot hide the glow.
                            Box(
                                modifier = Modifier
                                    .size(252.dp)
                                    .graphicsLayer {
                                        scaleX = pulse
                                        scaleY = pulse
                                        // Make the speaking vibration visible rather than sub-pixel.
                                        translationX = if (speaking) shake * 6.dp.toPx() else 0f
                                        translationY = if (speaking) shake * 2.5.dp.toPx() else 0f
                                        rotationZ = if (speaking) shake * 1.6f else 0f
                                    }
                                    .clip(CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(R.drawable.jarvis_orb),
                                    contentDescription = "Jarvis logo",
                                    contentScale = ContentScale.Crop,
                                    // Recolours the blue ring art to match the glow.
                                    colorFilter = if (!serviceOn) null else ColorFilter.colorMatrix(
                                        ColorMatrix(
                                            floatArrayOf(
                                                0.7f * (1f - glowColor.red), 0f, glowColor.red, 0f, 0f,
                                                0.7f * (1f - glowColor.green), 0f, glowColor.green, 0f, 0f,
                                                0.7f * (1f - glowColor.blue), 0f, glowColor.blue, 0f, 0f,
                                                0f, 0f, 0f, 1f, 0f
                                            )
                                        )
                                    ),
                                    modifier = Modifier.requiredSize(304.dp)
                                )
                            }
                        }

                        Text(
                            status,
                            color = orbColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )

                        // Conversation history under the orb; swipe up to read older lines.
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            state = historyState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(spoken) { message ->
                                val mine = message.sender == ChatMessage.Sender.USER
                                Text(
                                    (if (mine) "You: " else "Jarvis: ") + message.text,
                                    color = if (mine) TextSecondary else TextPrimary,
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // Live speech-to-text subtitle; disappears when saved history catches up.
                            if (heardNote.isNotBlank()) {
                                item(key = "live-heard-subtitle") {
                                    Text(
                                        "You: $heardNote",
                                        color = AccentRed,
                                        fontSize = 15.sp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            // Short notes that are not saved, e.g. "Sollunga sir".
                            if (note.isNotBlank()) {
                                item {
                                    Text(
                                        "Jarvis: $note",
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (serviceOn) {
                                    OpenDroidService.stop(context)
                                } else {
                                    startService()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (serviceOn) AccentRed else AccentCyan,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.fillMaxWidth(0.72f)
                        ) {
                            Text(if (serviceOn) "STOP JARVIS" else "START JARVIS")
                        }

                        if (serviceOn) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { triggerVoiceInput() },
                                modifier = Modifier.fillMaxWidth(0.72f)
                            ) {
                                Text("TAP TO SPEAK", color = AccentCyan)
                            }
                        }

                        if (lines == 0) Text(
                            text = if (serviceOn) {
                                "Say “Jarvis” to wake me up, or tap to speak."
                            } else {
                                "Start Jarvis to enable offline wake-word listening."
                            },
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun TeachTab(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember { mutableStateOf(com.opendroid.ai.core.teaching.TeachingMode.active) }
    var names by remember { mutableStateOf(com.opendroid.ai.core.teaching.TeachingMode.lessonNames(context)) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (on) "Teaching ON: neenga phone la pannunga, Jarvis kathukkum." else "Teaching OFF",
            color = if (on) androidx.compose.ui.graphics.Color(0xFFFFB300) else TextSecondary,
            fontSize = 14.sp
        )
        Button(onClick = {
            if (on) com.opendroid.ai.core.teaching.TeachingMode.stop(context)
            else com.opendroid.ai.core.teaching.TeachingMode.start(context)
            on = com.opendroid.ai.core.teaching.TeachingMode.active
            names = com.opendroid.ai.core.teaching.TeachingMode.lessonNames(context)
        }) { Text(if (on) "Stop and save lesson" else "Start teaching") }
        Text("Saved lessons", color = TextSecondary, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(names) { n ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(n, color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp)
                    OutlinedButton(onClick = {
                        com.opendroid.ai.core.teaching.TeachingMode.delete(context, n)
                        names = com.opendroid.ai.core.teaching.TeachingMode.lessonNames(context)
                    }) { Text("Delete", fontSize = 12.sp) }
                }
            }
        }
    }
}

package com.opendroid.ai.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.geometry.Offset
import kotlin.math.sin
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opendroid.ai.R
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.cloud.RemoteAppConfig
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
import com.opendroid.ai.ui.viewmodel.PlanViewModel
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

private enum class JarvisMode { ORBIT, CHAT, MOVE, VOICE, REVERSE }

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
    val planViewModel: PlanViewModel = hiltViewModel()
    val agentState by chatViewModel.agentState.collectAsState()
    val chatSessions by chatViewModel.sessions.collectAsState()
    val currentChatSessionId = chatSessions.firstOrNull { it.isCurrent }?.id
    var mode by remember { mutableStateOf(JarvisMode.ORBIT) }
    var showSettings by remember { mutableStateOf(false) }
    val serviceOn by OpenDroidService.isRunning.collectAsState()
    var remoteConfig by remember { mutableStateOf(RemoteAppConfig()) }

    // Refresh server-controlled UI settings while JARVIS is open. Changes to
    // Kotlin/native logic remain signed APK updates; no downloaded code is run.
    LaunchedEffect(Unit) {
        while (true) {
            RemoteAppConfig.fetch()?.let { config ->
                if (config.configVersion >= remoteConfig.configVersion) {
                    remoteConfig = config
                    android.util.Log.i("JarvisRemoteConfig", "Applied configVersion=${config.configVersion} to home UI")
                } else {
                    android.util.Log.w("JarvisRemoteConfig", "Ignored older configVersion=${config.configVersion}; current=${remoteConfig.configVersion}")
                }
            }
            kotlinx.coroutines.delay(5 * 60 * 1000L)
        }
    }

    // Jarvis stays switched on after you leave the app; reopening brings it back.
    LaunchedEffect(Unit) {
        OpenDroidService.resumeIfWanted(context)
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            OpenDroidService.start(context)
        }
    }

    fun startService() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            OpenDroidService.start(context)
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
    val inAppWakeOn = !serviceOn && !showSettings && mode == JarvisMode.ORBIT && appResumed
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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AppTheme.colors.surface,
                            AppTheme.colors.background,
                            AppTheme.colors.background
                        )
                    )
                )
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
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "JARVIS",
                            color = TextPrimary,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 2.8.sp
                        )
                        Text(
                            text = "PERSONAL AI SYSTEM",
                            color = AccentCyan.copy(alpha = 0.78f),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.1.sp
                        )
                        Text(
                            text = "MADE BY KINGSTON",
                            color = AccentCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 2.0.sp
                        )
                    }
                }

                if (!showSettings) {
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AppTheme.colors.surface)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AccentCyan)
                    }
                }
            }

            if (!showSettings) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppTheme.colors.surface)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        JarvisMode.ORBIT to "Orbit",
                        JarvisMode.CHAT to "Chat",
                        JarvisMode.MOVE to "Move",
                        JarvisMode.VOICE to "Voice",
                        JarvisMode.REVERSE to "Reverse"
                    ).forEach { (m, label) ->
                        val selected = mode == m
                        val tabBackground by animateColorAsState(
                            targetValue = if (selected) Color(0xFF103B55) else Color.Transparent,
                            animationSpec = tween(220),
                            label = "mainTabBackground"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(tabBackground)
                                .clickable {
                                    mode = m
                                    if (m != JarvisMode.ORBIT && serviceOn) {
                                        context.startService(
                                            Intent(context, OpenDroidService::class.java)
                                                .setAction(OpenDroidService.ACTION_CANCEL_LISTEN)
                                        )
                                    }
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(AccentCyan)
                                    )
                                }
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) Color.White else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = showSettings to mode,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 18 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 24 })
                },
                label = "jarvisMainScreenTransition"
            ) { (settingsVisible, currentMode) ->
                when {
                settingsVisible -> SettingsScreen(
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
                    modifier = Modifier.fillMaxSize()
                )

                currentMode == JarvisMode.CHAT -> ChatScreen(
                    viewModel = chatViewModel,
                    modifier = Modifier.fillMaxSize(),
                    textOnly = true
                )

                currentMode == JarvisMode.MOVE -> PlanScreen(
                    viewModel = planViewModel,
                    currentSessionId = currentChatSessionId,
                    modifier = Modifier.fillMaxSize()
                )

                currentMode == JarvisMode.VOICE -> VoiceScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier.fillMaxSize()
                )

                currentMode == JarvisMode.REVERSE -> ReverseEngineeringScreen(
                    modifier = Modifier.fillMaxSize()
                )

                else -> {
                    val liveSpeaking by VoiceSubtitles.liveSpeaking.collectAsState()
                    // Live voice can speak while AgentState remains Listening; audio state wins.
                    val speaking = agentState is AgentState.Speaking || liveSpeaking
                    // Keep the orb visibly alive while the wake service is running.
                    // ExecutingPlan must count as active or the orb appears frozen during tools.
                    val active = serviceOn ||
                        agentState is AgentState.Listening ||
                        agentState is AgentState.Thinking ||
                        agentState is AgentState.ExecutingPlan ||
                        agentState is AgentState.Speaking || speaking
                    // Use a subtle idle pulse; faster motion is reserved for active voice work.
                    val pulse by animateFloatAsState(
                        targetValue = when {
                            speaking -> remoteConfig.ui.orb.speakingScale.coerceIn(1f, 1.15f)
                            agentState is AgentState.Listening ||
                                agentState is AgentState.Thinking ||
                                agentState is AgentState.ExecutingPlan -> remoteConfig.ui.orb.activeScale.coerceIn(1f, 1.15f)
                            serviceOn -> remoteConfig.ui.orb.idleScale.coerceIn(1f, 1.15f)
                            else -> 1f
                        },
                        animationSpec = if (active) {
                            infiniteRepeatable(
                                animation = tween(
                                    durationMillis = when {
                                        speaking -> remoteConfig.ui.orb.speakingDurationMs.coerceIn(250, 3000)
                                        serviceOn && agentState is AgentState.Idle -> remoteConfig.ui.orb.idleDurationMs.coerceIn(500, 6000)
                                        else -> remoteConfig.ui.orb.activeDurationMs.coerceIn(250, 3000)
                                    },
                                    easing = LinearEasing
                                ),
                                repeatMode = RepeatMode.Reverse
                            )
                        } else tween(500),
                        label = "orbPulse"
                    )
                    // Slow, lightweight orbital motion: decorative rings remain subtle while
                    // the central JARVIS mark changes state. Animation only runs on Live Voice.
                    val orbitAngle by animateFloatAsState(
                        targetValue = if (active) 360f else 0f,
                        animationSpec = if (active) {
                            infiniteRepeatable(
                                animation = tween(12000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            )
                        } else tween(320),
                        label = "orbitalMotion"
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
                    // Orbit is a voice-only surface: never read the general chat history here.
                    // Chat-tab messages remain exclusively inside ChatScreen.
                    val heardNote = heard
                    val note = reply
                    val historyState = rememberLazyListState()
                    val lines = (if (heardNote.isNotBlank()) 1 else 0) +
                        (if (note.isNotBlank()) 1 else 0)
                    LaunchedEffect(lines, heardNote, note) {
                        if (lines > 0) historyState.animateScrollToItem(lines - 1)
                    }
                    val orbColor = when {
                        speaking -> AccentCyan
                        agentState is AgentState.Listening -> AccentRed
                        agentState is AgentState.Thinking -> AccentPurple
                        agentState is AgentState.ExecutingPlan -> AccentCyan
                        else -> TextPrimary
                    }
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
                        // Responsive, low-overdraw orb: it fits the available phone width
                        // instead of forcing a 380dp surface beyond the screen edges.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(292.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val radius = (size.minDimension / 2f - 10.dp.toPx()).coerceAtLeast(1f)
                                val coreRadius = radius * 0.69f
                                val ringColor = glowColor.copy(alpha = if (serviceOn) 0.54f else 0.20f)
                                if (serviceOn) {
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            0f to glowColor.copy(alpha = 0.22f),
                                            coreRadius / radius to glowColor.copy(alpha = 0.12f),
                                            1f to Color.Transparent,
                                            center = center,
                                            radius = radius
                                        ),
                                        radius = radius
                                    )
                                }
                                drawCircle(ringColor.copy(alpha = ringColor.alpha * 0.42f), radius * 0.96f, style = Stroke(1.dp.toPx()))
                                drawCircle(ringColor, radius * 0.84f, style = Stroke(1.2.dp.toPx()))
                                drawArc(
                                    color = glowColor.copy(alpha = if (speaking) 0.95f else 0.72f),
                                    startAngle = orbitAngle - 48f,
                                    sweepAngle = 92f,
                                    useCenter = false,
                                    topLeft = Offset(center.x - radius * 0.84f, center.y - radius * 0.84f),
                                    size = androidx.compose.ui.geometry.Size(radius * 1.68f, radius * 1.68f),
                                    style = Stroke(width = 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                                drawArc(
                                    color = Color(0xFFB388FF).copy(alpha = 0.56f),
                                    startAngle = -orbitAngle * 0.65f + 155f,
                                    sweepAngle = 42f,
                                    useCenter = false,
                                    topLeft = Offset(center.x - radius * 0.96f, center.y - radius * 0.96f),
                                    size = androidx.compose.ui.geometry.Size(radius * 1.92f, radius * 1.92f),
                                    style = Stroke(width = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                                val angle = Math.toRadians(orbitAngle.toDouble())
                                val dot = Offset(
                                    center.x + kotlin.math.cos(angle).toFloat() * radius * 0.84f,
                                    center.y + kotlin.math.sin(angle).toFloat() * radius * 0.84f
                                )
                                drawCircle(glowColor, 3.dp.toPx(), dot)
                            }
                            Image(
                                painter = painterResource(R.drawable.jarvis_orb),
                                contentDescription = "JARVIS live orb",
                                contentScale = ContentScale.Fit,
                                colorFilter = if (!serviceOn) null else ColorFilter.tint(glowColor.copy(alpha = 0.88f)),
                                modifier = Modifier
                                    .size(224.dp)
                                    .graphicsLayer {
                                        scaleX = if (active) pulse else 1f
                                        scaleY = if (active) pulse else 1f
                                    }
                            )
                        }

                        Text(
                            status,
                            color = orbColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        VoiceStateVisualizer(
                            color = glowColor,
                            active = active || speaking,
                            modifier = Modifier.fillMaxWidth(0.72f)
                        )
                        Spacer(Modifier.height(8.dp))

                        // Conversation history under the orb; swipe up to read older lines.
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            state = historyState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
}


@Composable
private fun VoiceStateVisualizer(
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val phase by animateFloatAsState(
        targetValue = if (active) (Math.PI * 2.0).toFloat() else 0f,
        animationSpec = if (active) {
            infiniteRepeatable(
                animation = tween(durationMillis = 850, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else tween(300),
        label = "voiceVisualizerPhase"
    )
    Canvas(modifier = modifier.height(34.dp)) {
        val count = 25
        val barWidth = 3.dp.toPx()
        val gap = (size.width - count * barWidth) / (count - 1)
        val centerY = size.height / 2f
        for (i in 0 until count) {
            val position = i / (count - 1f)
            val centerEnvelope = (1f - kotlin.math.abs(position - 0.5f) * 1.75f).coerceIn(0f, 1f)
            val waveA = (sin(phase + i * 0.43f) + 1f) / 2f
            val waveB = (sin(phase * 1.7f - i * 0.21f) + 1f) / 2f
            val energy = waveA * 0.68f + waveB * 0.32f
            val envelope = if (active) (0.12f + energy * 0.88f) * (0.24f + centerEnvelope * 0.76f) else 0.22f
            val barHeight = size.height * envelope * (if (active) 0.96f else 0.38f)
            val x = i * (barWidth + gap)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = if (active) 0.42f else 0.16f),
                        color.copy(alpha = if (active) 1f else 0.38f),
                        color.copy(alpha = if (active) 0.52f else 0.18f)
                    ),
                    startY = centerY - barHeight / 2f,
                    endY = centerY + barHeight / 2f
                ),
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth, barWidth)
            )
            if (active && i % 4 == 0) {
                drawCircle(
                    color = color.copy(alpha = 0.72f),
                    radius = 1.2.dp.toPx(),
                    center = Offset(x + barWidth / 2f, centerY - barHeight / 2f - 3.dp.toPx())
                )
            }
        }
    }
}

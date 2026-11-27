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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.ColorMatrix
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

private enum class JarvisMode { ORBIT, CHAT, MOVE }

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
                        JarvisMode.MOVE to "Move"
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

                else -> {
                    val liveSpeaking by VoiceSubtitles.liveSpeaking.collectAsState()
                    // Live voice can speak while AgentState remains Listening; audio state wins.
                    val speaking = agentState is AgentState.Speaking || liveSpeaking
                    val active = agentState is AgentState.Listening ||
                        agentState is AgentState.Thinking ||
                        agentState is AgentState.Speaking || speaking
                    // Animate continuously only while listening, thinking or speaking.
                    // Idle Orbit settles to a still frame to avoid wasting battery.
                    val pulse by animateFloatAsState(
                        targetValue = when {
                            speaking -> 1.075f
                            active -> 1.06f
                            else -> 1f
                        },
                        animationSpec = if (active) {
                            infiniteRepeatable(
                                animation = tween(
                                    durationMillis = if (speaking) 420 else 760,
                                    easing = LinearEasing
                                ),
                                repeatMode = RepeatMode.Reverse
                            )
                        } else tween(500),
                        label = "orbPulse"
                    )
                    val shake by animateFloatAsState(
                        targetValue = if (speaking) 1f else 0f,
                        animationSpec = if (speaking) {
                            infiniteRepeatable(
                                animation = tween(210, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        } else tween(140),
                        label = "orbVoiceMotion"
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

                    val orbitPurple = AccentPurple
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
                            Canvas(Modifier.fillMaxSize()) {
                                val orb = 126.dp.toPx() * (if (active) pulse else 1f)
                                val outer = size.minDimension / 2f
                                val edge = orb / outer
                                val strength = if (serviceOn) (if (active) 0.85f else 0.55f) + breath * 0.15f else 0.16f
                                fun at(t: Float) = edge + (1f - edge) * t
                                if (serviceOn) {
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
                                val ringColor = glowColor.copy(alpha = if (serviceOn) 0.42f else 0.16f)
                                val ringRadius = 150.dp.toPx()
                                drawCircle(color = ringColor, radius = ringRadius, style = Stroke(width = 1.dp.toPx()))
                                drawCircle(color = ringColor.copy(alpha = ringColor.alpha * 0.55f), radius = 166.dp.toPx(), style = Stroke(width = 0.7.dp.toPx()))
                                val angle = Math.toRadians(orbitAngle.toDouble())
                                val dotX = center.x + kotlin.math.cos(angle).toFloat() * ringRadius
                                val dotY = center.y + kotlin.math.sin(angle).toFloat() * ringRadius
                                drawCircle(color = glowColor.copy(alpha = if (serviceOn) 0.95f else 0.5f), radius = 3.2.dp.toPx(), center = Offset(dotX, dotY))
                                val secondAngle = angle * -0.72 - 1.2
                                val secondX = center.x + kotlin.math.cos(secondAngle).toFloat() * 166.dp.toPx()
                                val secondY = center.y + kotlin.math.sin(secondAngle).toFloat() * 166.dp.toPx()
                                drawCircle(color = orbitPurple.copy(alpha = if (serviceOn) 0.78f else 0.3f), radius = 2.2.dp.toPx(), center = Offset(secondX, secondY))
                                // Voice-reactive energy particles radiate from the orbit while JARVIS speaks.
                                if (speaking) {
                                    for (i in 0 until 16) {
                                        val particleAngle = angle + i * (Math.PI * 2.0 / 16.0)
                                        val travel = 174.dp.toPx() + (sin(angle * 2.0 + i).toFloat() + 1f) * 15.dp.toPx()
                                        val particleX = center.x + kotlin.math.cos(particleAngle).toFloat() * travel
                                        val particleY = center.y + kotlin.math.sin(particleAngle).toFloat() * travel
                                        val fade = 0.28f + ((sin(angle + i * 0.7).toFloat() + 1f) * 0.28f)
                                        drawCircle(
                                            color = if (i % 3 == 0) orbitPurple.copy(alpha = fade) else glowColor.copy(alpha = fade),
                                            radius = if (i % 4 == 0) 2.4.dp.toPx() else 1.35.dp.toPx(),
                                            center = Offset(particleX, particleY)
                                        )
                                    }
                                }
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
        val count = 31
        val barWidth = 3.dp.toPx()
        val gap = (size.width - count * barWidth) / (count - 1)
        val centerY = size.height / 2f
        for (i in 0 until count) {
            val wave = (sin(phase + i * 0.43f) + 1f) / 2f
            val envelope = (0.22f + wave * if (active) 0.78f else 0.28f)
            val barHeight = size.height * envelope * (if (active) 0.92f else 0.48f)
            drawRoundRect(
                color = color.copy(alpha = if (active) 0.9f else 0.38f),
                topLeft = Offset(i * (barWidth + gap), centerY - barHeight / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth, barWidth)
            )
        }
    }
}

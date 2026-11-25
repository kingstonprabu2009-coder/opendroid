package com.opendroid.ai.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import kotlin.math.PI
import kotlin.math.sin

private enum class JarvisMode { LIVE_VOICE, CHAT, MEMORY }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.jarvis_orb),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "JARVIS",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                    }
                }

                if (!showSettings) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
                    }
                }
            }

            if (!showSettings) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF0E1D2C))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    JarvisTab(
                        label = "Live Voice",
                        selected = mode == JarvisMode.LIVE_VOICE,
                        modifier = Modifier.weight(1f),
                        onClick = { mode = JarvisMode.LIVE_VOICE }
                    )
                    JarvisTab(
                        label = "Chat",
                        selected = mode == JarvisMode.CHAT,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            mode = JarvisMode.CHAT
                            // Chat is typed only: close the mic if it was listening.
                            if (serviceOn) {
                                context.startService(
                                    Intent(context, OpenDroidService::class.java)
                                        .setAction(OpenDroidService.ACTION_CANCEL_LISTEN)
                                )
                            }
                        }
                    )
                    JarvisTab(
                        label = "Memory",
                        selected = mode == JarvisMode.MEMORY,
                        modifier = Modifier.weight(1f),
                        onClick = { mode = JarvisMode.MEMORY }
                    )
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
                    val active = agentState is AgentState.Listening ||
                        agentState is AgentState.Thinking ||
                        agentState is AgentState.Speaking
                    val speaking = agentState is AgentState.Speaking

                    // Own clock driven by screen frames, so colours and shaking still move
                    // even when the phone's animation speed is switched off (battery saver).
                    var motion by remember { mutableStateOf(0f) }
                    LaunchedEffect(serviceOn, active) {
                        if (serviceOn && active) {
                            motion = 0f
                            var last = withFrameNanos { it }
                            while (true) {
                                withFrameNanos { now ->
                                    motion += (now - last) / 1_000_000_000f
                                    last = now
                                }
                            }
                        }
                    }
                    val twoPi = (2.0 * PI).toFloat()
                    val pulse = if (active) {
                        1f + 0.05f * (0.5f + 0.5f * sin(motion * twoPi / 1.1f))
                    } else 1f
                    val shake = if (speaking) sin(motion * twoPi / 0.16f) else 0f
                    // 0..1, breathing while Jarvis is busy.
                    val breath = (pulse - 1f) / 0.05f

                    // Cyan blue > red > green, changing all the time while Jarvis is
                    // listening, thinking or speaking. Steady cyan blue when only waiting.
                    val glowColors = listOf(Color(0xFF22D3FF), Color(0xFFFF2D3F), Color(0xFF1FFF6A))
                    val glowColor = if (serviceOn && active) {
                        val phase = motion / 1.6f
                        val step = phase.toInt() % 3
                        val fade = ((phase % 1f - 0.5f) / 0.5f).coerceIn(0f, 1f)
                        lerp(glowColors[step], glowColors[(step + 1) % 3], fade)
                    } else glowColors[0]

                    val reply by VoiceSubtitles.reply.collectAsState()
                    val history by chatViewModel.conversationHistory.collectAsState()
                    val spoken = history.filter { it.text.isNotBlank() }
                    // Do not show a reply twice once it is in the saved history.
                    val note = if (spoken.any { it.text == reply }) "" else reply
                    val historyState = rememberLazyListState()
                    val lines = spoken.size + if (note.isNotBlank()) 1 else 0
                    LaunchedEffect(lines, spoken.lastOrNull()?.text) {
                        if (lines > 0) historyState.animateScrollToItem(lines - 1)
                    }
                    val status = when (agentState) {
                        is AgentState.Listening -> "LISTENING"
                        is AgentState.Thinking -> "THINKING"
                        is AgentState.Speaking -> "SPEAKING"
                        is AgentState.ExecutingPlan -> "WORKING"
                        is AgentState.Error -> "ERROR"
                        else -> if (serviceOn) "WAKE WORD ACTIVE" else "SERVICE STOPPED"
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth(0.88f)
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val w = maxWidth
                            // Light that starts exactly at the orb's outer edge and fades out.
                            // No glow until Jarvis is switched ON.
                            if (serviceOn) Canvas(Modifier.fillMaxSize()) {
                                val orb = size.minDimension * 0.40f * pulse
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
                            // The whole round logo, ring and "Made by Kingston" included.
                            Box(
                                modifier = Modifier
                                    .size(w * 0.80f)
                                    .graphicsLayer {
                                        scaleX = pulse
                                        scaleY = pulse
                                        // Shakes while Jarvis is speaking.
                                        translationX = if (speaking) shake * 5f else 0f
                                        rotationZ = if (speaking) shake * 1.5f else 0f
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
                                    modifier = Modifier.requiredSize(w * 1.02f)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background((if (serviceOn) glowColor else TextSecondary).copy(alpha = 0.14f))
                                .padding(horizontal = 14.dp, vertical = 5.dp)
                        ) {
                            Text(
                                status,
                                color = if (serviceOn) glowColor else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 2.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        // Subtitles: one fixed card under the orb; swipe up to read older lines.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Brush.verticalGradient(listOf(Color(0xFF0C1A2B), Color(0xFF08121D))))
                                .border(
                                    1.dp,
                                    if (serviceOn) glowColor.copy(alpha = 0.35f) else Color(0xFF1B3147),
                                    RoundedCornerShape(16.dp)
                                )
                        ) {
                            if (lines == 0) Text(
                                text = if (serviceOn) {
                                    "Say “Jarvis” to wake me up, or tap to speak."
                                } else {
                                    "Start Jarvis to enable offline wake-word listening."
                                },
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp)
                            )
                            LazyColumn(
                                state = historyState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(spoken) { message ->
                                    val mine = message.sender == ChatMessage.Sender.USER
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start
                                    ) {
                                        Text(
                                            if (mine) "You:" else "Jarvis:",
                                            color = if (mine) TextSecondary else AccentCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            message.text,
                                            color = TextPrimary,
                                            fontSize = 15.sp,
                                            textAlign = if (mine) TextAlign.End else TextAlign.Start,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (mine) Color(0xFF14304A) else Color(0xFF0F2234))
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                                // Short notes that are not saved, e.g. "Sollunga sir".
                                if (note.isNotBlank()) {
                                    item {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(
                                                "Jarvis:",
                                                color = AccentCyan,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(note, color = TextPrimary, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (serviceOn) {
                                        OpenDroidService.stop(context)
                                    } else {
                                        startService()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (serviceOn) AccentRed else AccentCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (serviceOn) "STOP JARVIS" else "START JARVIS", maxLines = 1, fontSize = 13.sp)
                            }
                            if (serviceOn) {
                                OutlinedButton(
                                    onClick = { triggerVoiceInput() },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("TAP TO SPEAK", color = AccentCyan, maxLines = 1, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JarvisTab(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) AccentCyan else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

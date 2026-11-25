package com.opendroid.ai.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.opendroid.ai.R
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.service.OpenDroidService
import com.opendroid.ai.ui.theme.AccentCyan
import com.opendroid.ai.ui.theme.AccentPurple
import com.opendroid.ai.ui.theme.AccentRed
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import com.opendroid.ai.ui.viewmodel.ChatViewModel
import com.opendroid.ai.ui.viewmodel.MemoryViewModel
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

private enum class JarvisMode { LIVE_VOICE, MESSAGE, MEMORY }

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
                    data = Uri.parse("package:" + context.packageName)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { mode = JarvisMode.LIVE_VOICE },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (mode == JarvisMode.LIVE_VOICE) AccentCyan else TextSecondary
                        )
                    ) { Text("Live Voice", maxLines = 1, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { mode = JarvisMode.MESSAGE },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (mode == JarvisMode.MESSAGE) AccentCyan else TextSecondary
                        )
                    ) { Text("Message", maxLines = 1, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { mode = JarvisMode.MEMORY },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (mode == JarvisMode.MEMORY) AccentCyan else TextSecondary
                        )
                    ) { Text("Memory", maxLines = 1, fontSize = 12.sp) }
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

                mode == JarvisMode.MESSAGE -> ChatScreen(
                    viewModel = chatViewModel,
                    modifier = Modifier.weight(1f)
                )

                else -> {
                    val active = agentState is AgentState.Listening ||
                        agentState is AgentState.Thinking ||
                        agentState is AgentState.Speaking
                    val transition = rememberInfiniteTransition(label = "jarvisOrb")
                    val pulse by transition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.06f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "orbPulse"
                    )
                    val spin by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(3500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "orbSpin"
                    )
                    val shake by transition.animateFloat(
                        initialValue = -1f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(80, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "orbShake"
                    )
                    val speaking = agentState is AgentState.Speaking
                    val rainbow = Brush.sweepGradient(
                        listOf(
                            Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFFFF4081),
                            Color(0xFFFFAB00), Color(0xFF00E676), Color(0xFF00E5FF)
                        )
                    )
                    val orbColor = when (agentState) {
                        is AgentState.Listening -> AccentRed
                        is AgentState.Thinking -> AccentPurple
                        is AgentState.Speaking -> AccentCyan
                        is AgentState.ExecutingPlan -> AccentCyan
                        else -> TextPrimary
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
                            .padding(bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.size(270.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // Colourful rotating light ring around the logo.
                            Box(
                                modifier = Modifier
                                    .size(270.dp)
                                    .graphicsLayer {
                                        rotationZ = if (active) spin else spin / 4f
                                        alpha = if (active) 1f else 0.35f
                                    }
                                    .clip(CircleShape)
                                    .background(rainbow)
                            )
                            Image(
                                painter = painterResource(R.drawable.jarvis_icon_fg),
                                contentDescription = "Jarvis logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(250.dp)
                                    .clip(CircleShape)
                                    .graphicsLayer {
                                        val scale = if (active) pulse else 1f
                                        scaleX = scale
                                        scaleY = scale
                                        // Shakes while Jarvis is speaking.
                                        translationX = if (speaking) shake * 9f else 0f
                                        rotationZ = if (speaking) shake * 2f else 0f
                                    }
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            "JARVIS",
                            color = TextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            status,
                            color = orbColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(28.dp))

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

                        Spacer(Modifier.height(12.dp))
                        Text(
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

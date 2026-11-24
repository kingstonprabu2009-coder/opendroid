package com.opendroid.ai.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import com.opendroid.ai.ui.viewmodel.SettingsViewModel

private enum class JarvisMode { LIVE_VOICE, MESSAGE }

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
    val agentState by chatViewModel.agentState.collectAsState()
    var mode by remember { mutableStateOf(JarvisMode.LIVE_VOICE) }
    var showSettings by remember { mutableStateOf(false) }
    val serviceOn by OpenDroidService.isRunning.collectAsState()

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { mode = JarvisMode.LIVE_VOICE },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (mode == JarvisMode.LIVE_VOICE) AccentCyan else TextSecondary
                            )
                        ) { Text("Live Voice") }
                        Spacer(Modifier.size(6.dp))
                        OutlinedButton(
                            onClick = { mode = JarvisMode.MESSAGE },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (mode == JarvisMode.MESSAGE) AccentCyan else TextSecondary
                            )
                        ) { Text("Message") }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextSecondary)
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
                        targetValue = 1.07f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(850),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "orbPulse"
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
                            modifier = Modifier
                                .size(260.dp)
                                .clip(CircleShape)
                                .background(orbColor.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.jarvis_icon_fg),
                                contentDescription = "Jarvis orb",
                                colorFilter = ColorFilter.tint(orbColor),
                                modifier = Modifier
                                    .size(220.dp)
                                    .graphicsLayer {
                                        val scale = if (active) pulse else 1f
                                        scaleX = scale
                                        scaleY = scale
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

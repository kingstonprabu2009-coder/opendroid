package com.opendroid.ai.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opendroid.ai.R
import com.opendroid.ai.ui.theme.DarkBackground
import com.opendroid.ai.ui.theme.DarkSurface
import com.opendroid.ai.ui.theme.TextPrimary
import com.opendroid.ai.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SplashScreen(onNavigateNext: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.82f) }
    val dotPulse = remember { Animatable(0.4f) }
    val orbitAngle = remember { Animatable(0f) }
    val logoWave = remember { Animatable(1f) }
    val colorPhase = remember { Animatable(0f) }

    LaunchedEffect(key1 = true) {
        launch {
            dotPulse.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
        launch {
            orbitAngle.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(5200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            logoWave.animateTo(
                targetValue = 1.055f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1050, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }
        launch {
            colorPhase.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(3600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
            )
        }
        alpha.animateTo(1f, animationSpec = tween(650, easing = FastOutSlowInEasing))
        delay(2100)
        alpha.animateTo(0f, animationSpec = tween(450, easing = FastOutSlowInEasing))
        onNavigateNext()
    }

    val phase = colorPhase.value * (Math.PI * 2.0).toFloat()
    val cyan = Color(0xFF27DFFF)
    val violet = Color(0xFF9B6BFF)
    val pink = Color(0xFFFF58C8)
    val orbitColor = when {
        sin(phase.toDouble()) > 0.35 -> cyan
        sin(phase.toDouble()) < -0.35 -> pink
        else -> violet
    }

    Box(
        modifier = Modifier.fillMaxSize().background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha.value).scale(scale.value)
        ) {
            // Animated planetary orbit surrounding the JARVIS emblem.
            Box(
                modifier = Modifier.size(232.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radiusA = size.minDimension * 0.405f
                    val radiusB = size.minDimension * 0.465f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(orbitColor.copy(alpha = 0.20f), Color.Transparent),
                            center = center,
                            radius = radiusB * 1.35f
                        ),
                        radius = radiusB * 1.35f,
                        center = center
                    )
                    drawCircle(orbitColor.copy(alpha = 0.42f), radiusA, center, style = Stroke(width = 1.35.dp.toPx()))
                    drawCircle(violet.copy(alpha = 0.26f), radiusB, center, style = Stroke(width = 0.8.dp.toPx()))
                    val a = Math.toRadians(orbitAngle.value.toDouble())
                    val b = Math.toRadians((-orbitAngle.value * 0.72f - 48f).toDouble())
                    val p1 = Offset(center.x + cos(a).toFloat() * radiusA, center.y + sin(a).toFloat() * radiusA)
                    val p2 = Offset(center.x + cos(b).toFloat() * radiusB, center.y + sin(b).toFloat() * radiusB)
                    drawCircle(cyan.copy(alpha = 0.18f), 10.dp.toPx(), p1)
                    drawCircle(cyan, 4.2.dp.toPx(), p1)
                    drawCircle(pink.copy(alpha = 0.18f), 8.dp.toPx(), p2)
                    drawCircle(pink, 3.2.dp.toPx(), p2)
                    // Small orbit sparks create a subtle energy trail.
                    for (i in 0 until 10) {
                        val t = a - i * 0.12
                        val point = Offset(center.x + cos(t).toFloat() * radiusA, center.y + sin(t).toFloat() * radiusA)
                        drawCircle(orbitColor.copy(alpha = 0.48f * (1f - i / 10f)), (2.2f - i * 0.12f).dp.toPx(), point)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(154.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(orbitColor.copy(alpha = 0.22f), Color.Transparent)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.jarvis_icon_fg),
                        contentDescription = "JARVIS animated logo",
                        modifier = Modifier.size(142.dp).scale(logoWave.value)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "JARVIS",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                color = TextPrimary,
                letterSpacing = 8.sp
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = "At your service, Sir",
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.SansSerif,
                color = TextSecondary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "MADE BY KINGSTON",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = orbitColor.copy(alpha = 0.9f),
                letterSpacing = 3.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkSurface)
                    .border(1.dp, orbitColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(TextPrimary.copy(alpha = dotPulse.value))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SYSTEM ONLINE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
    }
}
